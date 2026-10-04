package network.ike.lease.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Stream;

import network.ike.lease.core.RefAligner.AlignReport;
import network.ike.lease.core.RefAligner.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A sibling's committed history travelling between two machines through
 * bundles in the synced lease folder (IKE-Network/ike-issues#1216), with
 * real git repositories throughout.
 *
 * <p>Each machine has its own home, development folder, parent working
 * set and sibling. A shared bare repository stands in for GitHub, so the
 * two parents can hold identical {@code main} commits. "Sync" copies
 * every file except {@code .git} contents from one machine's development
 * folder to the other's — exactly what the sync layer carries.
 */
class SiblingHistoryTest {

    private static final String PARENT = "parent";
    private static final String SIBLING = "parent꞉feat";
    private static final String BRANCH = "feature/feat";

    private final GitRunner git = new ProcessGitRunner();

    @TempDir
    Path tempDir;

    private Machine a;
    private Machine b;
    private Path github;

    /** One machine: its home, development folder, and protocol. */
    private record Machine(String id, Path home, Path ikeDev,
                           LeaseProtocol protocol) {

        Path parent() {
            return ikeDev.resolve(PARENT);
        }

        Path sibling() {
            return ikeDev.resolve(SIBLING);
        }
    }

    @BeforeEach
    void twoMachinesSharingOneSibling() throws IOException {
        github = tempDir.resolve("github.git");
        Files.createDirectories(github);
        git(github, "init", "--bare", "--initial-branch=main");

        Path seed = tempDir.resolve("seed");
        Files.createDirectories(seed);
        git(seed, "init", "--initial-branch=main");
        configure(seed);
        write(seed.resolve("readme.txt"), "parent\n");
        git(seed, "add", ".");
        git(seed, "commit", "-m", "parent's first commit");
        git(seed, "remote", "add", "origin", github.toString());
        git(seed, "push", "--quiet", "origin", "main");

        a = machine("Machine-A-0001");
        b = machine("Machine-B-0002");

        // The sibling starts on A and arrives on B as the materializer
        // would leave it: same tree, its own git state, origin the local
        // parent, on the feature branch at the shared fork point.
        startSibling(a);
        startSibling(b);
    }

    @Test
    void commitsMadeOnOneMachineArriveOnTheOther() throws IOException {
        take(a);
        commit(a.sibling(), "a.txt", "first change on A\n", "first change on A");
        commit(a.sibling(), "b.txt", "second change on A\n", "second change on A");
        String aHead = head(a.sibling());
        assertEquals(0, a.protocol().returnLease(SIBLING).exitCode());

        assertTrue(Files.isRegularFile(bundleFile(a)),
                "returning a sibling writes its history bundle");
        LeaseRecord returned = LeaseRecord.read(a.ikeDev()
                .resolve("leases/" + SIBLING + ".lease")).orElseThrow();
        assertEquals(RecordState.RETURNED, returned.state());
        assertEquals(List.of(new RepoStamp(".", BRANCH, aHead)),
                returned.stamps());

        sync(a, b);
        assertFalse(status(b.sibling()).isEmpty(),
                "before alignment B reads A's commits as uncommitted changes");

        AlignReport report = aligner(b).align(new WorkingSetName(SIBLING));

        assertEquals(Status.MOVED, report.entries().getFirst().status(),
                report.entries().toString());
        assertEquals(aHead, head(b.sibling()));
        assertEquals("", status(b.sibling()),
                "after alignment B's tree is exactly A's last commit");
        assertEquals(BRANCH, git(b.sibling(), "symbolic-ref", "--short",
                "HEAD"));
        assertFalse(git.run(b.sibling(), List.of("rev-parse", "--verify",
                        "--quiet", "refs/ike-bundle/" + BRANCH)).ok(),
                "the side ref is cleaned up");
    }

    @Test
    void uncommittedWorkCarriedBySyncStaysAsTheStatusDelta()
            throws IOException {
        take(a);
        commit(a.sibling(), "a.txt", "committed on A\n", "committed on A");
        write(a.sibling().resolve("a.txt"), "edited but not committed\n");
        assertEquals(0, a.protocol().returnLease(SIBLING).exitCode());
        sync(a, b);

        AlignReport report = aligner(b).align(new WorkingSetName(SIBLING));

        assertEquals(Status.MOVED, report.entries().getFirst().status());
        assertTrue(report.entries().getFirst().detail().contains(
                "1 path(s) differ"), report.entries().toString());
        assertEquals(" M a.txt", status(b.sibling()).stripTrailing(),
                "the carried edit shows as a modification against A's head");
        assertEquals("edited but not committed\n",
                Files.readString(b.sibling().resolve("a.txt")),
                "the tree is never written");
    }

    @Test
    void aMachineAlreadyAheadOfTheBundleKeepsItsCommits()
            throws IOException {
        take(a);
        commit(a.sibling(), "a.txt", "on A\n", "change made on A");
        assertEquals(0, a.protocol().returnLease(SIBLING).exitCode());
        sync(a, b);
        aligner(b).align(new WorkingSetName(SIBLING));
        commit(b.sibling(), "b.txt", "on B\n", "change made on B after");
        String bHead = head(b.sibling());

        AlignReport report = aligner(b).align(new WorkingSetName(SIBLING));

        assertEquals(Status.ALIGNED, report.entries().getFirst().status());
        assertEquals(bHead, head(b.sibling()), "nothing moved backwards");
    }

    @Test
    void localOnlyCommitsBlockTheMoveInsteadOfBeingOrphaned()
            throws IOException {
        take(a);
        commit(a.sibling(), "a.txt", "on A\n", "change made on A");
        assertEquals(0, a.protocol().returnLease(SIBLING).exitCode());
        commit(b.sibling(), "b.txt", "on B\n", "diverging change on B");
        String bHead = head(b.sibling());
        sync(a, b);

        AlignReport report = aligner(b).align(new WorkingSetName(SIBLING));

        assertEquals(Status.DIVERGED_REFUSED,
                report.entries().getFirst().status());
        assertFalse(report.ok());
        assertEquals(bHead, head(b.sibling()), "the refusal moved nothing");
    }

    @Test
    void aBundleBuiltOnNewerParentCommitsFetchesTheLocalParentFirst()
            throws IOException {
        // GitHub gains a commit; both parents pull it, but only A's
        // sibling picks it up before committing on top of it.
        Path seed = tempDir.resolve("seed");
        commit(seed, "readme.txt", "parent, updated\n", "parent moves ahead");
        git(seed, "push", "--quiet", "origin", "main");
        git(a.parent(), "pull", "--quiet", "--ff-only", "origin", "main");
        git(b.parent(), "pull", "--quiet", "--ff-only", "origin", "main");
        git(a.sibling(), "fetch", "--quiet", "origin");
        git(a.sibling(), "update-ref", "refs/heads/main", "origin/main");
        git(a.sibling(), "rebase", "--quiet", "main");

        take(a);
        commit(a.sibling(), "a.txt", "on A\n", "change on the new base");
        String aHead = head(a.sibling());
        assertEquals(0, a.protocol().returnLease(SIBLING).exitCode());
        sync(a, b);

        AlignReport report = aligner(b).align(new WorkingSetName(SIBLING));

        assertEquals(Status.MOVED, report.entries().getFirst().status(),
                report.entries().toString());
        assertEquals(aHead, head(b.sibling()));
    }

    @Test
    void renewRewritesBundlesOnlyWhenHeadsMoved() throws IOException {
        take(a);
        commit(a.sibling(), "a.txt", "on A\n", "first change on A");
        assertEquals(0, a.protocol().renew(SIBLING).exitCode());
        Path bundle = bundleFile(a);
        assertTrue(Files.isRegularFile(bundle),
                "a renew after committing writes the bundle — headless work "
                        + "that never returns still travels");
        FileTime written = FileTime.fromMillis(0);
        Files.setLastModifiedTime(bundle, written);

        assertEquals(0, a.protocol().renew(SIBLING).exitCode());
        assertEquals(written, Files.getLastModifiedTime(bundle),
                "nothing committed since: the renew leaves the bundle alone");

        commit(a.sibling(), "b.txt", "more\n", "second change on A");
        assertEquals(0, a.protocol().renew(SIBLING).exitCode());
        assertFalse(written.equals(Files.getLastModifiedTime(bundle)),
                "a new commit refreshes it");
    }

    @Test
    void aTakerThatHasNotAlignedNeverOverwritesTheHistory()
            throws IOException {
        take(a);
        commit(a.sibling(), "a.txt", "on A\n", "change made on A");
        String aHead = head(a.sibling());
        assertEquals(0, a.protocol().returnLease(SIBLING).exitCode());
        sync(a, b);

        // B takes silently, as Claude's fence does, and renews before
        // anything aligned it: its own branch is still at the fork point.
        take(b);
        LeaseProtocol.Outcome renewed = b.protocol().renew(SIBLING);

        assertEquals(0, renewed.exitCode(), "the lease write never fails "
                + "on bundle trouble");
        assertTrue(renewed.stderr().contains("kept the existing bundle"),
                renewed.stderr());
        assertEquals(aHead, bundleHead(b),
                "A's history survives B's stale renew");

        aligner(b).align(new WorkingSetName(SIBLING));
        commit(b.sibling(), "b.txt", "on B\n", "change made on B");
        assertEquals(0, b.protocol().renew(SIBLING).exitCode());

        assertEquals(head(b.sibling()), bundleHead(b),
                "once B contains A's head, its bundle moves forward");
    }

    @Test
    void aMemberWithNothingBeyondMainHasNoBundle() throws IOException {
        take(a);
        assertEquals(0, a.protocol().returnLease(SIBLING).exitCode());

        assertFalse(Files.exists(bundleFile(a)),
                "a feature branch with no commits of its own carries nothing");
        assertEquals(Status.NO_STAMP, aligner(b)
                .check(new WorkingSetName(SIBLING)).entries().getFirst()
                .status());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Machine machine(String id) throws IOException {
        Path home = tempDir.resolve(id);
        Path ikeDev = home.resolve("ike-dev");
        Files.createDirectories(ikeDev.resolve("leases"));
        Files.writeString(home.resolve(".ike-machine-id"), id + "\n",
                StandardCharsets.UTF_8);
        Path parent = ikeDev.resolve(PARENT);
        git(ikeDev, "clone", "--quiet", github.toString(), PARENT);
        configure(parent);
        return new Machine(id, home, ikeDev, new LeaseProtocol(ikeDev, home,
                ikeDev, "PT10M", 0, git));
    }

    private void startSibling(Machine machine) throws IOException {
        git(machine.ikeDev(), "clone", "--quiet",
                machine.parent().toString(), SIBLING);
        configure(machine.sibling());
        git(machine.sibling(), "switch", "--quiet", "-c", BRANCH);
    }

    private void take(Machine machine) {
        assertEquals(0, machine.protocol().take(SIBLING, true, false)
                .exitCode());
    }

    private SiblingAligner aligner(Machine machine) {
        return new SiblingAligner(machine.ikeDev(), git, line -> { });
    }

    private Path bundleFile(Machine machine) {
        return SiblingBundles.directory(machine.ikeDev(),
                new WorkingSetName(SIBLING)).resolve(SiblingBundles.ROOT_FILE);
    }

    private String bundleHead(Machine machine) {
        SiblingAligner.BundleHead head = SiblingAligner.BundleHead.parse(
                git(machine.sibling(), "bundle", "list-heads",
                        bundleFile(machine).toString()));
        return head == null ? "" : head.commit();
    }

    /**
     * Carries one machine's synced files to the other: the sibling's tree
     * and the lease folder, never anything under a {@code .git}.
     */
    private void sync(Machine from, Machine to) throws IOException {
        for (String top : List.of(SIBLING, "leases")) {
            Path source = from.ikeDev().resolve(top);
            try (Stream<Path> walk = Files.walk(source)) {
                for (Path file : walk.filter(Files::isRegularFile).toList()) {
                    Path relative = source.relativize(file);
                    if (relative.toString().equals(".git")
                            || relative.startsWith(".git")) {
                        continue;
                    }
                    Path target = to.ikeDev().resolve(top).resolve(relative);
                    Files.createDirectories(target.getParent());
                    Files.copy(file, target,
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void configure(Path repo) {
        git(repo, "config", "user.email", "test@ike.network");
        git(repo, "config", "user.name", "Sibling History Test");
        git(repo, "config", "commit.gpgsign", "false");
        git(repo, "config", "core.hooksPath", ".git/hooks");
    }

    private void commit(Path repo, String file, String content,
                        String message) throws IOException {
        write(repo.resolve(file), content);
        git(repo, "add", file);
        git(repo, "commit", "--quiet", "-m", message);
    }

    private String head(Path repo) {
        return git(repo, "rev-parse", "HEAD");
    }

    private String status(Path repo) {
        GitRunner.GitResult result = git.run(repo,
                List.of("status", "--porcelain"));
        assertTrue(result.ok(), result.stderr());
        return result.stdout();
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private String git(Path directory, String... args) {
        GitRunner.GitResult result = git.run(directory, List.of(args));
        assertTrue(result.ok(), "git " + String.join(" ", args) + " failed: "
                + result.stderr());
        return result.stdoutTrimmed();
    }
}
