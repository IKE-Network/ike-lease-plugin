package network.ike.lease.core;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The per-tree build lock that {@code ike-workspace-extension} 15 takes for
 * every Maven build, spelled here so the IDE takes the same one around its
 * own compiles (IKE-Network/ike-issues#1314, #1315).
 *
 * <p><b>The lock.</b> An exclusive {@link java.nio.channels.FileChannel}
 * lock on {@code <root>/.mvn/target/build.lock}. Maven and the IDE exclude
 * each other only because both take an OS lock on the same file, so the
 * primitive must not change on either side.
 *
 * <p><b>The file.</b> The holder writes what it is into the locked file,
 * one {@code key=value} per line: {@code pid}, {@code host},
 * {@code started}, {@code goals}, {@code projects}, {@code root}. A waiter
 * reads it to say whom it waits for. The format is the extension's
 * {@code BuildLock.Holder}, kept byte-for-byte; the core's tests run the
 * released extension in child JVMs against it.
 *
 * <p><b>The tree.</b> The root Maven locks is the build's root directory,
 * the nearest {@code .mvn}, except that a member of a working set built
 * from its own root locks the working set's tree, since it writes there.
 * {@link #treeRoot} applies the same rule to an IDE project's base path.
 *
 * <p>The names are spelled here rather than borrowed: the extension loads
 * into Maven's core and depends on nothing of ours. Keep the two in step.
 */
public final class BuildLock {

    /** The lock file, relative to the tree's root. */
    public static final Path LOCK_FILE = Path.of(".mvn", "target", "build.lock");

    /** The manifest file name of a working set. */
    static final String MANIFEST_FILE = "working-set.yaml";

    /** The manifest's former name, still read. */
    static final String LEGACY_MANIFEST_FILE = "workspace.yaml";

    /** What a tree's {@code .mvn/extensions.xml} names when its Maven builds take the lock. */
    static final String EXTENSION_ARTIFACT = "ike-workspace-extension";

    private BuildLock() { }

    /**
     * What a build is, as the lock file records it.
     *
     * @param pid      the holding process
     * @param host     the host it runs on
     * @param started  when it took the lock
     * @param goals    what it is doing: Maven's goals, or the IDE's build kind
     * @param projects the {@code -pl} selection, or the IDE's modules; may be empty
     * @param root     the tree it locks
     */
    public record Holder(long pid, String host, Instant started, String goals,
                         String projects, Path root) {

        /**
         * This process, about to build {@code root}.
         *
         * @param root     the tree's root
         * @param goals    what the build is doing
         * @param projects the projects it builds, or empty
         * @return the holder record for this process
         */
        public static Holder current(Path root, String goals, String projects) {
            return new Holder(ProcessHandle.current().pid(), hostName(), Instant.now(),
                    goals, projects, root);
        }

        /**
         * The lock file's text for this holder.
         *
         * @return the {@code key=value} lines, newline-terminated
         */
        public String render() {
            Map<String, String> lines = new LinkedHashMap<>();
            lines.put("pid", Long.toString(pid));
            lines.put("host", host);
            lines.put("started", started.toString());
            lines.put("goals", goals);
            lines.put("projects", projects);
            lines.put("root", root.toString());
            StringBuilder text = new StringBuilder();
            lines.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
            return text.toString();
        }

        /**
         * Reads a lock file's text.
         *
         * @param text the file's contents
         * @return the holder, or empty when the text is not a complete record
         *         (a holder between truncating and writing, or no holder yet)
         */
        public static Optional<Holder> parse(String text) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (String line : text.split("\\R")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    fields.put(line.substring(0, eq), line.substring(eq + 1));
                }
            }
            try {
                return Optional.of(new Holder(
                        Long.parseLong(fields.get("pid")),
                        fields.getOrDefault("host", "?"),
                        Instant.parse(fields.get("started")),
                        fields.getOrDefault("goals", ""),
                        fields.getOrDefault("projects", ""),
                        Path.of(fields.getOrDefault("root", "?"))));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }

        /**
         * One line: who, where, since when, doing what. The same wording
         * the extension prints while it waits.
         *
         * @param now the moment to measure the holder's age against
         * @return the description
         */
        public String describe(Instant now) {
            StringBuilder text = new StringBuilder();
            text.append("pid ").append(pid).append(" on ").append(host)
                    .append(", started ").append(started)
                    .append(" (").append(brief(Duration.between(started, now))).append(" ago)");
            if (!goals.isBlank()) {
                text.append(": ").append(goals);
            }
            if (!projects.isBlank()) {
                text.append(" -pl ").append(projects);
            }
            return text.toString();
        }
    }

    /**
     * The lock file of a tree.
     *
     * @param root the tree's root
     * @return {@code <root>/.mvn/target/build.lock}, absolute and normalized
     */
    public static Path lockFile(Path root) {
        return root.toAbsolutePath().normalize().resolve(LOCK_FILE);
    }

    /**
     * Reads the record of whoever holds a tree's lock.
     *
     * <p>Never call this from the process that holds the lock: POSIX record
     * locks belong to the process, and closing the descriptor this opens
     * would release them. The IDE holds its locks in a helper process for
     * exactly that reason (see {@link BuildLockHelper}).
     *
     * @param lockFile the lock file
     * @return the recorded holder, or empty when there is none or it is unreadable
     */
    public static Optional<Holder> read(Path lockFile) {
        try {
            return Holder.parse(Files.readString(lockFile, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The tree a project's builds lock, if its Maven builds take the lock.
     *
     * <p>The build root is the nearest directory at or above {@code base}
     * holding {@code .mvn}, as the Maven launcher finds it. A working-set
     * member built from its own root locks its working set instead. Only a
     * tree whose {@code .mvn/extensions.xml} loads
     * {@code ike-workspace-extension} qualifies: nothing else on its Maven
     * side takes the lock, and a lock file in any other repository would be
     * litter.
     *
     * @param base the project's base directory
     * @return the tree's root, or empty when its builds take no lock
     */
    public static Optional<Path> treeRoot(Path base) {
        Path start = base.toAbsolutePath().normalize();
        Path buildRoot = null;
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(".mvn"))) {
                buildRoot = dir;
                break;
            }
        }
        if (buildRoot == null) {
            return Optional.empty();
        }
        Path root = workingSetOf(buildRoot).orElse(buildRoot);
        return loadsExtension(root) || loadsExtension(buildRoot) ? Optional.of(root) : Optional.empty();
    }

    /** The working set a build root is a member of, by the extension's rule. */
    static Optional<Path> workingSetOf(Path buildRoot) {
        if (manifestIn(buildRoot) != null) {
            return Optional.empty();
        }
        for (Path ancestor = buildRoot.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            Path manifest = manifestIn(ancestor);
            if (manifest == null) {
                continue;
            }
            Path relative = ancestor.relativize(buildRoot);
            if (relative.getNameCount() == 1 && manifestMembers(manifest).contains(relative.toString())) {
                return Optional.of(ancestor);
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Path manifestIn(Path dir) {
        Path current = dir.resolve(MANIFEST_FILE);
        if (Files.exists(current)) {
            return current;
        }
        Path legacy = dir.resolve(LEGACY_MANIFEST_FILE);
        return Files.exists(legacy) ? legacy : null;
    }

    /** The members a manifest lists under {@code subprojects:}, as the extension reads them. */
    static List<String> manifestMembers(Path manifest) {
        try {
            List<String> members = new ArrayList<>();
            boolean inSubprojects = false;
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (line.startsWith("subprojects:")) {
                    inSubprojects = true;
                    continue;
                }
                if (inSubprojects) {
                    if (line.strip().startsWith("#")) {
                        continue;
                    }
                    if (!line.startsWith("  ") && !line.isBlank()) {
                        break;
                    }
                    if (line.matches("^  [A-Za-z0-9][^:\\s]*:\\s*$")) {
                        members.add(line.strip().replace(":", ""));
                    }
                }
            }
            return members;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static boolean loadsExtension(Path root) {
        try {
            Path extensions = root.resolve(".mvn").resolve("extensions.xml");
            return Files.isRegularFile(extensions)
                    && Files.readString(extensions, StandardCharsets.UTF_8).contains(EXTENSION_ARTIFACT);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * A short duration, as the extension prints one: {@code 850ms},
     * {@code 4.2s}, {@code 37s}, {@code 3m05s}, {@code 1h02m}.
     *
     * @param duration the duration
     * @return its brief form
     */
    public static String brief(Duration duration) {
        boolean negative = duration.isNegative();
        Duration d = duration.abs();
        String text;
        if (d.toSeconds() < 1) {
            text = d.toMillis() + "ms";
        } else if (d.toMinutes() < 1) {
            long tenths = d.toMillis() / 100;
            text = d.toSeconds() < 10 ? (tenths / 10) + "." + (tenths % 10) + "s" : d.toSeconds() + "s";
        } else if (d.toHours() < 1) {
            text = d.toMinutes() + "m" + String.format("%02ds", d.toSecondsPart());
        } else {
            text = d.toHours() + "h" + String.format("%02dm", d.toMinutesPart());
        }
        return negative ? "-" + text : text;
    }

    static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            String env = System.getenv("HOSTNAME");
            return env == null ? "unknown-host" : env;
        }
    }
}
