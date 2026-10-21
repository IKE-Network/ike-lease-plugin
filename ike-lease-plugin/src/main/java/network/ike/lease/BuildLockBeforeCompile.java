package network.ike.lease;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationAction;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompileTask;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import network.ike.lease.core.BuildLock;
import network.ike.lease.core.BuildLockClient;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Takes the working set's build lock before IntelliJ's own compiler writes
 * into its {@code target/} directories (IKE-Network/ike-issues#1315).
 *
 * <p>The extension's Maven builds hold the same lock, so a compile started
 * while {@code ./mvnw} builds the tree waits for it, and a Maven build
 * started while the IDE compiles waits for the compile. The compile itself
 * is IntelliJ's, native and unchanged — nothing is delegated to Maven, so
 * the edit, compile, hot-swap cycle stays as fast as it was. The lock is
 * held for the compile only: {@link BuildLockAfterCompile} gives it back
 * as soon as the compiler finishes, before any run or debug session it
 * preceded starts, and {@link BuildLockCompilationListener} gives it back
 * on the paths that skip the after-compile tasks.
 *
 * <p>Runs on the compile's background thread, under the compile's own
 * progress indicator: waiting shows the holder there, cancelling the
 * compile stops the wait, and a notification offers to build anyway.
 */
public final class BuildLockBeforeCompile implements CompileTask {

    private static final Logger LOG = Logger.getInstance(BuildLockBeforeCompile.class);

    /** How long a wait runs before the notification offering to build anyway appears. */
    private static final Duration NOTIFY_AFTER = Duration.ofSeconds(1);

    /** Module names listed in the lock file before the rest are counted instead. */
    private static final int MODULES_SHOWN = 6;

    /** Creates the task; the platform instantiates this reflectively. */
    public BuildLockBeforeCompile() { }

    @Override
    public boolean execute(CompileContext context) {
        Project project = context.getProject();
        BuildLockService service = BuildLockService.getInstance();
        Optional<Path> tree = service.treeFor(project);
        if (tree.isEmpty()) {
            return true;
        }
        Path root = tree.get();
        String goals = "IntelliJ " + (context.isRebuild() ? "rebuild" : context.isMake() ? "make" : "compile");
        ProgressIndicator indicator = context.getProgressIndicator();
        Waiter waiter = new Waiter(project, indicator, root);
        BuildLockClient.Outcome outcome;
        indicator.pushState();
        try {
            outcome = service.acquire(project, context, root, goals, modules(context), waiter);
        } finally {
            waiter.done();
            indicator.popState();
        }
        Instant now = Instant.now();
        return switch (outcome) {
            case BuildLockClient.Acquired acquired -> {
                LOG.info("IKE build lock " + acquired.lockFile() + " taken for " + goals
                        + (acquired.holder().isPresent()
                                ? " after waiting " + BuildLock.brief(acquired.waited()) + " for "
                                        + who(acquired.holder(), now)
                                : ""));
                if (!acquired.waited().minus(NOTIFY_AFTER).isNegative()) {
                    context.addMessage(CompilerMessageCategory.INFORMATION, "IKE build lock taken after waiting "
                            + BuildLock.brief(acquired.waited()) + " for " + who(acquired.holder(), now),
                            null, -1, -1);
                }
                yield true;
            }
            case BuildLockClient.Overridden overridden -> {
                String text = "Compiling without the build lock while " + who(overridden.holder(), now)
                        + " holds " + overridden.lockFile() + ". The two builds may overwrite each other's "
                        + "classes; rebuild if anything looks stale.";
                context.addMessage(CompilerMessageCategory.WARNING, text, null, -1, -1);
                LeaseProjectListener.LeaseNotifier.warn(project, "Compiling without the build lock", text);
                yield true;
            }
            case BuildLockClient.Cancelled cancelled -> {
                context.addMessage(CompilerMessageCategory.INFORMATION, "Compile cancelled after waiting "
                        + BuildLock.brief(cancelled.waited()) + " for the build lock held by "
                        + who(cancelled.holder(), now), null, -1, -1);
                yield false;
            }
            case BuildLockClient.Unavailable unavailable -> {
                LOG.warn("IKE build lock " + unavailable.lockFile() + " unavailable, compiling unlocked: "
                        + unavailable.reason());
                context.addMessage(CompilerMessageCategory.WARNING, "IKE build lock unavailable, compiling "
                        + "unlocked: " + unavailable.reason(), null, -1, -1);
                yield true;
            }
        };
    }

    /** The modules a compile covers, as the lock file's {@code projects} shows them. */
    private static String modules(CompileContext context) {
        List<String> names = ReadAction.compute(() -> Arrays.stream(context.getCompileScope().getAffectedModules())
                .map(Module::getName).sorted().toList());
        if (names.size() <= MODULES_SHOWN) {
            return String.join(",", names);
        }
        return String.join(",", names.subList(0, MODULES_SHOWN)) + ",+" + (names.size() - MODULES_SHOWN)
                + " more";
    }

    private static String who(Optional<BuildLock.Holder> holder, Instant now) {
        return holder.map(h -> h.describe(now)).orElse("another build");
    }

    /** Shows the wait on the compile's indicator and offers to build anyway. */
    private static final class Waiter implements BuildLockClient.Waiter {
        private final Project project;
        private final ProgressIndicator indicator;
        private final Path root;
        private final AtomicBoolean buildAnyway = new AtomicBoolean();
        private Notification notification;
        private boolean announced;

        Waiter(Project project, ProgressIndicator indicator, Path root) {
            this.project = project;
            this.indicator = indicator;
            this.root = root;
        }

        @Override
        public void waiting(Optional<BuildLock.Holder> holder, Duration waited) {
            Instant now = Instant.now();
            indicator.setText("Waiting for the build lock: " + who(holder, now));
            indicator.setText2("Waited " + BuildLock.brief(waited)
                    + ". Cancel the compile, or choose Build anyway in the notification.");
            if (!announced) {
                announced = true;
                LOG.info("IKE build lock: compile of " + project.getName() + " waiting for "
                        + who(holder, now) + " to finish building " + root);
            }
            if (notification == null && !waited.minus(NOTIFY_AFTER).isNegative()) {
                notification = NotificationGroupManager.getInstance()
                        .getNotificationGroup("IKE Working-Set Leases")
                        .createNotification("Compile waiting for the build lock",
                                who(holder, now) + " is building " + root.getFileName()
                                        + ". This compile starts when it finishes.",
                                NotificationType.INFORMATION);
                notification.addAction(NotificationAction.createSimpleExpiring("Build anyway",
                        () -> buildAnyway.set(true)));
                notification.notify(project);
            }
        }

        @Override
        public boolean cancelled() {
            return indicator.isCanceled();
        }

        @Override
        public boolean buildAnyway() {
            return buildAnyway.get();
        }

        void done() {
            if (notification != null) {
                notification.expire();
            }
        }
    }
}
