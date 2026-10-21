package network.ike.lease;

import com.intellij.openapi.compiler.CompilationStatusListener;
import com.intellij.openapi.compiler.CompileContext;

/**
 * The backstop for {@link BuildLockAfterCompile} (IKE-Network/ike-issues#1315).
 * IntelliJ skips the after-compile tasks when a later before-compile task
 * refuses or the compile is cancelled mid-way, but it always reports the
 * compile finished; this gives the lock back then. Idempotent: normally
 * the after-compile task has already released it.
 */
public final class BuildLockCompilationListener implements CompilationStatusListener {

    /** Creates the listener; the platform instantiates this reflectively. */
    public BuildLockCompilationListener() { }

    @Override
    public void compilationFinished(boolean aborted, int errors, int warnings, CompileContext context) {
        BuildLockService.getInstance().releaseInBackground(context.getProject(), context);
    }
}
