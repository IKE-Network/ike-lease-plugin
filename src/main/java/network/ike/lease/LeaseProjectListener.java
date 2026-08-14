package network.ike.lease;

import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.Messages;

import java.nio.file.Path;

/**
 * Makes opening a project the act of acquiring its lease.
 *
 * <p>There is no separate "claim this working set" gesture to remember:
 * opening the project is the claim. A free or expired lease is taken
 * silently; a lease held live by another machine raises the takeover
 * dialog, because displacing a live holder is the operator's decision on
 * every surface — this one and the Claude hook alike.
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

        if (lease.ensure(workingSet)) {
            LeaseNotifier.info(project, "Lease acquired",
                    workingSet + " is now held by " + lease.machineId() + ".");
            return;
        }

        // Held live elsewhere. Ask, on the EDT, then fence or take over.
        ApplicationManager.getApplication().invokeLater(() -> {
            String detail = lease.describe(workingSet);
            int choice = Messages.showYesNoDialog(project,
                    detail + "\n\n"
                            + "Another machine holds this working set. Taking it over "
                            + "closes the project there, after saving its work, and "
                            + "advances the fencing epoch so that machine stands down.\n\n"
                            + "Take over?",
                    "Working Set Is Leased Elsewhere",
                    "Take Over", "Work Read-Only", Messages.getQuestionIcon());
            if (choice == Messages.YES && lease.forceAcquire(workingSet)) {
                LeaseNotifier.info(project, "Lease taken over",
                        workingSet + " is now held by " + lease.machineId()
                                + ". The other machine will save and close it.");
            } else {
                LeaseNotifier.warn(project, "Working set leased elsewhere",
                        "Edits here risk colliding with the holder. "
                                + detail);
            }
        });
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
