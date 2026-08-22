package network.ike.lease.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import network.ike.lease.core.GitRunner.GitResult;

/**
 * Aligns a root working set's refs to the holder's {@link RepoStamp}s —
 * the taker's half of IKE-Network/ike-issues#1069.
 *
 * <p>A taker whose repository exists but whose refs are stale sees the
 * synced tree as a phantom mega-diff: every file sync updated reads as a
 * local modification against the old HEAD, and {@code git pull} fights it
 * because the merge refuses to overwrite exactly the files sync already
 * updated. Alignment uses the materializer's tree-untouched primitive
 * instead: fetch, move the branch ref and HEAD, {@code reset --mixed} —
 * the tree is never written.
 *
 * <p>Target selection per repository: the stamp's head — except that when
 * the stamp is behind {@code origin}'s branch tip, the tip wins, and when
 * the stamp's commit is not fetchable at all (the holder never pushed
 * it), the tip stands in and the unpushed history is reported. Reported,
 * never chased and never discarded: a taker whose own branch carries
 * commits the target lacks is refused with the divergence named, because
 * moving the ref would orphan them — every decision point defaults to
 * refuse-and-report (IKE-Network/ike-issues#1057).
 *
 * <p>Siblings are never aligned: their branch is the name's {@code ꞉}
 * suffix, their base is the local parent, and the synced tree is
 * authoritative (IKE-Network/ike-issues#992).
 */
public final class RefAligner {

    /** What happened to one repository. */
    public enum Status {
        /** Branch and head already match the alignment target. */
        ALIGNED,
        /** The current branch's ref (and index) moved to the target. */
        MOVED,
        /** HEAD switched to the stamped branch, at the target. */
        SWITCHED_BRANCH,
        /** Offline finding: local refs differ from the stamp. */
        STALE,
        /** No stamp covers this repository; nothing to align to. */
        NO_STAMP,
        /** The synced tree is not present on disk; skipped. */
        NO_TREE,
        /** No git state; materialization's job, not alignment's. */
        NO_GIT,
        /** Local-only commits would be orphaned; refused, reported. */
        DIVERGED_REFUSED,
        /** The operation could not proceed for this repository. */
        REFUSED
    }

    /**
     * One repository's outcome.
     *
     * @param path   the repository path relative to {@code ~/ike-dev}
     * @param status what happened
     * @param detail the movement on success, or the reason and remedy on
     *               refusal; empty when the status says it all
     */
    public record Entry(String path, Status status, String detail) {

        /**
         * Renders the entry as one report line.
         *
         * @return {@code <status> <path>} with the detail appended when
         *         present
         */
        public String render() {
            String suffix = detail.isEmpty() ? "" : " — " + detail;
            return status + " " + path + suffix;
        }
    }

    /**
     * The outcome of aligning or checking one working set.
     *
     * @param workingSet the working set the operation ran against
     * @param entries    the per-repository outcomes, in the order acted on
     */
    public record AlignReport(WorkingSetName workingSet,
                              List<Entry> entries) {

        /**
         * Reports whether the operation completed without refusals.
         *
         * @return {@code true} when no entry was refused
         */
        public boolean ok() {
            return entries.stream().noneMatch(entry ->
                    entry.status() == Status.REFUSED
                            || entry.status() == Status.DIVERGED_REFUSED);
        }

        /**
         * Counts the entries with the given status.
         *
         * @param status the status to count
         * @return how many entries carry it
         */
        public long count(Status status) {
            return entries.stream().filter(
                    entry -> entry.status() == status).count();
        }
    }

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
    public RefAligner(Path ikeDev, GitRunner git, Consumer<String> progress) {
        this.ikeDev = ikeDev;
        this.git = git;
        this.progress = progress;
    }

    /**
     * Reads the working set's lease record and returns its stamps.
     *
     * @param ikeDev     the development-folder root
     * @param workingSet the working-set directory name
     * @return the record's stamps, empty when there is no record or it
     *         carries none
     */
    public static List<RepoStamp> recordedStamps(Path ikeDev,
                                                 String workingSet) {
        return LeaseRecord.read(ikeDev.resolve("leases")
                        .resolve(workingSet + ".lease"))
                .map(LeaseRecord::stamps)
                .orElse(List.of());
    }

