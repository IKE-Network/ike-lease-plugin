package network.ike.lease;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;

/**
 * Watches the synchronized lease directory and stands this machine down
 * when it is fenced.
 *
 * <p>This is the half of the protocol that makes takeover work without any
 * machine-to-machine call: the lease record arrives through file sync, and
 * observing that a project this machine has open is now held by someone
 * else is the whole signal. The response is to save every document first,
 * then close the project — so the work in flight reaches the taking
 * machine rather than being stranded behind an unsaved buffer.
 */
@Service(Service.Level.APP)
public final class LeaseWatcher implements Disposable {

    private static final long POLL_SECONDS = 2L;

    /**
     * How often open projects re-assert their leases. Comfortably inside
     * the lease time-to-live so a renewal is never the thing that runs
     * late; the lease script itself only rewrites the record at half-life,
     * so sweeping more often than that costs nothing.
     */
    private static final long SWEEP_INTERVAL_NANOS = 60L * 1_000_000_000L;

    private volatile boolean running = true;
    private Thread thread;

    /** Creates the service; the platform instantiates this on first use. */
    public LeaseWatcher() { }

    /**
     * Starts watching the lease directory for records that fence this
     * machine. Safe to call more than once; only the first call starts a
     * watcher thread.
     */
    public synchronized void start() {
        if (thread != null) {
            return;
        }
        LeaseCli lease = LeaseCli.forCurrentUser();
        if (!lease.isAvailable()) {
            return;
        }
        thread = new Thread(() -> watch(lease), "ike-lease-watcher");
        thread.setDaemon(true);
        thread.start();
    }

    private void watch(LeaseCli lease) {
        Path leaseDir = lease.leaseDirectory();
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            leaseDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            long lastSweep = 0L;
            while (running) {
                WatchKey key = watchService.poll(POLL_SECONDS, TimeUnit.SECONDS);
                if (key != null) {
                    key.pollEvents();
                    key.reset();
                    reconcileOpenProjects(lease);       // react to a fence at once
                    lastSweep = System.nanoTime();
                    continue;
                }
                // A project sitting open with nobody typing in it is still
                // being worked on, and its lease has to say so. Without
                // this sweep the lease simply ages out: an open project
                // was taken by the other machine after ten idle minutes,
                // silently, because an expired lease needs no permission.
                // Renewal is the holder's job in this model, so an open
                // project renews itself.
                long now = System.nanoTime();
                if (now - lastSweep >= SWEEP_INTERVAL_NANOS) {
                    reconcileOpenProjects(lease);
                    lastSweep = now;
                }
            }
        } catch (IOException e) {
            // The directory may not exist until the first lease is written;
            // the service is restarted on the next IDE start.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Closes any open project whose lease has moved to another machine.
     *
     * @param lease the bridge used to read current lease state
     */
    void reconcileOpenProjects(LeaseCli lease) {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            String basePath = project.getBasePath();
            if (basePath == null || project.isDisposed()) {
                continue;
            }
            String workingSet = lease.resolve(Path.of(basePath));
            if (workingSet == null) {
                continue;
            }
            // One call does both jobs: it renews the lease when this
            // machine holds it, reclaims it when it is free or stale, and
            // reports false only when another machine holds it live —
            // which is the one case where this machine must stand down.
            if (!lease.ensure(workingSet)) {
                standDown(project, workingSet, lease);
            }
        }
    }

    private void standDown(Project project, String workingSet, LeaseCli lease) {
        ApplicationManager.getApplication().invokeLater(() -> {
            FileDocumentManager.getInstance().saveAllDocuments();
            LeaseProjectListener.LeaseNotifier.warn(project,
                    "Stood down: " + workingSet,
                    lease.describe(workingSet)
                            + " — documents saved and the project is closing so the "
                            + "holder receives your work.");
            ProjectManager.getInstance().closeAndDispose(project);
        });
    }

    /** Stops the watcher thread. */
    @Override
    public void dispose() {
        running = false;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
