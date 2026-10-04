package network.ike.lease.core;

/**
 * The {@code state:} field of a lease record — what the last writer did
 * with the working set (IKE-Network/ike-issues#1215).
 *
 * <p>The vocabulary is a library loan: a machine <em>takes</em> a working
 * set, <em>renews</em> it while working, and <em>returns</em> it when done.
 * Before ike-lease 7 a returned record said {@code released}, which
 * collided with releasing software; records sync across the whole fleet and
 * older cores keep writing that spelling until every machine is updated, so
 * {@link #parse} reads both. A core of this vintage only ever writes
 * {@code returned}.
 */
public enum RecordState {

    /** Taken by the record's holder and not yet returned. */
    HELD("held"),

    /** Returned by the record's holder; the working set is free. */
    RETURNED("returned"),

    /**
     * Anything else, including a missing field. Kept distinct so a record
     * this core cannot interpret is never mistaken for a returned one —
     * the protocol treats it as held by its holder, the safe reading.
     */
    UNKNOWN("");

    /** The spelling cores before ike-lease 7 wrote for {@link #RETURNED}. */
    static final String LEGACY_RETURNED = "released";

    private final String spelling;

    RecordState(String spelling) {
        this.spelling = spelling;
    }

    /**
     * Returns the on-disk spelling this core writes.
     *
     * @return {@code held}, {@code returned}, or the empty string for
     *         {@link #UNKNOWN}
     */
    public String spelling() {
        return spelling;
    }

    /**
     * Reads a record's {@code state:} value.
     *
     * @param value the raw field value, possibly {@code null}
     * @return {@link #HELD} for {@code held}; {@link #RETURNED} for
     *         {@code returned} or the legacy {@code released};
     *         {@link #UNKNOWN} otherwise
     */
    public static RecordState parse(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        return switch (value.trim()) {
            case "held" -> HELD;
            case "returned", LEGACY_RETURNED -> RETURNED;
            default -> UNKNOWN;
        };
    }
}
