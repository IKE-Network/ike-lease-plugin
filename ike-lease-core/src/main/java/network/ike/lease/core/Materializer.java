package network.ike.lease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import network.ike.lease.core.GitRunner.GitResult;
import network.ike.lease.core.MaterializeReport.Action;
import network.ike.lease.core.MaterializeReport.Entry;

/**
 * Creates machine-local git state for a working set whose tree arrived by
 * sync — the core of IKE-Network/ike-issues#1057, shared by the IDE
 * adapter and the headless CLI host.
 *
 * <p>{@code .git} never syncs, so a working set arrives on another machine
 * as a tree with no repository. Materialization brings git metadata
 * <em>to</em> that tree and never the tree to the metadata: the sequence is
 * in-place {@code init}, {@code fetch}, ref creation, {@code reset --mixed}
 * — no {@code clone} (which refuses a non-empty directory), no
 * {@code checkout}, {@code switch} or {@code merge} (which rewrite files).
 * The synced tree is authoritative; after materialization {@code git
 * status} shows exactly the uncommitted delta the tree carries.
 *
 * <p>Two kinds of working set, two sources of truth:
 *
 * <ul>
 *   <li><b>Roots</b> materialize from the synced origin manifest
 *       ({@code scripts/origins.conf}); the branch is the remote's default
 *       branch.</li>
 *   <li><b>Siblings</b> ({@code ꞉} in the name) wire to their <em>local</em>
 *       parent, never a remote remote: per member, {@code origin} is the
 *       parent member's path and the branch is
 *       {@code feature/<suffix>} cut from the parent member's current
 *       branch — the {@code ws:feature-start-sibling} derivation
 *       (IKE-Network/ike-issues#992). A bare parent materializes first;
 *       a sibling can only fetch from a parent that has git state.</li>
 * </ul>
 *
 * <p>Every repository this class creates gets {@code core.fsmonitor=false}
 * written to its repo-local config before any other git operation runs —
 * a freshly created repo's first fsmonitor daemon query can deadlock on
 * macOS under a file watcher such as Syncthing
 * (IKE-Network/ike-issues#1052).
 */
public final class Materializer {

    /** Directories never descended into while discovering parent members. */
    private static final List<String> SKIPPED_DIRECTORIES =
            List.of(".git", ".ike", ".idea", ".stversions", "target");

    /** How deep member discovery looks below the parent root. */
    private static final int MEMBER_DISCOVERY_DEPTH = 3;

    private final Path ikeDev;
    private final GitRunner git;
    private final OriginManifest manifest;
    private final Consumer<String> progress;

    /**
     * Creates a materializer.
     *
     * @param ikeDev   the development-folder root, normally {@code ~/ike-dev}
     * @param git      the git runner the host contributes
     * @param manifest the loaded origin manifest
     * @param progress sink for one-line progress narration
     */
    public Materializer(Path ikeDev, GitRunner git, OriginManifest manifest,
                        Consumer<String> progress) {
        this.ikeDev = ikeDev;
        this.git = git;
        this.manifest = manifest;
        this.progress = progress;
    }

    /**
     * Materializes a working set: creates git state for every bare
     * repository in it, leaving the working tree byte-identical.
     *
     * <p>Idempotent — repositories that already have git state are
     * reported and left alone. Decisions are never guessed: a bare root
     * repository with no manifest entry, or a parent that cannot be
     * materialized, is refused with a remedy in the entry detail.
     *
     * @param name the working-set directory name
     * @return the per-repository outcomes
     */
    public MaterializeReport materialize(WorkingSetName name) {
        List<Entry> entries = new ArrayList<>();
        Path workingSetDir = ikeDev.resolve(name.value());
        if (!Files.isDirectory(workingSetDir)) {
            entries.add(new Entry(name.value(), Action.REFUSED,
                    "not on disk under " + ikeDev));
            return new MaterializeReport(name, entries);
        }
        if (name.isSibling()) {
            materializeSibling(name, workingSetDir, entries);
        } else {
            materializeRoot(name, entries);
        }
        return new MaterializeReport(name, entries);
    }

