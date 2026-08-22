package network.ike.lease.core;

/**
 * A working-set directory name, classified.
 *
 * <p>A sibling working set is named {@code <parent>꞉<feature>} — the
 * separator is U+A789 MODIFIER LETTER COLON, filesystem-legal where a
 * plain colon is not — and its feature branch is {@code feature/<feature>},
 * exactly the derivation {@code ws:feature-start-sibling} uses at creation
 * (IKE-Network/ike-issues#992). Everything else is a root working set.
 *
 * @param value the directory name under {@code ~/ike-dev}
 */
public record WorkingSetName(String value) {

    /** The sibling separator, U+A789 MODIFIER LETTER COLON. */
    public static final char SEPARATOR = '꞉';

    /**
     * Validates the name.
     *
     * @param value the directory name under {@code ~/ike-dev}
     * @throws IllegalArgumentException if the name is blank, is a path
     *                                  rather than a single directory name,
     *                                  or has an empty parent or feature
     *                                  half around the sibling separator
     */
    public WorkingSetName {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("working-set name is blank");
        }
        if (value.contains("/") || value.contains("\\")) {
            throw new IllegalArgumentException(
                    "working-set name is a single directory name, not a path: "
                            + value);
        }
        int separator = value.indexOf(SEPARATOR);
        if (separator == 0 || separator == value.length() - 1) {
            throw new IllegalArgumentException(
                    "sibling name needs a parent and a feature around '"
                            + SEPARATOR + "': " + value);
        }
    }

    /**
     * Reports whether this names a sibling working set.
     *
     * @return {@code true} when the name contains the sibling separator
     */
    public boolean isSibling() {
        return value.indexOf(SEPARATOR) >= 0;
    }

    /**
     * Returns the parent working set's name.
     *
     * @return the name before the sibling separator
     * @throws IllegalStateException if this is not a sibling
     */
    public WorkingSetName parent() {
        return new WorkingSetName(value.substring(0, separatorIndex()));
    }

    /**
     * Returns the feature half of a sibling name.
     *
     * @return the name after the sibling separator
     * @throws IllegalStateException if this is not a sibling
     */
    public String feature() {
        return value.substring(separatorIndex() + 1);
    }

    /**
     * Returns the sibling's feature branch.
     *
     * @return {@code feature/<feature>}, the creation-time derivation
     * @throws IllegalStateException if this is not a sibling
     */
    public String featureBranch() {
        return "feature/" + feature();
    }

    private int separatorIndex() {
        int separator = value.indexOf(SEPARATOR);
        if (separator < 0) {
            throw new IllegalStateException(
                    value + " is a root working set, not a sibling");
        }
        return separator;
    }
}
