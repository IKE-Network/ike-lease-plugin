package network.ike.lease;

import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompileTask;

/**
 * Gives the build lock back the moment IntelliJ's compiler finishes, failed
 * compiles included (IKE-Network/ike-issues#1315). It runs before a run or
 * debug session the compile preceded starts, so a running application
 * never holds the tree.
 */
public final class BuildLockAfterCompile implements CompileTask {

    /** Creates the task; the platform instantiates this reflectively. */
    public BuildLockAfterCompile() { }

    @Override
    public boolean execute(CompileContext context) {
        BuildLockService.getInstance().release(context.getProject(), context);
        return true;
    }
}
