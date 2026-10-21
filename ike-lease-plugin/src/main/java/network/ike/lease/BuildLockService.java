package network.ike.lease;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import network.ike.lease.core.BuildLock;
import network.ike.lease.core.BuildLockClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The IDE's holder of per-tree build locks (IKE-Network/ike-issues#1315):
 * one {@link BuildLockClient}, and with it one helper process, for the
 * whole IDE, and a note of which project's compile holds which tree.
 *
 * <p>Fails open, as the lease fence does: if the helper cannot be started,
 * compiles proceed unlocked.
 */
@Service(Service.Level.APP)
public final class BuildLockService implements Disposable {

    private static final Logger LOG = Logger.getInstance(BuildLockService.class);

    /** This plugin's id, as plugin.xml declares it. */
    private static final String PLUGIN_ID = "network.ike.lease";

    /** The tree a project's compile holds, and which compile holds it. */
    private record Hold(Path root, Object compile) { }

    private final Map<Project, Hold> held = new ConcurrentHashMap<>();
    private BuildLockClient client;

    /** Creates the service; the platform instantiates this on first use. */
    public BuildLockService() { }

    /**
     * The application's instance.
     *
     * @return the service
     */
    public static BuildLockService getInstance() {
        return ApplicationManager.getApplication().getService(BuildLockService.class);
    }

    /**
     * The tree a project's compiles lock, when the setting is on and the
     * tree's Maven builds take the lock too.
     *
     * @param project the project about to compile
     * @return the tree's root, or empty to compile unlocked
     */
    public Optional<Path> treeFor(Project project) {
        String base = project.getBasePath();
        if (base == null || !BuildLockSettings.getInstance().isEnabled()) {
            return Optional.empty();
        }
        return BuildLock.treeRoot(Path.of(base));
    }

    /**
     * Takes a tree's lock for a project's compile, waiting as the waiter allows.
     *
     * @param project  the compiling project
     * @param compile  the compile taking it (its context), which alone may release it
     * @param root     the tree
     * @param goals    what the compile is, as Maven will be shown it
     * @param projects the modules it compiles, or empty
     * @param waiter   told of the wait, and asked whether to stop
     * @return how it came out
     */
    public BuildLockClient.Outcome acquire(Project project, Object compile, Path root, String goals,
                                           String projects,
                                           BuildLockClient.Waiter waiter) {
        BuildLockClient lockClient = client();
        if (lockClient == null) {
            return new BuildLockClient.Unavailable(BuildLock.lockFile(root), "the build-lock helper cannot start");
        }
        BuildLockClient.Outcome outcome = lockClient.acquire(root, goals, projects, waiter);
        if (outcome instanceof BuildLockClient.Acquired) {
            held.put(project, new Hold(root, compile));
        }
        return outcome;
    }

    /**
     * Gives back the lock a compile holds, if it still holds one. Idempotent,
     * so every end-of-compile path may call it; a later compile's hold is
     * never released by an earlier compile's late report.
     *
     * @param project the project whose compile ended
     * @param compile the compile that ended (its context)
     */
    public void release(Project project, Object compile) {
        Hold hold = held.get(project);
        if (hold != null && hold.compile() == compile && held.remove(project, hold)) {
            release(project, hold.root());
        }
    }

    /**
     * Gives back whatever lock a project holds, as it closes.
     *
     * @param project the closing project
     */
    public void releaseAll(Project project) {
        Hold hold = held.remove(project);
        if (hold != null) {
            release(project, hold.root());
        }
    }

    private void release(Project project, Path root) {
        BuildLockClient lockClient = client();
        if (lockClient != null && lockClient.release(root)) {
            LOG.info("IKE build lock " + BuildLock.lockFile(root) + " released by " + project.getName());
        }
    }


    /**
     * Releases a compile's lock off the calling thread, for callers on the
     * event dispatch thread.
     *
     * @param project the project whose compile ended
     * @param compile the compile that ended, or {@code null} for whatever
     *                the project holds (the project is closing)
     */
    public void releaseInBackground(Project project, Object compile) {
        Hold hold = held.get(project);
        if (hold != null && (compile == null || hold.compile() == compile)) {
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                if (compile == null) {
                    releaseAll(project);
                } else {
                    release(project, compile);
                }
            });
        }
    }

    private synchronized BuildLockClient client() {
        if (client == null) {
            List<String> command = helperCommand();
            if (command == null) {
                return null;
            }
            client = new BuildLockClient(command);
        }
        return client;
    }

    /** The helper's command: from this class's jar, or failing that the plugin's lib directory. */
    private static List<String> helperCommand() {
        try {
            return BuildLockClient.defaultHelperCommand();
        } catch (IllegalStateException e) {
            IdeaPluginDescriptor plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
            if (plugin != null) {
                try (Stream<Path> jars = Files.list(plugin.getPluginPath().resolve("lib"))) {
                    Optional<Path> core = jars.filter(jar -> jar.getFileName().toString()
                            .startsWith("ike-lease-core-")).findFirst();
                    if (core.isPresent()) {
                        return BuildLockClient.helperCommand(core.get());
                    }
                } catch (IOException ignored) {
                    // Reported below.
                }
            }
            LOG.warn("IKE build lock: cannot locate the lease core for the helper; compiles run unlocked", e);
            return null;
        }
    }

    @Override
    public synchronized void dispose() {
        held.clear();
        if (client != null) {
            client.close();
        }
    }
}
