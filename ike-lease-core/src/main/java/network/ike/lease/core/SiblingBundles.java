package network.ike.lease.core;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import network.ike.lease.core.GitRunner.GitResult;

/**
 * A sibling working set's committed history, carried between machines as
 * git bundles in the synced lease folder (IKE-Network/ike-issues#1216).
 *
 * <p>{@code .git} never syncs, and a sibling's {@code origin} is the local
 * parent, which never sees the sibling's commits — so without help, a
 * commit made on one machine exists nowhere another machine can fetch it
 * from. The holder therefore writes, for every member repository, a
 * bundle of its feature branch's commits that are not on its local
 * {@code main}, to {@code leases/<working-set>.bundles/}. Bundles are
 * ordinary files: the sync layer carries them like the record beside
 * them, with no SSH and no requirement that the writing machine be on.
 *
 * <p>A bundle names its own head, so it is its own alignment target: the
 * taker needs no stamp to know where the history ends
 * ({@link SiblingAligner}). Each write replaces a member's bundle
 * atomically (temporary file, then a rename), so the sync layer never
 * carries half a bundle.
 *
 * <p><b>A bundle only moves forward.</b> A member's bundle is replaced
 * only when this machine's branch already contains the existing bundle's
 * head. A machine that took the working set but has not yet aligned —
 * Claude's fence takes silently and renews at half-life — would otherwise
 * overwrite the previous holder's history with its own stale branch. Such
 * a member's bundle is kept, and the reason reported.
 */
final class SiblingBundles {

    /** Suffix of the per-working-set bundle directory under {@code leases/}. */
    static final String DIRECTORY_SUFFIX = ".bundles";

    /** File name standing for the working set's root repository. */
    static final String ROOT_FILE = "_root.bundle";

    private static final String EXTENSION = ".bundle";
    private static final String SEPARATOR_ESCAPE = "__";
    private static final String MAIN = "main";

    private SiblingBundles() { }

    /**
     * Locates a working set's bundle directory.
     *
     * @param ikeDev the development-folder root
     * @param name   the working-set name
     * @return {@code leases/<working-set>.bundles}
     */
    static Path directory(Path ikeDev, WorkingSetName name) {
        return ikeDev.resolve("leases").resolve(name.value() + DIRECTORY_SUFFIX);
    }

    /**
     * Names the bundle file for a member repository.
     *
     * @param memberPath the member's path relative to the working set,
     *                   {@code .} or empty for the root repository
     * @return the file name, flat: path separators are escaped
     */
    static String fileName(String memberPath) {
        if (memberPath.isEmpty() || ".".equals(memberPath)) {
            return ROOT_FILE;
        }
        return memberPath.replace("/", SEPARATOR_ESCAPE) + EXTENSION;
    }

    /**
     * Recovers the member path a bundle file stands for.
     *
     * @param fileName the bundle's file name
     * @return the member path, {@code .} for the root repository
     */
    static String memberPath(String fileName) {
        if (ROOT_FILE.equals(fileName)) {
            return ".";
        }
        String base = fileName.endsWith(EXTENSION)
                ? fileName.substring(0, fileName.length() - EXTENSION.length())
                : fileName;
        return base.replace(SEPARATOR_ESCAPE, "/");
    }

