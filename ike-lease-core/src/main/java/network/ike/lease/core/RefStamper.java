package network.ike.lease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Collects {@link RepoStamp}s for a root working set by reading git's
 * own files — {@code HEAD}, loose refs, {@code packed-refs} — never by
 * spawning a process (IKE-Network/ike-issues#1069).
 *
 * <p>File reads are what lets stamping ride every protocol write,
 * including the Claude fence's hot path, without a latency cost: a
 * fifteen-member workspace stamps in well under a millisecond. The
 * trade-off is accepted narrowness — a repository in a state these reads
 * cannot interpret (detached HEAD, unborn branch, an exotic ref layout)
 * is simply skipped, because stamps are best-effort metadata: a missing
 * stamp costs an alignment opportunity, a wrong one would mis-aim it.
 *
 * <p>Siblings are never stamped: their branch is the name's {@code ꞉}
 * suffix, their base is the local parent, and the synced tree is
 * authoritative (IKE-Network/ike-issues#992).
 */
final class RefStamper {

    private static final String REF_PREFIX = "ref: refs/heads/";

    private RefStamper() { }

    /**
     * Collects the current ref position of every repository in a root
     * working set.
     *
     * @param ikeDev     the development-folder root
     * @param workingSet the working-set directory name
     * @return one stamp per readable repository, {@code .} first; empty
     *         for siblings, absent trees, and anything unreadable
     */
    static List<RepoStamp> collect(Path ikeDev, String workingSet) {
        WorkingSetName name;
        try {
            name = new WorkingSetName(workingSet);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        if (name.isSibling()) {
            return List.of();
        }
        Path root = ikeDev.resolve(workingSet);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<RepoStamp> stamps = new ArrayList<>();
        for (String member : WorkingSetRepos.discover(root, line -> { })) {
            Path repoDir = member.isEmpty() ? root : root.resolve(member);
            String stampPath = member.isEmpty() ? "." : member;
            stamp(repoDir, stampPath).ifPresent(stamps::add);
        }
        return stamps;
    }

    private static Optional<RepoStamp> stamp(Path repoDir, String stampPath) {
        try {
            Path gitDir = resolveGitDir(repoDir);
            if (gitDir == null) {
                return Optional.empty();
            }
            String head = Files.readString(gitDir.resolve("HEAD"),
                    StandardCharsets.UTF_8).trim();
            if (!head.startsWith(REF_PREFIX)) {
                return Optional.empty();    // detached HEAD: transient, skip
            }
            String branch = head.substring(REF_PREFIX.length());
            if (branch.isEmpty() || branch.contains(" ")) {
                return Optional.empty();
            }
            return resolveBranch(gitDir, branch)
                    .map(sha -> new RepoStamp(stampPath, branch, sha));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolves the actual git directory: the {@code .git} directory
     * itself, or the target of a gitfile ({@code gitdir: <path>},
     * relative paths resolved against the repository).
     */
    private static Path resolveGitDir(Path repoDir) throws IOException {
        Path git = repoDir.resolve(".git");
        if (Files.isRegularFile(git)) {
            String content = Files.readString(git,
                    StandardCharsets.UTF_8).trim();
            if (!content.startsWith("gitdir:")) {
                return null;
            }
            Path target = Path.of(
                    content.substring("gitdir:".length()).trim());
            return (target.isAbsolute() ? target : repoDir.resolve(target))
                    .normalize();
        }
        return Files.isRegularFile(git.resolve("HEAD")) ? git : null;
    }

    private static Optional<String> resolveBranch(Path gitDir, String branch)
            throws IOException {
        Path loose = gitDir.resolve("refs/heads").resolve(branch);
        if (Files.isRegularFile(loose)) {
            String sha = Files.readString(loose, StandardCharsets.UTF_8)
                    .trim();
            return isCommitHash(sha) ? Optional.of(sha) : Optional.empty();
        }
        Path packed = gitDir.resolve("packed-refs");
        if (!Files.isRegularFile(packed)) {
            return Optional.empty();        // unborn branch: skip
        }
        String ref = "refs/heads/" + branch;
        for (String line : Files.readAllLines(packed,
                StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.startsWith("^")) {
                continue;
            }
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 2 && parts[1].equals(ref)
                    && isCommitHash(parts[0])) {
                return Optional.of(parts[0]);
            }
        }
        return Optional.empty();
    }

    private static boolean isCommitHash(String value) {
        if (value.length() != 40 && value.length() != 64) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
