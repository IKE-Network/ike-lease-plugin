package network.ike.lease;

import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

import network.ike.lease.LeaseProjectListener.LeaseNotifier;
import network.ike.lease.core.MaterializeReport;
import network.ike.lease.core.MaterializeReport.Action;
import network.ike.lease.core.Materializer;
import network.ike.lease.core.OriginManifest;
import network.ike.lease.core.ProcessGitRunner;
import network.ike.lease.core.WorkingSetName;

/**
 * The IDE host of the materializer core (IKE-Network/ike-issues#1057).
 *
 * <p>Per the one-core/two-thin-hosts contract this class contributes only
 * the trigger (the open gesture, after the confirmed acquisition), an
 * interaction surface (progress text, balloons, and a repair action for
 * legacy origins), and the IDE-specific cleanup (VFS refresh and a VCS
 * mapping so the fresh git state appears without reopening the project).
 * Everything decision-shaped stays in {@code network.ike.lease.core}.
 *
 * <p>The git runner is the same {@link ProcessGitRunner} the headless host
 * uses: on this fleet, git authenticates through the {@code IdentityAgent}
 * wired in {@code ~/.ssh/config} (and canonical manifest URLs are
 * rewritten by each machine's {@code insteadOf} rules), so a git
 * subprocess authenticates identically inside and outside the IDE. The
 * {@code GitRunner} seam stays open for a git4idea-backed runner if
 * credential prompting ever matters.
 */
final class MaterializeOnOpen {

    private MaterializeOnOpen() { }

    /**
     * Materializes the working set inside an already-running background
     * task, then reports and refreshes on the event dispatch thread.
     *
     * @param project    the project just opened on the working set
     * @param lease      the bridge to the lease protocol
     * @param workingSet the working-set directory name
     * @param indicator  the surrounding task's progress indicator
     */
    static void run(Project project, LeaseCli lease, String workingSet,
                    ProgressIndicator indicator) {
        indicator.setText("Materializing git state: " + workingSet);
        Materializer materializer = materializer(lease, indicator);
        MaterializeReport report;
        try {
            report = materializer.materialize(new WorkingSetName(workingSet));
        } catch (RuntimeException e) {
            // Materialization is an accessory to opening the project, so a
            // failure informs rather than blocks: the lease is held either
            // way, and the operator can materialize by hand.
            ApplicationManager.getApplication().invokeLater(() ->
                    LeaseNotifier.warn(project, "Materialization failed",
                            workingSet + ": " + e.getMessage()));
            return;
        }
        ApplicationManager.getApplication().invokeLater(
                () -> present(project, lease, workingSet, report));
    }

