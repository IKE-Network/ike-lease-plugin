package network.ike.lease.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs git as an external process — the headless host's runner.
 *
 * <p>Spawning {@code git} itself is portable everywhere the fleet could
 * go ({@code git.exe} included); it is spawning a shell that is not,
 * which is why the runner builds the argument vector directly and never
 * goes through one.
 *
 * <p>Every invocation carries a timeout. A hung git process is exactly
 * the failure class IKE-Network/ike-issues#1052 documents (fsmonitor
 * deadlocking under a file watcher), and a materializer that hangs with
 * it converts a diagnosable failure into a silent one. On timeout the
 * process is killed and the result reports it.
 */
public final class ProcessGitRunner implements GitRunner {

    /** Default ceiling for one git invocation; fetches can be slow. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);

    private final Duration timeout;

    /**
     * Creates a runner with the {@linkplain #DEFAULT_TIMEOUT default}
     * per-invocation timeout.
     */
    public ProcessGitRunner() {
        this(DEFAULT_TIMEOUT);
    }

    /**
     * Creates a runner with the given per-invocation timeout.
     *
     * @param timeout the ceiling for one git invocation
     */
    public ProcessGitRunner(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Runs one git command in the given directory, draining standard
     * output and standard error concurrently so neither pipe can fill and
     * deadlock the child.
     *
     * @param directory the working directory for the invocation
     * @param args      the git arguments, excluding the leading {@code git}
     * @return the completed invocation's exit code and captured output
     */
    @Override
    public GitResult run(Path directory, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        try {
            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .start();
            process.getOutputStream().close();
            StringBuilder err = new StringBuilder();
            Thread drainer = Thread.ofVirtual().start(
                    () -> drain(process.getErrorStream(), err));
            String out = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                drainer.join(Duration.ofSeconds(5).toMillis());
                return new GitResult(-1, out,
                        "git " + String.join(" ", args) + " timed out after "
                                + timeout + " in " + directory);
            }
            drainer.join();
            return new GitResult(process.exitValue(), out, err.toString());
        } catch (IOException e) {
            return new GitResult(-1, "", "could not run git in " + directory
                    + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new GitResult(-1, "", "interrupted while running git in "
                    + directory);
        }
    }

    private static void drain(InputStream stream, StringBuilder into) {
        try (InputStream in = stream) {
            byte[] bytes = in.readAllBytes();
            into.append(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            into.append("(stderr lost: ").append(e.getMessage()).append(')');
        }
    }
}
