package network.ike.lease.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The process that holds the IDE's build locks (IKE-Network/ike-issues#1315).
 *
 * <p><b>Why a separate process.</b> The lock is a POSIX record lock, and
 * those belong to a process: closing <em>any</em> descriptor of the file in
 * that process releases them. Inside the IDE that would happen unasked —
 * the holder's own write to the file wakes the file watcher, and the VFS
 * refresh and the indexer open and close it. Here nothing else ever opens
 * the file. A second reason: Maven treats a lock whose recorded pid is one
 * of its ancestors as its own outer build and runs unlocked, so recording
 * the IDE's pid would let every Maven started from the IDE's terminal or
 * Maven tool window walk through the IDE's lock. The helper is nobody's
 * ancestor.
 *
 * <p><b>Lifetime.</b> One helper per IDE, started on the first compile and
 * kept. It exits when its standard input closes or its parent dies, and
 * the OS releases whatever it held, so a crashed IDE never wedges a tree.
 *
 * <p><b>Protocol.</b> One tab-separated request per line on standard
 * input, one reply per line on standard output; it starts by printing
 * {@code ready<TAB><pid>}.
 * <ul>
 *   <li>{@code acquire<TAB>root<TAB>goals<TAB>projects} answers
 *       {@code acquired}, {@code held} (this helper already holds it),
 *       {@code busy} (another process holds it), or
 *       {@code unavailable<TAB>reason}. It never waits; the caller polls.</li>
 *   <li>{@code release<TAB>root} answers {@code released} or
 *       {@code not-held}.</li>
 * </ul>
 */
public final class BuildLockHelper {

    private final Map<Path, Held> held = new HashMap<>();
    private final String host = BuildLock.hostName();
    private final long pid = ProcessHandle.current().pid();

    private record Held(FileChannel channel, FileLock lock) { }

    private BuildLockHelper() { }

    /**
     * Serves lock requests until standard input closes.
     *
     * @param args unused
     * @throws IOException if standard input cannot be read
     */
    public static void main(String[] args) throws IOException {
        ProcessHandle.current().parent().ifPresent(parent ->
                parent.onExit().thenRun(() -> System.exit(0)));
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        BuildLockHelper helper = new BuildLockHelper();
        out.println("ready\t" + helper.pid);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            out.println(helper.handle(line));
        }
        helper.releaseAll();
    }

    String handle(String line) {
        String[] fields = line.split("\t", -1);
        return switch (fields[0]) {
            case "acquire" -> fields.length == 4
                    ? acquire(Path.of(fields[1]), fields[2], fields[3])
                    : "unavailable\tmalformed request";
            case "release" -> fields.length == 2 ? release(Path.of(fields[1])) : "not-held";
            default -> "unavailable\tunknown request " + fields[0];
        };
    }

    private String acquire(Path root, String goals, String projects) {
        Path lockFile = BuildLock.lockFile(root);
        if (held.containsKey(lockFile)) {
            return "held";
        }
        FileChannel channel;
        try {
            Files.createDirectories(lockFile.getParent());
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            return "unavailable\t" + oneLine(e.toString());
        }
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                channel.close();
                return "held";
            }
            if (lock == null) {
                channel.close();
                return "busy";
            }
            BuildLock.Holder holder = new BuildLock.Holder(pid, host, Instant.now(), goals, projects,
                    root.toAbsolutePath().normalize());
            write(channel, holder);
            held.put(lockFile, new Held(channel, lock));
            return "acquired";
        } catch (IOException e) {
            close(channel);
            return "unavailable\t" + oneLine(e.toString());
        }
    }

    private String release(Path root) {
        Held lock = held.remove(BuildLock.lockFile(root));
        if (lock == null) {
            return "not-held";
        }
        try {
            lock.lock.release();
        } catch (IOException ignored) {
            // Closing the channel releases it anyway.
        }
        close(lock.channel);
        return "released";
    }

    private void releaseAll() {
        held.values().forEach(lock -> close(lock.channel));
        held.clear();
    }

    /** Writes the record through the locked channel, never through a second descriptor. */
    private static void write(FileChannel channel, BuildLock.Holder holder) throws IOException {
        byte[] bytes = holder.render().getBytes(StandardCharsets.UTF_8);
        channel.truncate(0);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long position = 0;
        while (buffer.hasRemaining()) {
            position += channel.write(buffer, position);
        }
        channel.force(false);
    }

    private static void close(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Process exit releases it in the end.
        }
    }

    private static String oneLine(String text) {
        return text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }
}
