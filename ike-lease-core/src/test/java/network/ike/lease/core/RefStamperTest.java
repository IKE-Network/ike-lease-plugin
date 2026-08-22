package network.ike.lease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stamp collection by file reads against real repositories
 * (IKE-Network/ike-issues#1069): the stamper must agree with git's own
 * answer in every layout git actually uses — loose refs, packed refs,
 * gitfiles — and must skip what it cannot interpret rather than guess.
 */
class RefStamperTest {

    private final GitRunner git = new ProcessGitRunner();

    @TempDir
    Path sandbox;

    @Test
    void rootRepositoryStampsFromLooseRefs() throws IOException {
        Path ikeDev = ikeDev();
        Path root = repo(ikeDev.resolve("my-root"), "main");

        List<RepoStamp> stamps = RefStamper.collect(ikeDev, "my-root");

        assertEquals(List.of(new RepoStamp(".", "main",
                        revParse(root, "HEAD"))), stamps);
    }

    @Test
    void packedRefsResolveWhenLooseRefsAreGone() throws IOException {
        Path ikeDev = ikeDev();
        Path root = repo(ikeDev.resolve("my-root"), "main");
        run(root, "pack-refs", "--all");
        assertTrue(Files.notExists(
                        root.resolve(".git/refs/heads/main")),
                "precondition: the loose ref must be packed away");

        List<RepoStamp> stamps = RefStamper.collect(ikeDev, "my-root");

        assertEquals(List.of(new RepoStamp(".", "main",
                revParse(root, "HEAD"))), stamps);
    }

    @Test
    void workspaceMembersStampSortedWithRelativePaths() throws IOException {
        Path ikeDev = ikeDev();
        Path workspace = Files.createDirectories(
                ikeDev.resolve("my-workspace"));
        Path memberB = repo(workspace.resolve("member-b"), "main");
        Path memberA = repo(workspace.resolve("member-a"), "work");

        List<RepoStamp> stamps = RefStamper.collect(ikeDev, "my-workspace");

        assertEquals(List.of(
                new RepoStamp("member-a", "work", revParse(memberA, "HEAD")),
                new RepoStamp("member-b", "main", revParse(memberB, "HEAD"))),
                stamps);
    }

    @Test
    void detachedHeadIsSkippedNotGuessed() throws IOException {
        Path ikeDev = ikeDev();
        Path root = repo(ikeDev.resolve("my-root"), "main");
        run(root, "checkout", "--quiet", "--detach");

        assertEquals(List.of(), RefStamper.collect(ikeDev, "my-root"),
                "a detached HEAD has no branch to stamp");
    }

    @Test
    void gitfileIndirectionResolves() throws IOException {
        Path ikeDev = ikeDev();
        Path root = repo(ikeDev.resolve("my-root"), "main");
        String head = revParse(root, "HEAD");
        Path movedGitDir = sandbox.resolve("moved-git");
        Files.move(root.resolve(".git"), movedGitDir);
        Files.writeString(root.resolve(".git"),
                "gitdir: " + movedGitDir + "\n", StandardCharsets.UTF_8);

        assertEquals(List.of(new RepoStamp(".", "main", head)),
                RefStamper.collect(ikeDev, "my-root"));
    }

    @Test
    void siblingsAreNeverStamped() throws IOException {
        Path ikeDev = ikeDev();
        repo(ikeDev.resolve("my-root꞉feature"), "feature/feature");

        assertEquals(List.of(),
                RefStamper.collect(ikeDev, "my-root꞉feature"),
                "a sibling's refs are its own (ike-issues#992)");
    }

    @Test
    void absentTreesAndHusksYieldNoStamps() throws IOException {
        Path ikeDev = ikeDev();
        assertEquals(List.of(), RefStamper.collect(ikeDev, "not-on-disk"));

        Files.createDirectories(ikeDev.resolve("husk").resolve(".git"));
        assertEquals(List.of(), RefStamper.collect(ikeDev, "husk"),
                "an empty .git husk is not a repository");
    }

    // ------------------------------------------------------------------
    // Helpers — real git throughout
    // ------------------------------------------------------------------

    private Path ikeDev() throws IOException {
        return Files.createDirectories(sandbox.resolve("ike-dev"));
    }

    private Path repo(Path directory, String branch) throws IOException {
        Files.createDirectories(directory);
        run(directory, "init", "--initial-branch=" + branch);
        run(directory, "config", "user.email", "test@ike.network");
        run(directory, "config", "user.name", "Ref Stamper Test");
        run(directory, "config", "commit.gpgsign", "false");
        run(directory, "config", "core.hooksPath", ".git/hooks");
        Files.writeString(directory.resolve("tracked.txt"), "content\n",
                StandardCharsets.UTF_8);
        run(directory, "add", ".");
        run(directory, "commit", "-m", "initial");
        return directory;
    }

    private String revParse(Path repo, String ref) {
        GitRunner.GitResult result = git.run(repo,
                List.of("rev-parse", ref));
        assertTrue(result.ok(), "git rev-parse failed: " + result.stderr());
        return result.stdoutTrimmed();
    }

    private void run(Path repo, String... args) {
        GitRunner.GitResult result = git.run(repo, List.of(args));
        assertTrue(result.ok(), "git " + String.join(" ", args)
                + " failed: " + result.stderr());
    }
}
