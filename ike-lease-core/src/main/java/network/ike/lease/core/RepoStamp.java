package network.ike.lease.core;

import java.util.ArrayList;
import java.util.List;

/**
 * One repository's ref position, stamped into a lease record by its
 * holder — the root-ref-alignment bus of IKE-Network/ike-issues#1069.
 *
 * <p>The holder stamps every repository of a held <em>root</em> working
 * set on the writes the protocol already makes (renew at half-life,
 * release); the taker aligns its refs to the stamps at acquisition
 * without touching the tree. Siblings stamp nothing: their branch is the
 * name's {@code ꞉} suffix, their base is the local parent, and the
 * synced tree is authoritative (IKE-Network/ike-issues#992).
 *
 * <p>On disk a stamp is one record line —
 * {@code stamp: <path> <branch> <head>} — appended after the v2 fields.
 * Cores that predate stamps ignore the lines on read and drop them on
 * rewrite; both are tolerated by design, so the stamps are best-effort
 * metadata and never load-bearing for fencing.
 *
 * @param path   the repository path relative to the working-set root,
 *               {@code .} for the root repository itself
 * @param branch the checked-out branch name
 * @param head   the commit hash the branch pointed at
 */
public record RepoStamp(String path, String branch, String head) {

    /** The record-line key, without the colon. */
    public static final String KEY = "stamp";

    /**
     * Renders the record line for this stamp.
     *
     * @return {@code stamp: <path> <branch> <head>}, no newline
     */
    public String serialize() {
        return KEY + ": " + path + " " + branch + " " + head;
    }

    /**
     * Collects every well-formed stamp line, in file order. Lines whose
     * first token is not {@code stamp:} or that do not carry exactly a
     * path, a branch and a head are ignored — a malformed stamp must
     * never make a record unreadable.
     *
     * @param lines the record's lines
     * @return the stamps, possibly empty
     */
    public static List<RepoStamp> parseAll(Iterable<String> lines) {
        List<RepoStamp> stamps = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 4 && parts[0].equals(KEY + ":")) {
                stamps.add(new RepoStamp(parts[1], parts[2], parts[3]));
            }
        }
        return stamps;
    }
}
