package network.ike.lease.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import network.ike.lease.core.GitRunner.GitResult;
import network.ike.lease.core.RefAligner.AlignReport;
import network.ike.lease.core.RefAligner.Entry;
import network.ike.lease.core.RefAligner.Status;

/**
 * Aligns a sibling working set's refs to the history the last holder left
 * in its bundles — the taker's half of IKE-Network/ike-issues#1216.
 *
 * <p>A sibling arriving from another machine has the other machine's
 * commits in its synced tree but not in its {@code .git}, so the tree
 * reads as a mass of uncommitted changes. For each bundle the aligner
 * fetches the bundle's head into a side ref and, when the local branch is
 * an ancestor of it, moves the branch there and runs {@code reset --mixed}
 * — the tree is never written, exactly as the root aligner works
 * ({@link RefAligner}).
 *
 * <p>Every decision point refuses and reports: local commits the bundle
 * lacks block the move rather than being orphaned; a bundle needing
 * commits this repository does not have, even after fetching from the
 * local parent, is reported with nothing moved. A tree that still differs
 * from the bundle's head after the move is not an error: it is
 * uncommitted work the sync layer carried, or sync still arriving, and it
 * shows as the ordinary status delta.
 */
public final class SiblingAligner {

    private static final String SIDE_REF_PREFIX = "refs/ike-bundle/";

    private final Path ikeDev;
    private final GitRunner git;
    private final Consumer<String> progress;

    /**
     * Creates an aligner.
     *
     * @param ikeDev   the development-folder root, normally
     *                 {@code ~/ike-dev}
     * @param git      the git runner the host contributes
     * @param progress sink for one-line progress narration
     */
    public SiblingAligner(Path ikeDev, GitRunner git, Consumer<String> progress) {
        this.ikeDev = ikeDev;
        this.git = git;
        this.progress = progress;
    }

    /**
     * Reports whether the sibling has any bundles to align to.
     *
     * @param ikeDev the development-folder root
     * @param name   the working-set name
     * @return {@code true} when at least one bundle exists
     */
    public static boolean hasBundles(Path ikeDev, WorkingSetName name) {
        return name.isSibling() && !SiblingBundles.list(ikeDev, name).isEmpty();
    }

    /**
     * Aligns every bundled member: fetch the bundle's head, move the
     * branch, {@code reset --mixed} — tree untouched.
     *
     * @param name the sibling's working-set name
     * @return the per-repository outcomes
     */
    public AlignReport align(WorkingSetName name) {
        return run(name, true);
    }

    /**
     * Compares every bundled member's branch head against its bundle's
     * head without changing anything — the offline finding for
     * {@code verify}.
     *
     * @param name the sibling's working-set name
     * @return the per-repository findings
     */
    public AlignReport check(WorkingSetName name) {
        return run(name, false);
    }

    private AlignReport run(WorkingSetName name, boolean act) {
        List<Entry> entries = new ArrayList<>();
        if (!name.isSibling()) {
            entries.add(new Entry(name.value(), Status.REFUSED,
                    "bundle alignment applies to siblings; a root aligns "
                            + "to its stamps"));
            return new AlignReport(name, entries);
        }
        List<Path> bundles = SiblingBundles.list(ikeDev, name);
        if (bundles.isEmpty()) {
            entries.add(new Entry(name.value(), Status.NO_STAMP,
                    "no bundles yet; they appear once a holder renews after "
                            + "committing, or returns the lease"));
            return new AlignReport(name, entries);
        }
        Path root = ikeDev.resolve(name.value());
        for (Path bundle : bundles) {
            String member = SiblingBundles.memberPath(
                    bundle.getFileName().toString());
            boolean isRoot = ".".equals(member);
            Path repoDir = isRoot ? root : root.resolve(member);
            String reportPath = isRoot ? name.value()
                    : name.value() + "/" + member;
            if (!Files.isDirectory(repoDir)) {
                entries.add(new Entry(reportPath, Status.NO_TREE,
                        "synced tree not present yet"));
                continue;
            }
            if (!WorkingSetRepos.hasGit(repoDir)) {
                entries.add(new Entry(reportPath, Status.NO_GIT,
                        "materialize creates git state; alignment only "
                                + "moves refs"));
                continue;
            }
            entries.add(alignRepo(reportPath, repoDir, bundle, act));
        }
        return new AlignReport(name, entries);
    }

