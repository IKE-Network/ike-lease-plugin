package network.ike.lease;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.Messages;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

/**
 * Makes opening a project the act of acquiring its lease.
 *
 * <p>There is no separate "claim this working set" gesture to remember:
 * opening the project is the claim. A free or expired lease is taken
 * silently; a lease held live by another machine raises the takeover
 * dialog, because displacing a live holder is the operator's decision on
 * every surface — this one and the Claude hook alike.
 *
 * <p>Opening a project is a <em>consequential</em> acquisition, so it takes
 * the confirmed path: write, wait out the sync layer's propagation window,
 * reconcile, and verify this machine is still the holder. Two machines
 * acquiring the same free lease inside that window both succeed and the
 * loser is never told, so without the read-back the IDE would open on a
 * working set another machine owns. The wait costs about 25 seconds and
 * runs in the background, alongside indexing, where it is invisible.
 */
public final class LeaseProjectListener implements ProjectManagerListener {

    /** Creates the listener; the platform instantiates this reflectively. */
    public LeaseProjectListener() { }

    /**
     * Acquires, or offers to take over, the lease for the opened project.
     *
     * @param project the project the platform has just opened
     */
    @Override
    public void projectOpened(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return;
        }
        LeaseCli lease = LeaseCli.forCurrentUser();
        if (!lease.isAvailable()) {
            return;
        }
        // Start the watcher here rather than from a startup activity:
        // StartupActivity is deprecated (the platform logs "Migrate … to
        // ProjectActivity" on every open) and its replacement is a Kotlin
        // coroutine interface that Java cannot implement cleanly. The
        // watcher is application-level and idempotent, so driving it from
        // the first project open is equivalent and keeps the log quiet.
        ApplicationManager.getApplication().getService(LeaseWatcher.class).start();
        String workingSet = lease.resolve(Path.of(basePath));
        if (workingSet == null) {
            return;     // not a working set under ike-dev; not our business
        }

        // Already held live elsewhere: there is nothing to confirm, and
        // making the operator wait out a settle window for an answer we
        // already have would be pure delay. Go straight to the decision.
        if (lease.status(workingSet) == LeaseState.LIVE) {
            ApplicationManager.getApplication().invokeLater(
                    () -> askToTakeOver(project, lease, workingSet, false));
            return;
        }

        // projectOpened runs on the event dispatch thread and the confirmed
        // acquisition deliberately sleeps out the propagation window, so it
        // has to happen off it — with a visible progress indication, or a
        // silent 25-second pause reads as a hang.
        ProgressManager.getInstance().run(new Task.Backgroundable(project,
                "Confirming working-set lease: " + workingSet, false) {
            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                indicator.setIndeterminate(true);
                indicator.setText("Waiting for the sync layer to settle…");
                boolean held = lease.ensureConfirmed(workingSet);
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (held) {
                        LeaseNotifier.info(project, "Lease acquired",
                                workingSet + " is now held by " + lease.machineId() + ".");
                    } else {
                        // The claim was written and then lost: another
                        // machine's acquisition landed inside the window.
                        // Same decision as any live lease, different story,
                        // so the operator is told which one happened.
                        askToTakeOver(project, lease, workingSet, true);
                    }
                });
            }
        });
    }

    /**
     * Puts the takeover decision to the operator and acts on the answer.
     *
     * <p>Displacing a live holder is never automatic — on this surface or
     * the Claude hook — so this is the one path that can call
     * {@link LeaseCli#forceAcquire}.
     *
     * <p>Declining closes the project. The alternative this dialog used to
     * offer, working read-only, was never real: nothing here can stop the
     * IDE writing to disk, and the watcher's sweep closed the project within
     * a minute regardless, so the option promised something neither half of
     * the plugin delivered. A working set you do not hold is one you do not
     * write to, and staying open on it is exactly what the lease exists to
     * prevent. So the choice is honest now: take it over, or close.
     *
     * <p>Must be called on the event dispatch thread.
     *
     * @param project    the project whose working set is leased elsewhere
     * @param lease      the bridge to the lease protocol
     * @param workingSet the working-set directory name
     * @param lostRace   {@code true} when this machine acquired the lease and
     *                   then lost it to a claim that arrived inside the
     *                   propagation window, rather than finding it already held
     */
    private static void askToTakeOver(Project project, LeaseCli lease,
                                      String workingSet, boolean lostRace) {
        String detail = lease.describe(workingSet);
        String preamble = lostRace
                ? "Another machine claimed this working set at the same moment, "
                        + "and its claim won. Your lease here has been superseded."
                : "Another machine holds this working set.";
        int choice = Messages.showYesNoDialog(project,
                detail + "\n\n"
                        + preamble + " Taking it over "
                        + "closes the project there, after saving its work, and "
                        + "advances the fencing epoch so that machine stands down.\n\n"
                        + "Declining closes this project instead, saving your work first.\n\n"
                        + "Take over?",
                lostRace ? "Working Set Was Claimed Elsewhere" : "Working Set Is Leased Elsewhere",
                "Take Over", "Close Project", Messages.getQuestionIcon());
        if (choice == Messages.YES && lease.forceAcquire(workingSet)) {
            LeaseNotifier.info(project, "Lease taken over",
                    workingSet + " is now held by " + lease.machineId()
                            + ". The other machine will save and close it.");
            return;
        }
        // Declined, or the takeover write failed. Either way this machine
        // does not hold the working set, so it stands down the same way the
        // watcher would — immediately, rather than up to a sweep later.
        LeaseWatcher.standDown(project, workingSet, lease);
    }

    /**
     * Releases the lease when the project closes, so the next machine to
     * open it acquires in silence rather than meeting a takeover dialog.
     *
     * @param project the project being closed
     */
    @Override
    public void projectClosed(Project project) {
        String basePath = project.getBasePath();
        if (basePath == null) {
            return;
        }
        LeaseCli lease = LeaseCli.forCurrentUser();
        if (!lease.isAvailable()) {
            return;
        }
        String workingSet = lease.resolve(Path.of(basePath));
        if (workingSet != null && lease.status(workingSet) == LeaseState.MINE) {
            lease.release(workingSet);
        }
    }

    /** Balloon notifications for lease events. */
    static final class LeaseNotifier {

        private LeaseNotifier() { }

        static void info(Project project, String title, String content) {
            notify(project, title, content, NotificationType.INFORMATION);
        }

        static void warn(Project project, String title, String content) {
            notify(project, title, content, NotificationType.WARNING);
        }

        static void notify(Project project, String title, String content,
                           NotificationType type) {
            NotificationGroupManager.getInstance()
                    .getNotificationGroup("IKE Working-Set Leases")
                    .createNotification(title, content, type)
                    .notify(project);
        }
    }
}
