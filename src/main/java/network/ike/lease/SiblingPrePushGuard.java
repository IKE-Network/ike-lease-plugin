package network.ike.lease;

import com.intellij.dvcs.push.PrePushHandler;
import com.intellij.dvcs.push.PushInfo;
import com.intellij.dvcs.push.PushTarget;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.push.GitPushTarget;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.util.List;

import network.ike.lease.LeaseProjectListener.LeaseNotifier;
import network.ike.lease.core.Materializer;
import network.ike.lease.core.WorkingSetName;

/**
 * Refuses pushes from a sibling working set to anything but its local
 * parent — the enforcement arm of the local-remote invariant
 * (IKE-Network/ike-issues#992, #1057).
 *
 * <p>A sibling chains sibling → parent → GitHub: its per-issue branches
 * are disposable by design, and two machines' re-rooted sibling histories
 * pushed to the same remote branch would fight with non-fast-forward
 * errors there. Externalization is the parent's own explicit
 * {@code ws:push}. The Claude fence carries the mirror rule — the same
 * one-rule-on-both-surfaces pattern as takeover.
 *
 * <p>The guard binds only sibling repositories (a {@code ꞉} working-set
 * segment under the development folder) and only positively identified
 * remote-remote targets; everything else passes untouched, so it can
 * never wedge ordinary work — the fail-open discipline of every lease
 * enforcement arm.
 */
public final class SiblingPrePushGuard implements PrePushHandler {

    /** Creates the guard; the platform instantiates this reflectively. */
    public SiblingPrePushGuard() { }

    /**
     * Names the guard in the push dialog's progress and abort messages.
     *
     * @return the human-readable handler name
     */
    @Override
    public @NotNull String getPresentableName() {
        return "IKE sibling local-origin guard";
    }

    /**
     * Checks every outgoing push and aborts the batch when any of it
     * would carry a sibling's branch to a remote remote.
     *
     * @param project     the project the push runs in
     * @param pushDetails one entry per repository being pushed
     * @param indicator   the push flow's progress indicator
     * @return {@link Result#OK} when no sibling pushes to a remote remote;
     *         {@link Result#ABORT} otherwise
     */
    @Override
    public @NotNull Result handle(@NotNull Project project,
                                  @NotNull List<PushInfo> pushDetails,
                                  @NotNull ProgressIndicator indicator) {
        Path ikeDev = LeaseCli.forCurrentUser().root()
                .toAbsolutePath().normalize();
        for (PushInfo info : pushDetails) {
            String workingSet = siblingWorkingSetOf(ikeDev,
                    info.getRepository().getRoot());
            if (workingSet == null) {
                continue;
            }
            PushTarget target = info.getPushSpec().getTarget();
            if (!(target instanceof GitPushTarget gitTarget)) {
                continue;
            }
            for (String url : gitTarget.getBranch().getRemote().getUrls()) {
                if (!Materializer.isLocalPath(url)) {
                    String remote = gitTarget.getBranch().getRemote().getName();
                    ApplicationManager.getApplication().invokeLater(() ->
                            LeaseNotifier.warn(project,
                                    "Sibling push to a remote remote refused",
                                    workingSet + ": '" + remote + "' is " + url
                                            + ", but a sibling chains through "
                                            + "its local parent "
                                            + "(ike-issues#992). Finish with "
                                            + "the ws: goals; the parent's own "
                                            + "ws:push externalizes. If this "
                                            + "sibling still carries legacy "
                                            + "origins, repair them from the "
                                            + "materialization notification "
                                            + "or `MaterializeCli repair`."));
                    return Result.ABORT;
                }
            }
        }
        return Result.OK;
    }

    /**
     * Resolves a repository root to the sibling working set containing it.
     *
     * @param ikeDev the development-folder root
     * @param root   the repository root being pushed
     * @return the sibling working-set name, or {@code null} when the root
     *         is outside the development folder or under a root working set
     */
    private static String siblingWorkingSetOf(Path ikeDev, VirtualFile root) {
        Path repoRoot = Path.of(root.getPath()).toAbsolutePath().normalize();
        if (!repoRoot.startsWith(ikeDev)
                || repoRoot.getNameCount() <= ikeDev.getNameCount()) {
            return null;
        }
        String segment = repoRoot.getName(ikeDev.getNameCount()).toString();
        try {
            WorkingSetName name = new WorkingSetName(segment);
            return name.isSibling() ? name.value() : null;
        } catch (IllegalArgumentException e) {
            return null;    // not a working-set shape; not our business
        }
    }
}