    /**
     * Schedules materialization in its own background task, for call sites
     * that are not already inside one (the takeover path).
     *
     * @param project    the project just opened on the working set
     * @param lease      the bridge to the lease protocol
     * @param workingSet the working-set directory name
     */
    static void runInBackground(Project project, LeaseCli lease,
                                String workingSet) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Materializing git state: " + workingSet, false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                MaterializeOnOpen.run(project, lease, workingSet, indicator);
            }
        });
    }

    private static Materializer materializer(LeaseCli lease,
                                             ProgressIndicator indicator) {
        Path ikeDev = lease.root();
        return new Materializer(ikeDev, new ProcessGitRunner(),
                OriginManifest.load(ikeDev), indicator::setText2);
    }

    private static void present(Project project, LeaseCli lease,
                                String workingSet, MaterializeReport report) {
        long materialized = report.count(Action.MATERIALIZED);
        long refused = report.count(Action.REFUSED);
        long legacy = report.count(Action.REMOTE_ORIGIN_LEGACY);

        if (materialized > 0) {
            LeaseNotifier.info(project, "Git state materialized",
                    workingSet + ": " + materialized + (materialized == 1
                            ? " repository wired" : " repositories wired")
                            + " — tree untouched.");
            refreshVcsView(project, lease, workingSet);
        }
        if (refused > 0) {
            LeaseNotifier.warn(project, "Materialization incomplete",
                    refusedSummary(report, workingSet));
        }
        if (legacy > 0) {
            LeaseNotifier.notification(project,
                    "Sibling has remote-remote origins",
                    legacy + (legacy == 1 ? " repository chains" :
                            " repositories chain") + " to GitHub instead of "
                            + "the local parent (ike-issues#992):<br/>"
                            + memberSummary(report,
                                    Action.REMOTE_ORIGIN_LEGACY, workingSet)
                            + "<br/>Repair re-points origins only — tree and "
                            + "branches are untouched.",
                    NotificationType.WARNING,
                    NotificationAction.createSimple("Repair to local origins",
                            () -> repairInBackground(project, lease,
                                    workingSet)));
        }
    }

    private static void repairInBackground(Project project, LeaseCli lease,
                                           String workingSet) {
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Re-pointing sibling origins: " + workingSet, false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                MaterializeReport report = materializer(lease, indicator)
                        .repair(new WorkingSetName(workingSet));
                ApplicationManager.getApplication().invokeLater(() -> {
                    long repaired = report.count(Action.REPAIRED);
                    if (report.ok()) {
                        LeaseNotifier.info(project, "Origins repaired",
                                workingSet + ": " + repaired
                                        + " re-pointed to the local parent.");
                    } else {
                        LeaseNotifier.warn(project, "Repair incomplete",
                                refusedSummary(report, workingSet));
                    }
                });
            }
        });
    }

    /**
     * Makes the IDE notice the repositories that now exist: a recursive
     * asynchronous VFS refresh of the working-set root, plus a project
     * Git mapping when the project has none at all — a project opened on
     * a bare tree has nothing mapped, and git4idea's root detection only
     * scans under mappings.
     */
    private static void refreshVcsView(Project project, LeaseCli lease,
                                       String workingSet) {
        VirtualFile root = VirtualFileManager.getInstance()
                .refreshAndFindFileByNioPath(
                        lease.root().resolve(workingSet));
        if (root != null) {
            root.refresh(true, true);
        }
        ProjectLevelVcsManager vcsManager =
                ProjectLevelVcsManager.getInstance(project);
        if (vcsManager.getDirectoryMappings().isEmpty()) {
            vcsManager.setDirectoryMappings(
                    List.of(new VcsDirectoryMapping("", "Git")));
        }
    }

    /** How many members a balloon names before "… and N more". */
    private static final int BALLOON_MEMBER_CAP = 6;

    /**
     * Balloon-facing member list for one action: names relative to the
     * working set, comma-joined, capped. The full per-repository detail
     * stays where it belongs — {@code MaterializeCli verify} and the
     * Notifications tool window — a balloon carries the diagnosis once,
     * never sixteen rationale sentences. (Balloon content is HTML: plain
     * newlines collapse to spaces, which is exactly the wall of text this
     * formatter replaces.)
     *
     * @param report     the operation's report
     * @param action     the action to list members for
     * @param workingSet the working set the paths are relativized against
     * @return the capped, comma-joined member names
     */
    private static String memberSummary(MaterializeReport report,
                                        Action action, String workingSet) {
        return capped(report.entries().stream()
                .filter(entry -> entry.action() == action)
                .map(entry -> memberName(entry.path(), workingSet))
                .toList(), ", ");
    }

    /**
     * Balloon-facing refusal list: member name and reason per line, capped.
     *
     * @param report     the operation's report
     * @param workingSet the working set the paths are relativized against
     * @return the capped, {@code <br/>}-joined refusal lines
     */
    private static String refusedSummary(MaterializeReport report,
                                         String workingSet) {
        return capped(report.entries().stream()
                .filter(entry -> entry.action() == Action.REFUSED)
                .map(entry -> memberName(entry.path(), workingSet) + ": "
                        + escape(entry.detail()))
                .toList(), "<br/>");
    }

    private static String memberName(String path, String workingSet) {
        if (path.equals(workingSet)) {
            return "(root)";
        }
        String prefix = workingSet + "/";
        return path.startsWith(prefix)
                ? path.substring(prefix.length()) : path;
    }

    private static String capped(List<String> items, String separator) {
        if (items.size() <= BALLOON_MEMBER_CAP) {
            return String.join(separator, items);
        }
        return String.join(separator, items.subList(0, BALLOON_MEMBER_CAP))
                + separator + "… and "
                + (items.size() - BALLOON_MEMBER_CAP) + " more";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