    /**
     * Lists a working set's bundle files.
     *
     * @param ikeDev the development-folder root
     * @param name   the working-set name
     * @return the bundle files, sorted by name; empty when there are none
     */
    static List<Path> list(Path ikeDev, WorkingSetName name) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                directory(ikeDev, name), "*" + EXTENSION)) {
            stream.forEach(files::add);
        } catch (IOException e) {
            return List.of();
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    /**
     * Reports whether every stamped member on a feature branch has a
     * bundle — the cheap, git-free check a renew makes before deciding
     * there is nothing to refresh.
     *
     * @param ikeDev the development-folder root
     * @param name   the working-set name
     * @param stamps this machine's current stamps
     * @return {@code true} when no feature-branch member lacks a bundle
     */
    static boolean complete(Path ikeDev, WorkingSetName name,
                            List<RepoStamp> stamps) {
        Path dir = directory(ikeDev, name);
        return stamps.stream()
                .filter(stamp -> !MAIN.equals(stamp.branch()))
                .allMatch(stamp -> Files.isRegularFile(
                        dir.resolve(fileName(stamp.path()))));
    }

    /**
     * Writes a bundle for every member repository on a branch other than
     * {@code main}, holding the branch's commits not on the member's
     * local {@code main}; removes bundles for members with nothing to
     * carry. Problems are collected, never thrown: the lease write that
     * follows must not depend on git.
     *
     * @param ikeDev the development-folder root
     * @param name   the sibling's working-set name
     * @param git    the git runner
     * @return one line per member whose bundle could not be written
     */
    static List<String> write(Path ikeDev, WorkingSetName name, GitRunner git) {
        List<String> problems = new ArrayList<>();
        Path root = ikeDev.resolve(name.value());
        if (!Files.isDirectory(root)) {
            return problems;
        }
        Path dir = directory(ikeDev, name);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            problems.add("cannot create " + dir + ": " + e.getMessage());
            return problems;
        }
        Set<String> kept = new HashSet<>();
        for (String member : WorkingSetRepos.discover(root, line -> { })) {
            Path repoDir = member.isEmpty() ? root : root.resolve(member);
            String file = fileName(member);
            Path target = dir.resolve(file);
            GitResult branch = git.run(repoDir,
                    List.of("symbolic-ref", "--short", "HEAD"));
            if (!branch.ok() || MAIN.equals(branch.stdoutTrimmed())) {
                deleteQuietly(target);  // detached, unborn, or on main: nothing to carry
                continue;
            }
            String regression = regression(git, repoDir, target,
                    branch.stdoutTrimmed());
            if (regression != null) {
                problems.add((member.isEmpty() ? "." : member) + ": "
                        + regression);
                kept.add(file);             // the existing bundle is ahead
                continue;
            }
            List<String> create = new ArrayList<>(List.of("bundle", "create",
                    "--quiet"));
            Path temporary = dir.resolve("." + file + ".partial");
            create.add(temporary.toString());
            create.add("refs/heads/" + branch.stdoutTrimmed());
            if (git.run(repoDir, List.of("rev-parse", "--verify", "--quiet",
                    "refs/heads/" + MAIN)).ok()) {
                create.add("^refs/heads/" + MAIN);
            }
            GitResult result = git.run(repoDir, create);
            if (!result.ok()) {
                deleteQuietly(temporary);
                if (result.stderr().contains("empty bundle")) {
                    deleteQuietly(target);  // no commits beyond main
                } else {
                    problems.add((member.isEmpty() ? "." : member) + ": "
                            + result.stderr().strip());
                    kept.add(file);         // keep the last good bundle
                }
                continue;
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
                kept.add(file);
            } catch (IOException e) {
                deleteQuietly(temporary);
                problems.add((member.isEmpty() ? "." : member)
                        + ": cannot place bundle: " + e.getMessage());
            }
        }
        for (Path stale : list(ikeDev, name)) {
            if (!kept.contains(stale.getFileName().toString())) {
                deleteQuietly(stale);
            }
        }
        return problems;
    }

    /**
     * Reports why writing this member's bundle would move its history
     * backwards, or {@code null} when it would not: there is no existing
     * bundle, it carries another branch, or this repository's branch
     * already contains its head.
     */
    private static String regression(GitRunner git, Path repoDir,
                                     Path existing, String branch) {
        if (!Files.isRegularFile(existing)) {
            return null;
        }
        GitResult heads = git.run(repoDir, List.of("bundle", "list-heads",
                existing.toAbsolutePath().toString()));
        SiblingAligner.BundleHead head = heads.ok()
                ? SiblingAligner.BundleHead.parse(heads.stdout()) : null;
        if (head == null || !head.branch().equals(branch)) {
            return null;                    // unreadable or another branch
        }
        boolean contained = git.run(repoDir, List.of("merge-base",
                "--is-ancestor", head.commit(), "refs/heads/" + branch)).ok();
        return contained ? null
                : "kept the existing bundle: its head "
                        + head.commit().substring(0, 12) + " is not on this "
                        + "machine's " + branch + " yet — align first "
                        + "(MaterializeCli repair, or reopen the project)";
    }

    /**
     * Removes a working set's bundle directory and everything in it — the
     * cleanup that accompanies removing its lease record.
     *
     * @param ikeDev the development-folder root
     * @param name   the working-set name
     */
    static void deleteAll(Path ikeDev, WorkingSetName name) {
        Path dir = directory(ikeDev, name);
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.forEach(SiblingBundles::deleteQuietly);
        } catch (IOException e) {
            // Leave what cannot be listed; the next pass retries.
        }
        deleteQuietly(dir);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // A leftover file is retried by the next write or cleanup.
        }
    }
}
