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
import network.ike.lease.core.MaterializeReport.Action;
import network.ike.lease.core.MaterializeReport.Entry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Materialization against real git repositories in a sandboxed
 * development folder — the thing under test is a git-command sequence, so
 * git itself is the only honest test double.
 */
class MaterializerTest {

    private final GitRunner git = new ProcessGitRunner();

    @TempDir
    Path sandbox;

    // ------------------------------------------------------------------
    // Roots
    // ------------------------------------------------------------------

    @Test
    void bareRootMaterializesFromTheManifestWithTheTreeUntouched()
            throws IOException {
        Path ikeDev = ikeDev();
        Path upstream = upstreamRepo("upstream", "main");
        Path root = ikeDev.resolve("my-root");
        copyTree(upstream, root);
        Map<String, String> before = snapshot(root);

        MaterializeReport report = materializer(ikeDev,
                "my-root " + upstream + "\n")
                .materialize(new WorkingSetName("my-root"));

        assertTrue(report.ok(), report.entries().toString());
        assertEquals(1, report.count(Action.MATERIALIZED));
        assertEquals(before, snapshot(root), "the working tree changed");
        assertEquals("main", currentBranch(root));
        assertEquals("", porcelainStatus(root), "status should be clean");
        assertEquals("false", config(root, "core.fsmonitor"),
                "the #1052 guard must be set");
        assertEquals(upstream.toString(), originUrl(root));
    }

    @Test
    void syncedDriftSurvivesMaterializationAsTheExactStatusDelta()
            throws IOException {
        Path ikeDev = ikeDev();
        Path upstream = upstreamRepo("upstream", "main");
        Path root = ikeDev.resolve("my-root");
        copyTree(upstream, root);
        Files.writeString(root.resolve("tracked.txt"), "locally edited\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("fresh.txt"), "new work\n",
                StandardCharsets.UTF_8);

        MaterializeReport report = materializer(ikeDev,
                "my-root " + upstream + "\n")
                .materialize(new WorkingSetName("my-root"));

        assertTrue(report.ok(), report.entries().toString());
        assertEquals(java.util.Set.of(" M tracked.txt", "?? fresh.txt"),
                java.util.Set.copyOf(porcelainStatus(root).lines().toList()));
    }

    @Test
    void bareRootWithoutAManifestEntryIsRefusedWithTheRemedy()
            throws IOException {
        Path ikeDev = ikeDev();
        Files.createDirectories(ikeDev.resolve("unknown-root"));

        MaterializeReport report = materializer(ikeDev, "")
                .materialize(new WorkingSetName("unknown-root"));

        assertFalse(report.ok());
        Entry entry = report.entries().getFirst();
        assertEquals(Action.REFUSED, entry.action());
        assertTrue(entry.detail().contains("origins.conf"), entry.detail());
        assertFalse(Files.exists(ikeDev.resolve("unknown-root/.git")),
                "a refused materialization must leave no git state behind");
    }

    @Test
    void secondRunIsIdempotent() throws IOException {
        Path ikeDev = ikeDev();
        Path upstream = upstreamRepo("upstream", "main");
        Path root = ikeDev.resolve("my-root");
        copyTree(upstream, root);
        Materializer materializer = materializer(ikeDev,
                "my-root " + upstream + "\n");

        materializer.materialize(new WorkingSetName("my-root"));
        MaterializeReport second = materializer.materialize(
                new WorkingSetName("my-root"));

        assertTrue(second.ok());
        assertEquals(1, second.count(Action.ALREADY_MATERIALIZED));
        assertEquals(0, second.count(Action.MATERIALIZED));
    }

    // ------------------------------------------------------------------
    // Siblings
    // ------------------------------------------------------------------

    @Test
    void bareSiblingWiresToItsLocalParent() throws IOException {
        Path ikeDev = ikeDev();
        Path parent = upstreamRepo("ike-dev/parent-ws", "main");
        Path sibling = ikeDev.resolve("parent-ws꞉feat");
        copyTree(parent, sibling);
        Files.writeString(sibling.resolve("tracked.txt"), "feature work\n",
                StandardCharsets.UTF_8);

        MaterializeReport report = materializer(ikeDev, "")
                .materialize(new WorkingSetName("parent-ws꞉feat"));

        assertTrue(report.ok(), report.entries().toString());
        assertEquals("feature/feat", currentBranch(sibling));
        assertEquals(parent.toAbsolutePath().normalize().toString(),
                originUrl(sibling));
        assertEquals(" M tracked.txt", porcelainStatus(sibling));
        assertEquals("origin", config(sibling, "branch.main.remote"),
                "the base branch keeps its clone-time upstream shape");
        assertEquals("../parent-ws" + System.lineSeparator(),
                Files.readString(sibling.resolve(".ike/parent-workspace"),
                        StandardCharsets.UTF_8),
                "the parent record is derivable and must be re-created");
        assertTrue(Files.readString(sibling.resolve(".git/info/exclude"),
                        StandardCharsets.UTF_8)
                        .lines().anyMatch(".ike/parent-workspace"::equals),
                "the record is excluded via .git/info/exclude, never a "
                        + "tracked ignore");
    }

