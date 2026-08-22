package network.ike.lease.core;

import java.util.List;

/**
 * The outcome of materializing or verifying one working set, one entry
 * per repository the operation considered.
 *
 * @param workingSet the working set the operation ran against
 * @param entries    the per-repository outcomes, in the order acted on
 */
public record MaterializeReport(WorkingSetName workingSet,
                                List<Entry> entries) {

    /** What happened to one repository. */
    public enum Action {
        /** Git state was created for a bare tree. */
        MATERIALIZED,
        /** Git state already existed; nothing was done. */
        ALREADY_MATERIALIZED,
        /** The synced tree is not present on disk yet; skipped. */
        NO_TREE,
        /** No git state and this operation does not create it. */
        NO_GIT,
        /** A sibling repository whose origin is a local parent path. */
        LOCAL_ORIGIN_OK,
        /** A sibling repository still carrying a remote-remote origin —
         *  the pre-IKE-Network/ike-issues#992 shape, awaiting migration. */
        REMOTE_ORIGIN_LEGACY,
        /** The operation could not proceed for this repository. */
        REFUSED
    }

    /**
     * One repository's outcome.
     *
     * @param path   the repository path relative to {@code ~/ike-dev}
     * @param action what happened
     * @param detail the origin and branch on success, or the reason and
     *               remedy on refusal; empty when the action says it all
     */
    public record Entry(String path, Action action, String detail) {

        /**
         * Renders the entry as one report line.
         *
         * @return {@code <action> <path>} with the detail appended when
         *         present
         */
        public String render() {
            String suffix = detail.isEmpty() ? "" : " — " + detail;
            return action + " " + path + suffix;
        }
    }

    /**
     * Reports whether the operation completed without refusals.
     *
     * @return {@code true} when no entry is {@link Action#REFUSED}
     */
    public boolean ok() {
        return entries.stream().noneMatch(
                entry -> entry.action() == Action.REFUSED);
    }

    /**
     * Counts the entries with the given action.
     *
     * @param action the action to count
     * @return how many entries carry it
     */
    public long count(Action action) {
        return entries.stream().filter(
                entry -> entry.action() == action).count();
    }
}