    /**
     * Aligns every stamped repository: fetch, move the branch ref and
     * HEAD, {@code reset --mixed} — tree untouched.
     *
     * @param name   the working-set directory name
     * @param stamps the holder's stamps, normally
     *               {@link #recordedStamps read from the lease record}
     * @return the per-repository outcomes
     */
    public AlignReport align(WorkingSetName name, List<RepoStamp> stamps) {
        return run(name, stamps, true);
    }

    /**
     * Compares every stamped repository's refs against its stamp without
     * fetching or changing anything — the offline finding for
     * {@code verify}.
     *
     * @param name   the working-set directory name
     * @param stamps the holder's stamps
     * @return the per-repository findings
     */
    public AlignReport check(WorkingSetName name, List<RepoStamp> stamps) {
        return run(name, stamps, false);
    }

    private AlignReport run(WorkingSetName name, List<RepoStamp> stamps,
                            boolean act) {
        List<Entry> entries = new ArrayList<>();
        if (name.isSibling()) {
            entries.add(new Entry(name.value(), Status.REFUSED,
                    "alignment applies to roots; a sibling's branch is its "
                            + "name's suffix and its base is the local parent "
                            + "(ike-issues#992)"));
            return new AlignReport(name, entries);
        }
        if (stamps.isEmpty()) {
            entries.add(new Entry(name.value(), Status.NO_STAMP,
                    "the lease record carries no ref stamps; they appear "
                            + "once a stamping holder renews or releases"));
            return new AlignReport(name, entries);
        }
        Path root = ikeDev.resolve(name.value());
        for (RepoStamp stamp : stamps) {
            boolean isRoot = ".".equals(stamp.path());
            Path repoDir = isRoot ? root : root.resolve(stamp.path());
            String reportPath = isRoot ? name.value()
                    : name.value() + "/" + stamp.path();
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
            entries.add(act ? alignRepo(reportPath, repoDir, stamp)
                    : checkRepo(reportPath, repoDir, stamp));
        }
        return new AlignReport(name, entries);
    }

    private Entry checkRepo(String reportPath, Path repoDir, RepoStamp stamp) {
        GitResult branch = git.run(repoDir,
                List.of("symbolic-ref", "--short", "HEAD"));
        GitResult head = git.run(repoDir, List.of("rev-parse", "HEAD"));
        if (!branch.ok() || !head.ok()) {
            return new Entry(reportPath, Status.STALE,
                    "no current branch (detached or unborn HEAD); stamp is "
                            + stamp.branch() + "@" + shortHash(stamp.head()));
        }
        if (branch.stdoutTrimmed().equals(stamp.branch())
                && head.stdoutTrimmed().equals(stamp.head())) {
            return new Entry(reportPath, Status.ALIGNED, "");
        }
        return new Entry(reportPath, Status.STALE,
                "stamp " + stamp.branch() + "@" + shortHash(stamp.head())
                        + ", local " + branch.stdoutTrimmed() + "@"
                        + shortHash(head.stdoutTrimmed())
                        + " — repair aligns");
    }

