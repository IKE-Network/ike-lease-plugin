package network.ike.lease;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bridge to {@code ~/ike-dev/scripts/lease.sh}.
 *
 * <p>The lease protocol is deliberately implemented once, in the shell
 * script that the Claude hook and the operator also use. Re-implementing
 * epoch arithmetic and staleness rules here would create a second
 * implementation to keep in step, and a fencing system whose two halves
 * disagree is worse than one with no fencing at all.
 */
public final class LeaseCli {

    /** Seconds to wait for the lease script before giving up. */
    private static final long TIMEOUT_SECONDS = 10L;

    private final Path ikeDev;

    /**
     * Creates a bridge rooted at the given synchronized development folder.
     *
     * @param ikeDev the {@code ike-dev} root, normally {@code ~/ike-dev}
     */
    public LeaseCli(Path ikeDev) {
        this.ikeDev = ikeDev;
    }

    /**
     * Creates a bridge rooted at {@code ~/ike-dev}.
     *
     * @return a bridge for the current user's development folder
     */
    public static LeaseCli forCurrentUser() {
        return new LeaseCli(Path.of(System.getProperty("user.home"), "ike-dev"));
    }

    /**
     * Reports whether the lease machinery is present on this machine.
     *
     * @return {@code true} when both the script and the machine identity exist
     */
    public boolean isAvailable() {
        return Files.isExecutable(script())
                && Files.exists(Path.of(System.getProperty("user.home"), ".ike-machine-id"));
    }

    /**
     * Resolves a filesystem path to the working set that contains it.
     *
     * @param path any path, inside or outside the development folder
     * @return the working-set directory name, or {@code null} when the path
     *         lies outside {@code ike-dev} or names the root itself
     */
    public String resolve(Path path) {
        Result result = run("resolve", path.toString());
        return result.exitCode() == 0 && !result.stdout().isBlank()
                ? result.stdout().trim() : null;
    }

    /**
     * Reads the state of a working set's lease without changing it.
     *
     * @param workingSet the working-set directory name
     * @return the current state as this machine sees it
     */
    public LeaseState status(String workingSet) {
        Result result = run("status", workingSet);
        return LeaseState.fromDescription(result.stdout());
    }

    /**
     * Takes the lease when doing so needs no human decision.
     *
     * <p>Free and expired leases are acquired silently. A lease held live
     * by another machine is left alone — taking it over is the operator's
     * decision, surfaced through the takeover dialog.
     *
     * @param workingSet the working-set directory name
     * @return {@code true} when this machine now holds the lease
     */
    public boolean ensure(String workingSet) {
        return run("ensure", workingSet).exitCode() == 0;
    }

    /**
     * Takes the lease from whoever holds it, advancing the fencing epoch.
     *
     * @param workingSet the working-set directory name
     * @return {@code true} when the takeover was written successfully
     */
    public boolean forceAcquire(String workingSet) {
        return run("acquire", workingSet, "--force").exitCode() == 0;
    }

    /**
     * Releases a lease this machine holds.
     *
     * @param workingSet the working-set directory name
     * @return {@code true} when the lease was released
     */
    public boolean release(String workingSet) {
        return run("release", workingSet).exitCode() == 0;
    }

    /**
     * Reads the human-readable description of a lease, holder included.
     *
     * @param workingSet the working-set directory name
     * @return the description line, never {@code null}
     */
    public String describe(String workingSet) {
        return run("status", workingSet).stdout().trim();
    }

    /**
     * Returns the directory holding lease records.
     *
     * @return the {@code leases} directory under the development folder
     */
    public Path leaseDirectory() {
        return ikeDev.resolve("leases");
    }

    /**
     * Returns this machine's stable identity.
     *
     * @return the captured machine id, or {@code "unknown"} when unreadable
     */
    public String machineId() {
        try {
            return Files.readString(
                    Path.of(System.getProperty("user.home"), ".ike-machine-id"),
                    StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "unknown";
        }
    }

    private Path script() {
        return ikeDev.resolve("scripts").resolve("lease.sh");
    }

    private Result run(String... args) {
        List<String> command = new ArrayList<>();
        command.add(script().toString());
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, output.toString());
            }
            return new Result(process.exitValue(), output.toString());
        } catch (IOException e) {
            return new Result(-1, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, "");
        }
    }

    /**
     * The outcome of one lease-script invocation.
     *
     * @param exitCode the script's exit status
     * @param stdout   its combined output
     */
    public record Result(int exitCode, String stdout) { }
}
