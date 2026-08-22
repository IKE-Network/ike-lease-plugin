package network.ike.lease;

/**
 * How this machine currently stands with respect to a working set's lease.
 *
 * <p>The distinction that matters is {@link #EXPIRED} versus {@link #LIVE}:
 * both are held by another machine, but an expired lease is reclaimable in
 * silence while a live one requires the operator's decision. That is the
 * whole job of the time-to-live — it is a staleness horizon, never a wait.
 */
public enum LeaseState {

    /** No record, or explicitly released: take it silently. */
    FREE,

    /** Held by this machine. */
    MINE,

    /** Held elsewhere but not renewed within its time-to-live: reclaimable. */
    EXPIRED,

    /** Held elsewhere and fresh: takeover is the operator's decision. */
    LIVE,

    /** State could not be determined; callers should not fence on this. */
    UNKNOWN;

    /**
     * Derives the state from the lease script's description line.
     *
     * @param description a line such as {@code "ws: HELD by machine-id (…)"}
     * @return the corresponding state, or {@link #UNKNOWN} when unrecognised
     */
    public static LeaseState fromDescription(String description) {
        if (description == null) {
            return UNKNOWN;
        }
        if (description.contains(": MINE")) {
            return MINE;
        }
        if (description.contains(": FREE")) {
            return FREE;
        }
        if (description.contains(": EXPIRED")) {
            return EXPIRED;
        }
        if (description.contains(": HELD by")) {
            return LIVE;
        }
        return UNKNOWN;
    }

    /**
     * Reports whether this machine may write to the working set.
     *
     * @return {@code true} only when this machine holds the lease
     */
    public boolean permitsWriting() {
        return this == MINE;
    }
}