    @Test
    void workspaceSiblingWiresEveryNestedMemberToTheParentMember()
            throws IOException {
        Path ikeDev = ikeDev();
        Path parent = upstreamRepo("ike-dev/ws", "main");
        Path member = upstreamRepo("ike-dev/ws/member-repo", "main");
        Path sibling = ikeDev.resolve("ws꞉feat");
        copyTree(parent, sibling);

        MaterializeReport report = materializer(ikeDev, "")
                .materialize(new WorkingSetName("ws꞉feat"));

        assertTrue(report.ok(), report.entries().toString());
        assertEquals(2, report.count(Action.MATERIALIZED));
        Path siblingMember = sibling.resolve("member-repo");
        assertEquals("feature/feat", currentBranch(sibling));
        assertEquals("feature/feat", currentBranch(siblingMember));
        assertEquals(member.toAbsolutePath().normalize().toString(),
                originUrl(siblingMember));
    }

    @Test
    void bareParentMaterializesBeforeItsSibling() throws IOException {
        Path ikeDev = ikeDev();
        Path upstream = upstreamRepo("upstream", "main");
        Path parent = ikeDev.resolve("parent-ws");
        copyTree(upstream, parent);
        Path sibling = ikeDev.resolve("parent-ws꞉feat");
        copyTree(upstream, sibling);

        MaterializeReport report = materializer(ikeDev,
                "parent-ws " + upstream + "\n")
                .materialize(new WorkingSetName("parent-ws꞉feat"));

        assertTrue(report.ok(), report.entries().toString());
        assertEquals(2, report.count(Action.MATERIALIZED));
        assertEquals("main", currentBranch(parent));
        assertEquals("feature/feat", currentBranch(sibling));
        assertEquals(parent.toAbsolutePath().normalize().toString(),
                originUrl(sibling), "the sibling chains to the parent, "
                        + "never directly to the upstream");
    }

    @Test
    void detachedParentMemberIsRefusedWithRemediation() throws IOException {
        Path ikeDev = ikeDev();
        Path parent = upstreamRepo("ike-dev/parent-ws", "main");
        run(parent, "checkout", "--detach");
        Path sibling = ikeDev.resolve("parent-ws꞉feat");
        copyTree(parent, sibling);

        MaterializeReport report = materializer(ikeDev, "")
                .materialize(new WorkingSetName("parent-ws꞉feat"));

        assertFalse(report.ok());
        assertTrue(report.entries().stream().anyMatch(entry ->
                        entry.action() == Action.REFUSED
                                && entry.detail().contains("detached")),
                report.entries().toString());
    }

    // ------------------------------------------------------------------
    // The local-remote invariant
    // ------------------------------------------------------------------

    @Test
    void verifyReportsLegacyRemoteOriginsAndAcceptsLocalOnes()
            throws IOException {
        Path ikeDev = ikeDev();
        Path parent = upstreamRepo("ike-dev/parent-ws", "main");
        Path sibling = ikeDev.resolve("parent-ws꞉feat");
        copyTree(parent, sibling);
        Materializer materializer = materializer(ikeDev, "");
        materializer.materialize(new WorkingSetName("parent-ws꞉feat"));

        MaterializeReport local = materializer.verify(
                new WorkingSetName("parent-ws꞉feat"));
        assertEquals(1, local.count(Action.LOCAL_ORIGIN_OK),
                local.entries().toString());

        run(sibling, "remote", "set-url", "origin",
                "git@github.com:IKE-Network/parent-ws.git");
        MaterializeReport legacy = materializer.verify(
                new WorkingSetName("parent-ws꞉feat"));
        assertEquals(1, legacy.count(Action.REMOTE_ORIGIN_LEGACY),
                legacy.entries().toString());
    }

