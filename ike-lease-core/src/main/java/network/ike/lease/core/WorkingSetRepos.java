package network.ike.lease.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Repository discovery inside one working set — the walk the materializer
 * (IKE-Network/ike-issues#1057) and the ref stamper
 * (IKE-Network/ike-issues#1069) share, kept single so the two features can
 * never disagree about what counts as a member repository.
 */
final class WorkingSetRepos {

    /** Directories never descended into while discovering members. */
    private static final List<String> SKIPPED_DIRECTORIES =
            List.of(".git", ".ike", ".idea", ".stversions", "target");

    /** How deep member discovery looks below the working-set root. */
    private static final int MEMBER_DISCOVERY_DEPTH = 3;

    private WorkingSetRepos() { }

    /**
     * Discovers a working set's member repositories: the root itself,
     * plus every git repository below it, without descending into
     * repositories, hidden directories, or build output.
     *
     * @param root     the working set's directory
     * @param progress sink for one-line narration of skipped subtrees
     * @return member paths relative to the root, the root itself as the
     *         empty string, shallowest first
     */
    static List<String> discover(Path root, Consumer<String> progress) {
        List<String> members = new ArrayList<>();
        if (hasGit(root)) {
            members.add("");
        }
        collect(root, root, 0, members, progress);
        members.sort(Comparator
                .comparingInt((String member) ->
                        member.split("/", -1).length)
                .thenComparing(Comparator.naturalOrder()));
        return members;
    }

    private static void collect(Path root, Path directory, int depth,
                                List<String> members,
                                Consumer<String> progress) {
        if (depth >= MEMBER_DISCOVERY_DEPTH) {
            return;
        }
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.filter(Files::isDirectory).toList()) {
                String childName = child.getFileName().toString();
                if (childName.startsWith(".")
                        || SKIPPED_DIRECTORIES.contains(childName)) {
                    continue;
                }
                if (hasGit(child)) {
                    members.add(root.relativize(child).toString());
                } else {
                    collect(root, child, depth + 1, members, progress);
                }
            }
        } catch (IOException e) {
            // An unreadable directory hides its members; surfacing that as
            // a hard failure would block the readable rest. Skip it.
            progress.accept("skipping unreadable " + directory + ": "
                    + e.getMessage());
        }
    }

    /**
     * Reports whether a directory has real git state.
     *
     * @param directory the candidate repository directory
     * @return {@code true} for a gitfile or a {@code .git} directory with
     *         a {@code HEAD}; {@code false} for the sync layer's empty
     *         {@code .git} husks
     */
    static boolean hasGit(Path directory) {
        Path git = directory.resolve(".git");
        // A gitfile (worktree or submodule pointer) is real git state. A
        // .git DIRECTORY is real only when it has a HEAD: the sync
        // layer's `(?d).git/` pattern excludes contents but carries the
        // directory entry itself, so peers hold empty ".git" husks —
        // measured fleet-wide 2026-08-22: every root and sibling on both
        // peer machines was a husk, invisible to an existence check.
        return Files.isRegularFile(git) || Files.exists(git.resolve("HEAD"));
    }
}