    /**
     * Verifies a working set without changing anything: reports which
     * repositories have git state, and for sibling members whether the
     * local-remote invariant holds ({@code origin} is a local parent path,
     * never a remote remote).
     *
     * @param name the working-set directory name
     * @return the per-repository findings
     */
    public MaterializeReport verify(WorkingSetName name) {
        List<Entry> entries = new ArrayList<>();
        Path workingSetDir = ikeDev.resolve(name.value());
        if (!Files.isDirectory(workingSetDir)) {
            entries.add(new Entry(name.value(), Action.REFUSED,
                    "not on disk under " + ikeDev));
            return new MaterializeReport(name, entries);
        }
        if (name.isSibling()) {
            Path parentDir = ikeDev.resolve(name.parent().value());
            for (String member : discoverMembers(parentDir)) {
                Path memberDir = resolveMember(workingSetDir, member);
                String reportPath = reportPath(name.value(), member);
                if (!Files.isDirectory(memberDir)) {
                    entries.add(new Entry(reportPath, Action.NO_TREE, ""));
                } else if (hasGit(memberDir)) {
                    entries.add(originInvariantEntry(reportPath, memberDir));
                } else {
                    entries.add(new Entry(reportPath, Action.NO_GIT, ""));
                }
            }
        } else {
            SequencedMap<String, String> declared = manifest.entriesFor(name);
            if (declared.isEmpty()) {
                entries.add(new Entry(name.value(),
                        hasGit(workingSetDir) ? Action.ALREADY_MATERIALIZED
                                : Action.NO_GIT,
                        "no " + OriginManifest.RELATIVE_PATH + " entry"));
            }
            for (Map.Entry<String, String> entry : declared.entrySet()) {
                Path repoDir = ikeDev.resolve(entry.getKey());
                Action action = !Files.isDirectory(repoDir) ? Action.NO_TREE
                        : hasGit(repoDir) ? Action.ALREADY_MATERIALIZED
                        : Action.NO_GIT;
                entries.add(new Entry(entry.getKey(), action, ""));
            }
        }
        return new MaterializeReport(name, entries);
    }

    private void materializeRoot(WorkingSetName name, List<Entry> entries) {
        SequencedMap<String, String> declared = manifest.entriesFor(name);
        Path workingSetDir = ikeDev.resolve(name.value());
        if (declared.isEmpty()) {
            if (hasGit(workingSetDir)) {
                entries.add(new Entry(name.value(),
                        Action.ALREADY_MATERIALIZED, ""));
            } else {
                entries.add(new Entry(name.value(), Action.REFUSED,
                        "bare tree with no manifest entry; add '" + name.value()
                                + " <url>' to " + OriginManifest.RELATIVE_PATH));
            }
            return;
        }
        for (Map.Entry<String, String> declaredRepo : declared.entrySet()) {
            String path = declaredRepo.getKey();
            String url = declaredRepo.getValue();
            Path repoDir = ikeDev.resolve(path);
            if (!Files.isDirectory(repoDir)) {
                entries.add(new Entry(path, Action.NO_TREE,
                        "synced tree not present yet"));
                continue;
            }
            if (hasGit(repoDir)) {
                entries.add(new Entry(path, Action.ALREADY_MATERIALIZED, ""));
                continue;
            }
            entries.add(materializeRootRepo(path, repoDir, url));
        }
    }

    private Entry materializeRootRepo(String path, Path repoDir, String url) {
        progress.accept("materializing " + path + " from " + url);
        GitResult head = git.run(repoDir,
                List.of("ls-remote", "--symref", url, "HEAD"));
        if (!head.ok()) {
            return new Entry(path, Action.REFUSED,
                    "could not reach origin " + url + ": "
                            + head.stderr().strip());
        }
        String branch = parseDefaultBranch(head.stdout());
        if (branch == null) {
            return new Entry(path, Action.REFUSED,
                    "could not determine the default branch of " + url);
        }
        return materializeRepo(path, repoDir, url, branch, branch);
    }