    private Entry alignRepo(String reportPath, Path repoDir, Path bundle,
                            boolean act) {
        String bundlePath = bundle.toAbsolutePath().toString();
        GitResult heads = git.run(repoDir,
                List.of("bundle", "list-heads", bundlePath));
        BundleHead head = heads.ok() ? BundleHead.parse(heads.stdout()) : null;
        if (head == null) {
            return new Entry(reportPath, Status.REFUSED,
                    "bundle " + bundle.getFileName() + " is unreadable"
                            + (heads.ok() ? " (no branch head)"
                                    : ": " + heads.stderr().strip()));
        }
        GitResult branchResult = git.run(repoDir,
                List.of("symbolic-ref", "--short", "HEAD"));
        GitResult headResult = git.run(repoDir, List.of("rev-parse", "HEAD"));
        if (!branchResult.ok() || !headResult.ok()) {
            return new Entry(reportPath, act ? Status.REFUSED : Status.STALE,
                    "no current branch (detached or unborn HEAD); the bundle "
                            + "carries " + head.branch() + "@"
                            + shortHash(head.commit()));
        }
        String currentBranch = branchResult.stdoutTrimmed();
        String currentHead = headResult.stdoutTrimmed();
        if (!currentBranch.equals(head.branch())) {
            return new Entry(reportPath, act ? Status.REFUSED : Status.STALE,
                    "on " + currentBranch + ", but the bundle carries "
                            + head.branch() + "; switch branches by hand");
        }
        if (currentHead.equals(head.commit())) {
            return new Entry(reportPath, Status.ALIGNED, "");
        }
        boolean present = commitPresent(repoDir, head.commit());
        if (!act) {
            if (present && isAncestor(repoDir, head.commit(), currentHead)) {
                return new Entry(reportPath, Status.ALIGNED,
                        "local is ahead of the bundle");
            }
            return new Entry(reportPath, Status.STALE,
                    "bundle " + head.branch() + "@" + shortHash(head.commit())
                            + ", local @" + shortHash(currentHead)
                            + " — repair aligns");
        }

        progress.accept("aligning " + reportPath + " to "
                + head.branch() + "@" + shortHash(head.commit()));
        if (!present) {
            String fetched = fetchBundle(repoDir, bundlePath, head);
            if (fetched != null) {
                return new Entry(reportPath, Status.REFUSED, fetched);
            }
        }
        if (isAncestor(repoDir, head.commit(), currentHead)) {
            deleteSideRef(repoDir, head);
            return new Entry(reportPath, Status.ALIGNED,
                    "local is ahead of the bundle");
        }
        if (!isAncestor(repoDir, currentHead, head.commit())) {
            String count = countRange(repoDir, head.commit(), currentHead);
            deleteSideRef(repoDir, head);
            return new Entry(reportPath, Status.DIVERGED_REFUSED,
                    count + " local-only commit(s) on " + currentBranch
                            + " would be orphaned by moving to "
                            + shortHash(head.commit()) + "; reconcile by hand "
                            + "(git log " + shortHash(head.commit())
                            + "..HEAD) and re-run");
        }
        List<List<String>> commands = List.of(
                List.of("update-ref", "-m", "ike-lease: align to sibling bundle",
                        "refs/heads/" + head.branch(), head.commit(),
                        currentHead),
                List.of("reset", "--quiet"));
        for (List<String> command : commands) {
            GitResult result = git.run(repoDir, command);
            if (!result.ok()) {
                deleteSideRef(repoDir, head);
                return new Entry(reportPath, Status.REFUSED,
                        "git " + String.join(" ", command) + " failed: "
                                + result.stderr().strip());
            }
        }
        deleteSideRef(repoDir, head);
        GitResult status = git.run(repoDir,
                List.of("status", "--porcelain"));
        long changes = status.ok() ? status.stdout().lines().count() : 0;
        String delta = changes == 0 ? ""
                : "; " + changes + " path(s) differ from it — uncommitted "
                        + "work the sync layer carried, or sync still arriving";
        return new Entry(reportPath, Status.MOVED,
                head.branch() + ": " + shortHash(currentHead) + " → "
                        + shortHash(head.commit()) + delta);
    }

    /**
     * Fetches the bundle's head into a side ref, first fetching the local
     * parent when the bundle needs commits this repository lacks.
     *
     * @return {@code null} on success, else the refusal reason
     */
    private String fetchBundle(Path repoDir, String bundlePath,
                               BundleHead head) {
        GitResult verify = git.run(repoDir,
                List.of("bundle", "verify", "--quiet", bundlePath));
        if (!verify.ok()) {
            progress.accept("bundle needs commits not here yet; fetching the "
                    + "local parent");
            git.run(repoDir, List.of("fetch", "--quiet", "origin"));
            verify = git.run(repoDir,
                    List.of("bundle", "verify", "--quiet", bundlePath));
            if (!verify.ok()) {
                return "the bundle builds on commits this repository does not "
                        + "have, even after fetching the local parent; update "
                        + "the parent working set (ws:pull) and re-run: "
                        + verify.stderr().strip();
            }
        }
        GitResult fetch = git.run(repoDir, List.of("fetch", "--quiet",
                bundlePath, "+refs/heads/" + head.branch() + ":"
                        + SIDE_REF_PREFIX + head.branch()));
        if (!fetch.ok() || !commitPresent(repoDir, head.commit())) {
            return "could not fetch from the bundle: "
                    + fetch.stderr().strip();
        }
        return null;
    }

    private void deleteSideRef(Path repoDir, BundleHead head) {
        git.run(repoDir, List.of("update-ref", "-d",
                SIDE_REF_PREFIX + head.branch()));
    }

    private boolean commitPresent(Path repoDir, String commit) {
        return git.run(repoDir, List.of("cat-file", "-e",
                commit + "^{commit}")).ok();
    }

    private boolean isAncestor(Path repoDir, String ancestor,
                               String descendant) {
        return git.run(repoDir, List.of("merge-base", "--is-ancestor",
                ancestor, descendant)).ok();
    }

    private String countRange(Path repoDir, String excluded,
                              String included) {
        GitResult count = git.run(repoDir, List.of("rev-list", "--count",
                excluded + ".." + included));
        return count.ok() ? count.stdoutTrimmed() : "?";
    }

    private static String shortHash(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) : hash;
    }

    /**
     * The branch head a bundle carries.
     *
     * @param branch the branch name, without {@code refs/heads/}
     * @param commit the commit the branch points at
     */
    record BundleHead(String branch, String commit) {

        /**
         * Reads the first branch head from {@code git bundle list-heads}
         * output.
         *
         * @param listHeads the command's standard output
         * @return the head, or {@code null} when no branch head is listed
         */
        static BundleHead parse(String listHeads) {
            for (String line : listHeads.lines().toList()) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && parts[1].startsWith("refs/heads/")) {
                    return new BundleHead(
                            parts[1].substring("refs/heads/".length()),
                            parts[0]);
                }
            }
            return null;
        }
    }
}