    private Entry alignRepo(String reportPath, Path repoDir, RepoStamp stamp) {
        GitResult branchResult = git.run(repoDir,
                List.of("symbolic-ref", "--short", "HEAD"));
        if (!branchResult.ok()) {
            return new Entry(reportPath, Status.REFUSED,
                    "no current branch (detached HEAD?); check out a branch "
                            + "by hand and re-run");
        }
        String currentBranch = branchResult.stdoutTrimmed();
        GitResult headResult = git.run(repoDir, List.of("rev-parse", "HEAD"));
        if (!headResult.ok()) {
            return new Entry(reportPath, Status.REFUSED,
                    "HEAD does not resolve (unborn branch?); materialize or "
                            + "repair by hand");
        }
        String currentHead = headResult.stdoutTrimmed();

        progress.accept("aligning " + reportPath + " to " + stamp.branch()
                + "@" + shortHash(stamp.head()));
        GitResult fetch = git.run(repoDir, List.of("fetch", "--quiet",
                "origin", "+refs/heads/" + stamp.branch()
                        + ":refs/remotes/origin/" + stamp.branch()));
        String tip = fetch.ok()
                ? trimmedOrNull(git.run(repoDir, List.of("rev-parse",
                        "refs/remotes/origin/" + stamp.branch())))
                : null;
        if (!fetch.ok()) {
            // A stale remote-tracking ref from an earlier fetch must not
            // stand in for a fresh tip; without a fetch the only honest
            // target is the stamp itself.
            progress.accept("fetch failed for " + reportPath + ": "
                    + fetch.stderr().strip());
        }
        boolean stampPresent = git.run(repoDir, List.of("rev-parse",
                "--verify", "--quiet", stamp.head() + "^{commit}")).ok();

        String target;
        StringBuilder notes = new StringBuilder();
        if (stampPresent && tip != null) {
            if (!stamp.head().equals(tip)
                    && isAncestor(repoDir, stamp.head(), tip)) {
                target = tip;
                notes.append("; stamp was behind origin/")
                        .append(stamp.branch()).append(" — the tip wins");
            } else {
                target = stamp.head();
                if (!isAncestor(repoDir, stamp.head(), tip)) {
                    notes.append("; ").append(countRange(repoDir, tip,
                                    stamp.head()))
                            .append(" commit(s) not on origin/")
                            .append(stamp.branch())
                            .append(" (unpushed — push to publish history)");
                }
            }
        } else if (stampPresent) {
            target = stamp.head();
            notes.append("; origin/").append(stamp.branch())
                    .append(" unavailable (fetch failed)");
        } else if (tip != null) {
            target = tip;
            notes.append("; stamp ").append(shortHash(stamp.head()))
                    .append(" is not fetchable — its commits exist only on "
                            + "the stamping machine (tree content is synced; "
                            + "push from there to publish history)");
        } else {
            return new Entry(reportPath, Status.REFUSED,
                    "stamp " + shortHash(stamp.head()) + " is not present "
                            + "locally and origin/" + stamp.branch()
                            + " could not be fetched: "
                            + fetch.stderr().strip());
        }

        if (currentHead.equals(target)
                && currentBranch.equals(stamp.branch())) {
            return new Entry(reportPath, Status.ALIGNED,
                    stamp.branch() + "@" + shortHash(target)
                            + trimLeadingSeparator(notes));
        }
        if (!isAncestor(repoDir, currentHead, target)) {
            return new Entry(reportPath, Status.DIVERGED_REFUSED,
                    countRange(repoDir, target, currentHead)
                            + " local-only commit(s) on " + currentBranch
                            + " would be orphaned by moving to "
                            + shortHash(target) + "; reconcile by hand "
                            + "(git log " + shortHash(target)
                            + "..HEAD) and re-run");
        }

        boolean branchExisted = git.run(repoDir, List.of("rev-parse",
                "--verify", "--quiet",
                "refs/heads/" + stamp.branch())).ok();
        List<List<String>> commands = new ArrayList<>();
        commands.add(List.of("update-ref",
                "refs/heads/" + stamp.branch(), target));
        if (!branchExisted) {
            commands.add(List.of("config",
                    "branch." + stamp.branch() + ".remote", "origin"));
            commands.add(List.of("config",
                    "branch." + stamp.branch() + ".merge",
                    "refs/heads/" + stamp.branch()));
        }
        if (!currentBranch.equals(stamp.branch())) {
            commands.add(List.of("symbolic-ref", "HEAD",
                    "refs/heads/" + stamp.branch()));
        }
        commands.add(List.of("reset", "--quiet"));
        for (List<String> command : commands) {
            GitResult result = git.run(repoDir, command);
            if (!result.ok()) {
                return new Entry(reportPath, Status.REFUSED,
                        "git " + String.join(" ", command) + " failed: "
                                + result.stderr().strip());
            }
        }
        Status status = currentBranch.equals(stamp.branch())
                ? Status.MOVED : Status.SWITCHED_BRANCH;
        return new Entry(reportPath, status,
                stamp.branch() + ": " + shortHash(currentHead) + " → "
                        + shortHash(target) + notes);
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

    private static String trimmedOrNull(GitResult result) {
        return result.ok() ? result.stdoutTrimmed() : null;
    }

    private static String trimLeadingSeparator(StringBuilder notes) {
        String value = notes.toString();
        return value.startsWith("; ") ? " (" + value.substring(2) + ")"
                : value;
    }

    private static String shortHash(String hash) {
        return hash.length() > 12 ? hash.substring(0, 12) : hash;
    }
}