    private void materializeSibling(WorkingSetName name, Path siblingDir,
                                    List<Entry> entries) {
        WorkingSetName parentName = name.parent();
        Path parentDir = ikeDev.resolve(parentName.value());
        if (!Files.isDirectory(parentDir)) {
            entries.add(new Entry(name.value(), Action.REFUSED,
                    "parent working set " + parentName.value()
                            + " is not on disk; a sibling chains through its "
                            + "local parent (ike-issues#992)"));
            return;
        }
        // Parent before sibling: a sibling can only fetch from a parent
        // that has git state. The parent pass always runs — it is
        // idempotent, and a parent whose root is materialized can still
        // carry a bare nested member that discovery would otherwise miss.
        materializeRoot(parentName, entries);
        if (!hasGit(parentDir)) {
            entries.add(new Entry(name.value(), Action.REFUSED,
                    "parent " + parentName.value() + " could not be "
                            + "materialized; the sibling chains through it"));
            return;
        }
        for (String member : discoverMembers(parentDir)) {
            Path memberDir = resolveMember(siblingDir, member);
            Path parentMember = resolveMember(parentDir, member);
            String reportPath = reportPath(name.value(), member);
            if (!Files.isDirectory(memberDir)) {
                entries.add(new Entry(reportPath, Action.NO_TREE,
                        "synced tree not present yet"));
                continue;
            }
            if (hasGit(memberDir)) {
                entries.add(originInvariantEntry(reportPath, memberDir));
                continue;
            }
            GitResult base = git.run(parentMember,
                    List.of("symbolic-ref", "--short", "HEAD"));
            if (!base.ok()) {
                entries.add(new Entry(reportPath, Action.REFUSED,
                        "parent member has no current branch (detached "
                                + "HEAD?); check out a branch in "
                                + parentMember + " and re-run"));
                continue;
            }
            Entry entry = materializeRepo(reportPath, memberDir,
                    parentMember.toAbsolutePath().normalize().toString(),
                    base.stdoutTrimmed(), name.featureBranch());
            entries.add(entry);
            if (member.isEmpty() && entry.action() == Action.MATERIALIZED) {
                recordParentWorkspace(siblingDir, parentDir, entries,
                        name.value());
            }
        }
    }

    /**
     * The tree-untouched materialization sequence for one repository.
     *
     * <p>On any failure after {@code git init}, the partially created
     * {@code .git} directory is removed again so the repository reads as
     * bare on the next attempt rather than as materialized. Only the
     * directory this very call created is ever deleted, and {@code .git}
     * is excluded from sync, so the rollback is local by construction.
     */
    private Entry materializeRepo(String reportPath, Path repoDir, String origin,
                                  String baseBranch, String targetBranch) {
        List<List<String>> commands = new ArrayList<>();
        commands.add(List.of("init"));
        // The #1052 guard, before any command that could wake fsmonitor.
        commands.add(List.of("config", "core.fsmonitor", "false"));
        commands.add(List.of("remote", "add", "origin", origin));
        commands.add(List.of("fetch", "--quiet", "origin",
                "+refs/heads/" + baseBranch
                        + ":refs/remotes/origin/" + baseBranch));
        commands.add(List.of("branch", baseBranch,
                "refs/remotes/origin/" + baseBranch));
        commands.add(List.of("config", "branch." + baseBranch + ".remote",
                "origin"));
        commands.add(List.of("config", "branch." + baseBranch + ".merge",
                "refs/heads/" + baseBranch));
        if (!targetBranch.equals(baseBranch)) {
            commands.add(List.of("branch", targetBranch,
                    "refs/remotes/origin/" + baseBranch));
        }
        commands.add(List.of("symbolic-ref", "HEAD",
                "refs/heads/" + targetBranch));
        commands.add(List.of("reset", "--quiet"));

        for (List<String> command : commands) {
            GitResult result = git.run(repoDir, command);
            if (!result.ok()) {
                rollBackGitDirectory(repoDir);
                return new Entry(reportPath, Action.REFUSED,
                        "git " + String.join(" ", command) + " failed: "
                                + result.stderr().strip());
            }
        }
        return new Entry(reportPath, Action.MATERIALIZED,
                "origin " + origin + ", branch " + targetBranch);
    }

