package network.ike.lease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import network.ike.lease.core.GitRunner.GitResult;
import network.ike.lease.core.RefAligner.AlignReport;
import network.ike.lease.core.RefAligner.Status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ref alignment against real repositories (IKE-Network/ike-issues#1069):
 * a taker catches up to the holder's stamps with the tree untouched, the
 * tip wins over a stale stamp, unpushed history is reported rather than
 * chased, and local-only commits refuse the move instead of being
 * orphaned.
 */
class RefAlignerTest {

    private static final String FABRICATED_SHA =
            "cafecafecafecafecafecafecafecafecafecafe";

    private final GitRunner git = new ProcessGitRunner();

    @TempDir
    Path sandbox;

    @Test
    void takerBehindMovesToTheStampWithTheTreeUntouched()
            throws IOException {
        Fixture fixture = Fixture.create(this);
        Path taker = fixture.takerClone("my-root");
        String c1 = revParse(taker, "HEAD");
        String c2 = fixture.advanceOrigin("second");
        Map<String, String> before = snapshot(taker);

        AlignReport report = fixture.aligner().align(
                new WorkingSetName("my-root"),
                List.of(new RepoStamp(".", "main", c2)));

        assertEquals(List.of(Status.MOVED), statuses(report));
        assertEquals(c2, revParse(taker, "HEAD"));
        assertEquals("main", currentBranch(taker));
        assertEquals(before, snapshot(taker), "the working tree changed");
        assertTrue(porcelainStatus(taker).contains("tracked.txt"),
                "the synced tree's drift against the new HEAD must appear "
                        + "as the status delta, exactly like materialize");
        String detail = report.entries().getFirst().detail();
        assertTrue(detail.contains(c1.substring(0, 12))
                        && detail.contains(c2.substring(0, 12)),
                "the movement names both ends: " + detail);
    }

    @Test
    void staleStampLosesToTheOriginTip() throws IOException {
        Fixture fixture = Fixture.create(this);
        String c1 = fixture.originHead();
        Path taker = fixture.takerClone("my-root");
        String c2 = fixture.advanceOrigin("second");

        AlignReport report = fixture.aligner().align(
                new WorkingSetName("my-root"),
                List.of(new RepoStamp(".", "main", c1)));

        assertEquals(List.of(Status.MOVED), statuses(report));
        assertEquals(c2, revParse(taker, "HEAD"),
                "when the stamp is behind origin's tip, the tip wins");
        assertTrue(report.entries().getFirst().detail()
                        .contains("the tip wins"),
                report.entries().toString());
    }

    @Test
    void unfetchableStampFallsBackToTheTipAndReportsIt()
            throws IOException {
        Fixture fixture = Fixture.create(this);
        Path taker = fixture.takerClone("my-root");
        String c2 = fixture.advanceOrigin("second");

        AlignReport report = fixture.aligner().align(
                new WorkingSetName("my-root"),
                List.of(new RepoStamp(".", "main", FABRICATED_SHA)));

        assertEquals(List.of(Status.MOVED), statuses(report));
        assertEquals(c2, revParse(taker, "HEAD"));
        assertTrue(report.entries().getFirst().detail()
                        .contains("exist only on the stamping machine"),
                "unpushed history is reported, never chased: "
                        + report.entries());
    }

    @Test
    void stampAheadOfOriginAlignsAndReportsTheUnpushedCount()
            throws IOException {
        Fixture fixture = Fixture.create(this);
        // The holder committed c3 and never pushed it to main — but it
        // reached origin on another ref, so the object is fetchable and
        // only the BRANCH is behind. The taker clones after that push,
        // so c3 is present locally.
        String c3 = fixture.pushSideBranch("extra", "holder work");
        Path taker = fixture.takerClone("my-root");

        AlignReport report = fixture.aligner().align(
                new WorkingSetName("my-root"),
                List.of(new RepoStamp(".", "main", c3)));

        assertEquals(List.of(Status.MOVED), statuses(report));
        assertEquals(c3, revParse(taker, "HEAD"));
        assertTrue(report.entries().getFirst().detail()
                        .contains("not on origin/main"),
                report.entries().toString());
    }

    @Test
    void localOnlyCommitsRefuseTheMoveAndStayPut() throws IOException {
        Fixture fixture = Fixture.create(this);
        Path taker = fixture.takerClone("my-root");
        String c2 = fixture.advanceOrigin("second");
        Files.writeString(taker.resolve("local.txt"), "local work\n",
                StandardCharsets.UTF_8);
        run(taker, "add", ".");
        run(taker, "commit", "-m", "local-only");
        String localHead = revParse(taker, "HEAD");

        AlignReport report = fixture.aligner().align(
                new WorkingSetName("my-root"),
                List.of(new RepoStamp(".", "main", c2)));

        assertEquals(List.of(Status.DIVERGED_REFUSED), statuses(report));
        assertEquals(localHead, revParse(taker, "HEAD"),
                "a refused move must leave the refs exactly as they were");
        assertTrue(report.entries().getFirst().detail()
                        .contains("would be orphaned"),
                report.entries().toString());
    }

    @Test
    void stampedBranchSwitchCreatesAndConfiguresTheLocalBranch()
            throws IOException {
        Fixture fixture = Fixture.create(this);
        String cWork = fixture.pushSideBranch("work", "branch work");
        Path taker = fixture.takerClone("my-root");

        AlignReport report = fixture.aligner().align(
                new WorkingSetName("my-root"),
                List.of(new RepoStamp(".", "work", cWork)));

        assertEquals(List.of(Status.SWITCHED_BRANCH), statuses(report));
        assertEquals("work", currentBranch(taker));
        assertEquals(cWork, revParse(taker, "HEAD"));
        assertEquals("origin", config(taker, "branch.work.remote"),
                "a branch this alignment created tracks origin");
    }

    @Test
    void alignedRepositoryReportsAlignedAndTouchesNothing()
            throws IOException {
        Fixture fixture = Fixture.create(this);
        Path taker = fixture.takerClone("my-root");
        String c1 = revParse(taker, "HEAD");

        AlignReport report = fixture.aligner().align(
                new WorkingSetName("my-root"),
                List.of(new RepoStamp(".", "main", c1)));

        assertEquals(List.of(Status.ALIGNED), statuses(report));
        assertEquals(c1, revParse(taker, "HEAD"));
    }

    @Test
    void checkIsOfflineAndNamesTheDrift() throws IOException {
        Fixture fixture = Fixture.create(this);
        Path taker = fixture.takerClone("my-root");
        String c1 = revParse(taker, "HEAD");

        AlignReport aligned = fixture.aligner().check(
                new WorkingSetName("my-root"),
                List.of(new RepoStamp(".", "main", c1)));
        AlignReport stale = fixture.aligner().check(
                new WorkingSetName("my-root"),
                List.of(new RepoStamp(".", "main", FABRICATED_SHA)));

        assertEquals(List.of(Status.ALIGNED), statuses(aligned));
        assertEquals(List.of(Status.STALE), statuses(stale));
        assertEquals(c1, revParse(taker, "HEAD"),
                "check never changes anything");
        assertTrue(stale.entries().getFirst().detail()
                        .contains("repair aligns"),
                stale.entries().toString());
    }

    @Test
    void siblingsMissingTreesAndHusksAreRefusedOrSkipped()
            throws IOException {
        Fixture fixture = Fixture.create(this);
        fixture.takerClone("my-root");

        AlignReport sibling = fixture.aligner().align(
                new WorkingSetName("my-root꞉feature"),
                List.of(new RepoStamp(".", "main", FABRICATED_SHA)));
        assertEquals(List.of(Status.REFUSED), statuses(sibling));

        AlignReport unstamped = fixture.aligner().align(
                new WorkingSetName("my-root"), List.of());
        assertEquals(List.of(Status.NO_STAMP), statuses(unstamped));

        Files.createDirectories(
                fixture.ikeDev.resolve("husk-ws").resolve(".git"));
        AlignReport husk = fixture.aligner().align(
                new WorkingSetName("husk-ws"),
                List.of(new RepoStamp(".", "main", FABRICATED_SHA),
                        new RepoStamp("ghost", "main", FABRICATED_SHA)));
        assertEquals(List.of(Status.NO_GIT, Status.NO_TREE),
                statuses(husk));
    }

    // ------------------------------------------------------------------
    // Fixture — a bare origin, a seed workdir that stands in for the
    // holder, and taker clones inside a sandboxed development folder
    // ------------------------------------------------------------------

    private record Fixture(RefAlignerTest test, Path ikeDev, Path origin,
                           Path seed) {

        static Fixture create(RefAlignerTest test) throws IOException {
            Path ikeDev = Files.createDirectories(
                    test.sandbox.resolve("ike-dev"));
            Path origin = test.sandbox.resolve("origin.git");
            Files.createDirectories(origin);
            test.run(origin, "init", "--bare", "--initial-branch=main");
            Path seed = test.sandbox.resolve("seed");
            test.git.run(test.sandbox, List.of("clone", "--quiet",
                    origin.toString(), seed.toString()));
            test.configureIdentity(seed);
            Files.writeString(seed.resolve("tracked.txt"), "first\n",
                    StandardCharsets.UTF_8);
            test.run(seed, "add", ".");
            test.run(seed, "commit", "-m", "first");
            test.run(seed, "push", "--quiet", "-u", "origin", "main");
            return new Fixture(test, ikeDev, origin, seed);
        }

        RefAligner aligner() {
            return new RefAligner(ikeDev, test.git, line -> { });
        }

        String originHead() {
            return test.revParse(seed, "HEAD");
        }

        Path takerClone(String name) {
            Path taker = ikeDev.resolve(name);
            GitResult clone = test.git.run(ikeDev, List.of("clone",
                    "--quiet", origin.toString(), taker.toString()));
            assertTrue(clone.ok(), "clone failed: " + clone.stderr());
            test.configureIdentity(taker);
            return taker;
        }

        /** Commits and pushes one more change on main; returns its hash. */
        String advanceOrigin(String content) throws IOException {
            Files.writeString(seed.resolve("tracked.txt"), content + "\n",
                    StandardCharsets.UTF_8);
            test.run(seed, "add", ".");
            test.run(seed, "commit", "-m", content);
            test.run(seed, "push", "--quiet", "origin", "main");
            return test.revParse(seed, "HEAD");
        }

        /**
         * Commits on a side branch cut from main and pushes it; main's
         * tip is untouched. Returns the side commit's hash.
         */
        String pushSideBranch(String branch, String content)
                throws IOException {
            test.run(seed, "checkout", "--quiet", "-b", branch);
            Files.writeString(seed.resolve(branch + ".txt"), content + "\n",
                    StandardCharsets.UTF_8);
            test.run(seed, "add", ".");
            test.run(seed, "commit", "-m", content);
            test.run(seed, "push", "--quiet", "origin", branch);
            String hash = test.revParse(seed, "HEAD");
            test.run(seed, "checkout", "--quiet", "main");
            return hash;
        }
    }

    private void configureIdentity(Path repo) {
        run(repo, "config", "user.email", "test@ike.network");
        run(repo, "config", "user.name", "Ref Aligner Test");
        run(repo, "config", "commit.gpgsign", "false");
        run(repo, "config", "core.hooksPath", ".git/hooks");
    }

    private static List<Status> statuses(AlignReport report) {
        return report.entries().stream().map(RefAligner.Entry::status)
                .toList();
    }

    /** Every file's bytes as hex, keyed by relative path — {@code .git}
     *  excluded. Hex strings compare by value, unlike {@code byte[]}. */
    private static Map<String, String> snapshot(Path root)
            throws IOException {
        Map<String, String> files = new HashMap<>();
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path path : tree.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(path).toString();
                if (!relative.startsWith(".git")) {
                    files.put(relative, java.util.HexFormat.of()
                            .formatHex(Files.readAllBytes(path)));
                }
            }
        }
        return files;
    }

    private String currentBranch(Path repo) {
        return require(repo, "symbolic-ref", "--short", "HEAD");
    }

    private String porcelainStatus(Path repo) {
        GitResult result = git.run(repo, List.of("status", "--porcelain"));
        assertTrue(result.ok(), "git status failed: " + result.stderr());
        return result.stdout().stripTrailing();
    }

    private String config(Path repo, String key) {
        return require(repo, "config", "--get", key);
    }

    private String revParse(Path repo, String ref) {
        return require(repo, "rev-parse", ref);
    }

    private void run(Path repo, String... args) {
        GitResult result = git.run(repo, List.of(args));
        assertTrue(result.ok(), "git " + String.join(" ", args)
                + " failed: " + result.stderr());
    }

    private String require(Path repo, String... args) {
        GitResult result = git.run(repo, List.of(args));
        assertTrue(result.ok(), "git " + String.join(" ", args)
                + " failed: " + result.stderr());
        return result.stdoutTrimmed();
    }
}
