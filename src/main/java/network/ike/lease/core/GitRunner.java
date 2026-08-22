package network.ike.lease.core;

import java.nio.file.Path;
import java.util.List;

/**
 * The seam between the materializer core and a git implementation.
 *
 * <p>The core holds everything decision-shaped and expresses its work as
 * plain git invocations; each host contributes the runner that executes
 * them — the headless CLI wraps the {@code git} executable directly, and
 * the IDE adapter routes through git4idea so credentials, progress and
 * cancellation are the platform's. Keeping this seam narrow is what lets
 * the same core serve both hosts (IKE-Network/ike-issues#1057).
 */
public interface GitRunner {

    /**
     * Runs one git command in the given directory.
     *
     * @param directory the working directory for the invocation
     * @param args      the git arguments, excluding the leading {@code git}
     * @return the completed invocation's exit code and captured output
     */
    GitResult run(Path directory, List<String> args);

    /**
     * The outcome of one git invocation.
     *
     * @param exitCode the process exit code, or {@code -1} when the process
     *                 could not run or was killed on timeout
     * @param stdout   the captured standard output
     * @param stderr   the captured standard error, or a description of the
     *                 launch failure when {@code exitCode} is {@code -1}
     */
    record GitResult(int exitCode, String stdout, String stderr) {

        /**
         * Reports whether the invocation succeeded.
         *
         * @return {@code true} when the exit code is zero
         */
        public boolean ok() {
            return exitCode == 0;
        }

        /**
         * Returns the standard output with surrounding whitespace removed.
         *
         * @return the trimmed standard output
         */
        public String stdoutTrimmed() {
            return stdout.trim();
        }
    }
}