    @Test
    void repairRepointsLegacyOriginsToTheLocalParent() throws IOException {
        Path ikeDev = ikeDev();
        Path parent = upstreamRepo("ike-dev/parent-ws", "main");
        Path sibling = ikeDev.resolve("parent-ws꞉feat");
        copyTree(parent, sibling);
        Materializer materializer = materializer(ikeDev, "");
        materializer.materialize(new WorkingSetName("parent-ws꞉feat"));
        run(sibling, "remote", "set-url", "origin",
                "git@github.com:IKE-Network/parent-ws.git");

        MaterializeReport report = materializer.repair(
                new WorkingSetName("parent-ws꞉feat"));

        assertTrue(report.ok(), report.entries().toString());
        assertEquals(1, report.count(Action.REPAIRED));
        assertEquals(parent.toAbsolutePath().normalize().toString(),
                originUrl(sibling));
        assertEquals(1, materializer.repair(new WorkingSetName("parent-ws꞉feat"))
                        .count(Action.LOCAL_ORIGIN_OK),
                "a second repair finds nothing left to re-point");
    }

    @Test
    void repairOnARootIsRefused() throws IOException {
        Path ikeDev = ikeDev();
        Files.createDirectories(ikeDev.resolve("some-root"));

        MaterializeReport report = materializer(ikeDev, "")
                .repair(new WorkingSetName("some-root"));

        assertFalse(report.ok());
        assertTrue(report.entries().getFirst().detail().contains("siblings"));
    }

    @Test
    void localPathClassificationCoversEveryOriginShape() {
        assertTrue(Materializer.isLocalPath("/Users/kec/ike-dev/ws"));
        assertTrue(Materializer.isLocalPath("../parent-ws"));
        assertTrue(Materializer.isLocalPath("file:///Users/kec/ike-dev/ws"));
        assertFalse(Materializer.isLocalPath(
                "https://github.com/IKE-Network/x.git"));
        assertFalse(Materializer.isLocalPath(
                "ssh://git@github.com/IKE-Network/x.git"));
        assertFalse(Materializer.isLocalPath(
                "git@github.com-graphlet:IKE-Network/x.git"));
    }

    // ------------------------------------------------------------------
    // Helpers — real git throughout
    // ------------------------------------------------------------------

    private Path ikeDev() throws IOException {
        return Files.createDirectories(sandbox.resolve("ike-dev"));
    }

    private Materializer materializer(Path ikeDev, String manifestContent) {
        return new Materializer(ikeDev, git,
                OriginManifest.parse(manifestContent), line -> { });
    }

    /**
     * Creates a commit-bearing repository standing in for an upstream (or
     * a parent working set): one tracked file on the given branch.
     */
    private Path upstreamRepo(String relative, String branch)
            throws IOException {
        Path repo = Files.createDirectories(sandbox.resolve(relative));
        run(repo, "init", "--initial-branch=" + branch);
        run(repo, "config", "user.email", "test@ike.network");
        run(repo, "config", "user.name", "Materializer Test");
        run(repo, "config", "commit.gpgsign", "false");
        // The developer machine routes hooks through a global
        // core.hooksPath (commit-message generators); point the fixture
        // at its own empty hooks directory so test commits stay inert.
        run(repo, "config", "core.hooksPath", ".git/hooks");
        Files.writeString(repo.resolve("tracked.txt"), "committed content\n",
                StandardCharsets.UTF_8);
        run(repo, "add", ".");
        run(repo, "commit", "-m", "initial");
        return repo;
    }

    /** Copies a working tree, excluding {@code .git} — the sync layer. */
    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> tree = Files.walk(source)) {
            for (Path path : tree.toList()) {
                Path relative = source.relativize(path);
                if (relative.toString().isEmpty()
                        || relative.startsWith(".git")
                        || relative.toString().contains("/.git")) {
                    continue;
                }
                Path destination = target.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            }
        }
    }

    /** Every file's bytes as hex, keyed by relative path — {@code .git}
     *  excluded. Hex strings compare by value, unlike {@code byte[]}. */
    private static Map<String, String> snapshot(Path root) throws IOException {
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
        // Porcelain status is column-positional; the leading space of an
        // unstaged " M" entry is data, so only the trailing newline goes.
        GitResult result = git.run(repo, List.of("status", "--porcelain"));
        assertTrue(result.ok(), "git status failed: " + result.stderr());
        return result.stdout().stripTrailing();
    }

    private String originUrl(Path repo) {
        return require(repo, "remote", "get-url", "origin");
    }

    private String config(Path repo, String key) {
        return require(repo, "config", "--get", key);
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