    /**
     * Mirrors {@code ws:feature-start-sibling}'s parent-workspace record on
     * the materialization path, as its creation code requires: the
     * {@code .ike/parent-workspace} relative path is written when the sync
     * layer has not carried it, and the fresh {@code .git/info/exclude}
     * regains the exclusion line so the record never reads as content to
     * merge back.
     */
    private void recordParentWorkspace(Path siblingDir, Path parentDir,
                                       List<Entry> entries, String name) {
        try {
            Path record = siblingDir.resolve(".ike/parent-workspace");
            if (!Files.exists(record)) {
                Files.createDirectories(record.getParent());
                Path relative = siblingDir.toAbsolutePath().normalize()
                        .relativize(parentDir.toAbsolutePath().normalize());
                Files.writeString(record,
                        relative + System.lineSeparator(),
                        StandardCharsets.UTF_8);
            }
            Path exclude = siblingDir.resolve(".git/info/exclude");
            Files.createDirectories(exclude.getParent());
            String line = ".ike/parent-workspace";
            String existing = Files.exists(exclude)
                    ? Files.readString(exclude, StandardCharsets.UTF_8) : "";
            if (existing.lines().noneMatch(l -> l.strip().equals(line))) {
                String separator = existing.isEmpty() || existing.endsWith("\n")
                        ? "" : "\n";
                Files.writeString(exclude, existing + separator + line + "\n",
                        StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            entries.add(new Entry(name, Action.REFUSED,
                    "could not record the parent workspace: "
                            + e.getMessage()));
        }
    }

    private Entry originInvariantEntry(String reportPath, Path repoDir) {
        GitResult url = git.run(repoDir,
                List.of("remote", "get-url", "origin"));
        if (!url.ok()) {
            return new Entry(reportPath, Action.REMOTE_ORIGIN_LEGACY,
                    "no origin remote configured");
        }
        String origin = url.stdoutTrimmed();
        if (isLocalPath(origin)) {
            return new Entry(reportPath, Action.LOCAL_ORIGIN_OK,
                    "origin " + origin);
        }
        return new Entry(reportPath, Action.REMOTE_ORIGIN_LEGACY,
                "origin " + origin + " is a remote remote; a sibling chains "
                        + "through its local parent (ike-issues#992)");
    }

    /**
     * Re-points a sibling's legacy remote-remote origins to the local
     * parent member paths — the migration recipe IKE-Network/ike-issues#992
     * left open, run per member. Members already on a local origin are
     * left untouched and reported as such; bare members are reported, not
     * materialized (that is {@link #materialize}'s job).
     *
     * @param name the sibling working-set directory name
     * @return the per-repository outcomes
     */
    public MaterializeReport repair(WorkingSetName name) {
        List<Entry> entries = new ArrayList<>();
        Path siblingDir = ikeDev.resolve(name.value());
        if (!name.isSibling()) {
            entries.add(new Entry(name.value(), Action.REFUSED,
                    "repair applies to siblings; a root's origin comes from "
                            + OriginManifest.RELATIVE_PATH));
            return new MaterializeReport(name, entries);
        }
        Path parentDir = ikeDev.resolve(name.parent().value());
        if (!Files.isDirectory(siblingDir) || !Files.isDirectory(parentDir)
                || !hasGit(parentDir)) {
            entries.add(new Entry(name.value(), Action.REFUSED,
                    "sibling and a materialized parent must both be on disk"));
            return new MaterializeReport(name, entries);
        }
        for (String member : discoverMembers(parentDir)) {
            Path memberDir = resolveMember(siblingDir, member);
            String reportPath = reportPath(name.value(), member);
            if (!Files.isDirectory(memberDir)) {
                entries.add(new Entry(reportPath, Action.NO_TREE, ""));
                continue;
            }
            if (!hasGit(memberDir)) {
                entries.add(new Entry(reportPath, Action.NO_GIT,
                        "materialize creates git state; repair only re-points"
                                + " origins"));
                continue;
            }
            Entry current = originInvariantEntry(reportPath, memberDir);
            if (current.action() != Action.REMOTE_ORIGIN_LEGACY) {
                entries.add(current);
                continue;
            }
            String parentPath = resolveMember(parentDir, member)
                    .toAbsolutePath().normalize().toString();
            GitResult set = git.run(memberDir,
                    List.of("remote", "set-url", "origin", parentPath));
            entries.add(set.ok()
                    ? new Entry(reportPath, Action.REPAIRED,
                            "origin re-pointed to " + parentPath)
                    : new Entry(reportPath, Action.REFUSED,
                            "git remote set-url failed: "
                                    + set.stderr().strip()));
        }
        return new MaterializeReport(name, entries);
    }

    /**
     * Classifies a git origin as a local filesystem path or a remote URL.
     *
     * @param origin the configured origin
     * @return {@code true} for filesystem paths ({@code file://} included),
     *         {@code false} for scheme and scp-style remote URLs
     */
    public static boolean isLocalPath(String origin) {
        if (origin.startsWith("file://")) {
            return true;
        }
        if (origin.contains("://")) {
            return false;
        }
        // scp-style user@host:path — the one URL form with no scheme.
        return !origin.matches("^[^/@]+@[^/:]+:.*");
    }

    /**
     * Discovers the parent's member repositories: the parent root itself,
     * plus every git repository below it, without descending into
     * repositories, hidden directories, or build output.
     *
     * @param parentDir the parent working set's directory
     * @return member paths relative to the parent root, the root itself as
     *         the empty string, shallowest first
     */
    private List<String> discoverMembers(Path parentDir) {
        List<String> members = new ArrayList<>();
        if (hasGit(parentDir)) {
            members.add("");
        }
        collectMembers(parentDir, parentDir, 0, members);
        members.sort(Comparator
                .comparingInt((String member) ->
                        member.split("/", -1).length)
                .thenComparing(Comparator.naturalOrder()));
        return members;
    }

    private void collectMembers(Path parentRoot, Path directory, int depth,
                                List<String> members) {
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
                    members.add(parentRoot.relativize(child).toString());
                } else {
                    collectMembers(parentRoot, child, depth + 1, members);
                }
            }
        } catch (IOException e) {
            // An unreadable directory hides its members; surfacing that as
            // a hard failure would block the readable rest. Skip it.
            progress.accept("skipping unreadable " + directory + ": "
                    + e.getMessage());
        }
    }

    private static Path resolveMember(Path root, String member) {
        return member.isEmpty() ? root : root.resolve(member);
    }

    private static String reportPath(String workingSet, String member) {
        return member.isEmpty() ? workingSet : workingSet + "/" + member;
    }

    private static boolean hasGit(Path directory) {
        Path git = directory.resolve(".git");
        // A gitfile (worktree or submodule pointer) is real git state. A
        // .git DIRECTORY is real only when it has a HEAD: the sync
        // layer's `(?d).git/` pattern excludes contents but carries the
        // directory entry itself, so peers hold empty ".git" husks —
        // measured fleet-wide 2026-08-22: every root and sibling on both
        // peer machines was a husk, invisible to an existence check.
        return Files.isRegularFile(git) || Files.exists(git.resolve("HEAD"));
    }

    private static String parseDefaultBranch(String lsRemoteOutput) {
        return lsRemoteOutput.lines()
                .filter(line -> line.startsWith("ref:")
                        && line.contains("refs/heads/"))
                .map(line -> line.substring(
                        line.indexOf("refs/heads/") + "refs/heads/".length())
                        .split("\\s")[0])
                .findFirst()
                .orElse(null);
    }

    private void rollBackGitDirectory(Path repoDir) {
        Path gitDir = repoDir.resolve(".git");
        // Contents only — NEVER the .git directory entry itself. The sync
        // layer indexes the entry (that is how the fleet's empty husks
        // propagate), so deleting it here would propagate as a deletion,
        // and `(?d)` then permits receivers to delete their real, ignored
        // .git contents to honor it — the likeliest mechanism of the
        // 2026-08 fleet-wide .git loss. An empty .git left behind is the
        // fleet's ordinary husk condition, and this class now reads it as
        // bare, so the retry story is unchanged.
        try (Stream<Path> tree = Files.walk(gitDir)) {
            for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(gitDir)) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException e) {
            progress.accept("could not roll back " + gitDir + ": "
                    + e.getMessage() + " — empty it by hand before retrying");
        }
    }
}
