package network.ike.lease.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The IDE's side of the per-tree build lock (IKE-Network/ike-issues#1315):
 * takes a tree's {@link BuildLock} for one compile and gives it back.
 *
 * <p>The lock itself is held by a {@link BuildLockHelper} child process,
 * started on first use and kept for the life of this client; see that
 * class for why it cannot be held in the IDE. This side asks the helper,
 * and while another build holds the tree it reads that build's record and
 * polls, so the caller can show who it waits for and let the operator
 * cancel or build anyway. Nothing here touches a UI.
 *
 * <p>Thread-safe. Waiting happens on the caller's thread, which must not
 * be a UI thread.
 */
public final class BuildLockClient implements AutoCloseable {

    /** How often a waiting compile asks again. */
    public static final Duration POLL = Duration.ofMillis(250);

    /** How long one helper request may take before the helper is presumed hung. */
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(15);

    private final List<String> helperCommand;
    private Process helper;
    private Writer requests;
    private BlockingQueue<String> replies;

    /**
     * How an {@link #acquire} came out.
     */
    public sealed interface Outcome permits Acquired, Overridden, Cancelled, Unavailable { }

    /**
     * The tree is locked for this compile; {@link #release} it after.
     *
     * @param lockFile the lock file
     * @param waited   how long another build kept this one waiting
     * @param holder   whom it waited for, if anyone
     */
    public record Acquired(Path lockFile, Duration waited, Optional<BuildLock.Holder> holder)
            implements Outcome { }

    /**
     * The operator chose to build anyway, unlocked, while another build held the tree.
     *
     * @param lockFile the lock file
     * @param holder   the build still holding it, if recorded
     * @param waited   how long this one had waited
     */
    public record Overridden(Path lockFile, Optional<BuildLock.Holder> holder, Duration waited)
            implements Outcome { }

    /**
     * The operator cancelled the compile while another build held the tree.
     *
     * @param lockFile the lock file
     * @param holder   the build holding it, if recorded
     * @param waited   how long this one had waited
     */
    public record Cancelled(Path lockFile, Optional<BuildLock.Holder> holder, Duration waited)
            implements Outcome { }

    /**
     * The lock could not be taken for a reason other than another build;
     * the compile should proceed unlocked, as the extension's builds do.
     *
     * @param lockFile the lock file
     * @param reason   what went wrong
     */
    public record Unavailable(Path lockFile, String reason) implements Outcome { }

    /**
     * What a waiting compile consults and tells, once per poll.
     */
    public interface Waiter {

        /**
         * Reports whom the compile is waiting for.
         *
         * @param holder the build holding the tree, if its record is readable yet
         * @param waited how long the compile has waited
         */
        void waiting(Optional<BuildLock.Holder> holder, Duration waited);

        /**
         * Whether the operator cancelled the compile.
         *
         * @return {@code true} to stop waiting and cancel
         */
        boolean cancelled();

        /**
         * Whether the operator chose to build without the lock.
         *
         * @return {@code true} to stop waiting and build unlocked
         */
        boolean buildAnyway();
    }

    /**
     * Creates a client that starts its helper with the given command.
     *
     * @param helperCommand the command line that runs {@link BuildLockHelper}
     */
    public BuildLockClient(List<String> helperCommand) {
        this.helperCommand = List.copyOf(helperCommand);
    }

    /**
     * The command that runs the helper on this JVM's own Java, from the jar
     * or directory this class was loaded from.
     *
     * @return the command line
     * @throws IllegalStateException if this class's location cannot be found
     */
    public static List<String> defaultHelperCommand() {
        Path classes;
        try {
            classes = Path.of(BuildLockHelper.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            throw new IllegalStateException("cannot locate the lease core's classes", e);
        }
        return helperCommand(classes);
    }

    /**
     * The command that runs the helper on this JVM's own Java, from the
     * given jar or classes directory of the lease core.
     *
     * @param classes the lease core's jar or classes directory
     * @return the command line
     */
    public static List<String> helperCommand(Path classes) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        // A small, quick JVM: it does nothing but hold file locks.
        return List.of(java.toString(), "-Xmx16m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                "-Djava.awt.headless=true", "-cp", classes.toString(), BuildLockHelper.class.getName());
    }

    /**
     * Takes a tree's lock, waiting while another build holds it.
     *
     * @param root     the tree's root (see {@link BuildLock#treeRoot})
     * @param goals    what the compile is, as the lock file shows it to Maven
     * @param projects the modules it compiles, or empty
     * @param waiter   told of the wait, and asked whether to stop
     * @return how it came out; never throws for a held lock
     */
    public Outcome acquire(Path root, String goals, String projects, Waiter waiter) {
        Path lockFile = BuildLock.lockFile(root);
        String request = "acquire\t" + root.toAbsolutePath().normalize() + "\t" + field(goals)
                + "\t" + field(projects);
        Instant start = Instant.now();
        Optional<BuildLock.Holder> lastHolder = Optional.empty();
        while (true) {
            String reply;
            try {
                reply = request(request);
            } catch (IOException e) {
                return new Unavailable(lockFile, e.getMessage());
            }
            Duration waited = Duration.between(start, Instant.now());
            if (reply.equals("acquired") || reply.equals("held")) {
                return new Acquired(lockFile, waited, lastHolder);
            }
            if (!reply.equals("busy")) {
                return new Unavailable(lockFile, reply.startsWith("unavailable\t")
                        ? reply.substring("unavailable\t".length()) : reply);
            }
            // Read only while another process holds it: the helper, which
            // may hold it, never reads, and this process never holds.
            Optional<BuildLock.Holder> current = BuildLock.read(lockFile);
            if (current.isPresent()) {
                lastHolder = current;
            }
            waiter.waiting(lastHolder, waited);
            if (waiter.cancelled()) {
                return new Cancelled(lockFile, lastHolder, waited);
            }
            if (waiter.buildAnyway()) {
                return new Overridden(lockFile, lastHolder, waited);
            }
            try {
                Thread.sleep(POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Cancelled(lockFile, lastHolder, Duration.between(start, Instant.now()));
            }
        }
    }

    /**
     * Gives a tree's lock back. Idempotent: releasing a lock this client
     * does not hold does nothing.
     *
     * @param root the tree's root
     * @return {@code true} when a held lock was released
     */
    public boolean release(Path root) {
        synchronized (this) {
            if (helper == null || !helper.isAlive()) {
                return false;   // a dead helper's locks died with it
            }
        }
        try {
            return request("release\t" + root.toAbsolutePath().normalize()).equals("released");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The helper's process id, if it is running.
     *
     * @return the pid that lock files held by this client record
     */
    public synchronized Optional<Long> helperPid() {
        return helper != null && helper.isAlive() ? Optional.of(helper.pid()) : Optional.empty();
    }

    /** Stops the helper, which releases everything it holds. */
    @Override
    public synchronized void close() {
        if (helper == null) {
            return;
        }
        try {
            requests.close();
        } catch (IOException ignored) {
            // Destroying it below releases its locks too.
        }
        try {
            if (!helper.waitFor(2, TimeUnit.SECONDS)) {
                helper.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            helper.destroyForcibly();
        }
        helper = null;
    }

    private synchronized String request(String line) throws IOException {
        ensureHelper();
        try {
            requests.write(line);
            requests.write('\n');
            requests.flush();
        } catch (IOException e) {
            stopHelper();
            throw new IOException("the build-lock helper is gone: " + e.getMessage(), e);
        }
        String reply = awaitReply();
        if (reply == null) {
            stopHelper();
            throw new IOException("the build-lock helper did not answer within " + BuildLock.brief(REPLY_TIMEOUT));
        }
        return reply;
    }

    private void ensureHelper() throws IOException {
        if (helper != null && helper.isAlive()) {
            return;
        }
        Process process = new ProcessBuilder(helperCommand)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String text = in.readLine(); text != null; text = in.readLine()) {
                    lines.add(text);
                }
            } catch (IOException ignored) {
                // The helper ended.
            }
        }, "ike-build-lock-helper-reader");
        reader.setDaemon(true);
        reader.start();
        helper = process;
        requests = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        replies = lines;
        String ready = awaitReply();
        if (ready == null || !ready.startsWith("ready\t")) {
            stopHelper();
            throw new IOException("the build-lock helper did not start: " + helperCommand);
        }
    }

    private String awaitReply() {
        try {
            return replies.poll(REPLY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private void stopHelper() {
        if (helper != null) {
            helper.destroyForcibly();
            helper = null;
        }
    }

    private static String field(String text) {
        return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
