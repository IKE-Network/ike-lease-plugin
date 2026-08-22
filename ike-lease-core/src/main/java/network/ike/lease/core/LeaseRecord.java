package network.ike.lease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One working-set lease record — the flat-scalar file in
 * {@code leases/<ws>.lease} that is simultaneously the grant and the bus
 * (IKE-Network/ike-issues#1002).
 *
 * <p>This is the protocol port's on-disk contract
 * (IKE-Network/ike-issues#1067): parsing accepts exactly what
 * {@code lease.sh} v2 wrote, and {@link #serialize} reproduces its output
 * byte for byte — header comments, field order, trailing newline — so a
 * mixed fleet interoperates mid-rollout and the golden tests can compare
 * files literally.
 *
 * @param workingSet the working-set directory name
 * @param state      {@code held} or {@code released}
 * @param holder     the machine id that wrote the record
 * @param epoch      the monotonic fencing token
 * @param acquired   ISO-8601 UTC acquisition stamp
 * @param renewed    ISO-8601 UTC renewal stamp
 * @param ttl        the staleness horizon, ISO-8601 duration
 */
public record LeaseRecord(String workingSet, String state, String holder,
                          long epoch, String acquired, String renewed,
                          String ttl) {

    /**
     * Reads a field the way the shell did: the line whose first
     * whitespace-delimited token is {@code key:}, value = the remaining
     * tokens joined by single spaces, first match wins.
     *
     * @param lines the record's lines
     * @param key   the field key, without the colon
     * @return the value, or empty when no line carries the key
     */
    public static Optional<String> field(Iterable<String> lines, String key) {
        String token = key + ":";
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length > 0 && parts[0].equals(token)) {
                return Optional.of(String.join(" ",
                        java.util.Arrays.copyOfRange(parts, 1, parts.length)));
            }
        }
        return Optional.empty();
    }

    /**
     * Parses a record file.
     *
     * @param file the {@code .lease} file
     * @return the record, or empty when the file does not exist or cannot
     *         be read
     */
    public static Optional<LeaseRecord> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            java.util.List<String> lines =
                    Files.readAllLines(file, StandardCharsets.UTF_8);
            String name = file.getFileName().toString();
            String fallbackWs = name.endsWith(".lease")
                    ? name.substring(0, name.length() - ".lease".length())
                    : name;
            return Optional.of(new LeaseRecord(
                    field(lines, "working-set").orElse(fallbackWs),
                    field(lines, "state").orElse(""),
                    field(lines, "holder").orElse(""),
                    parseEpoch(field(lines, "epoch").orElse("0")),
                    field(lines, "acquired").orElse(""),
                    field(lines, "renewed").orElse(""),
                    field(lines, "ttl").orElse("")));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Serializes exactly as {@code lease.sh} v2 wrote — the byte-for-byte
     * contract that keeps a mixed fleet coherent.
     *
     * @return the file content, trailing newline included
     */
    public String serialize() {
        return "# Working-set lease — written by scripts/lease.sh.\n"
                + "# Holder has sole write access; see IKE-Network/ike-issues#1002.\n"
                + "working-set: " + workingSet + "\n"
                + "state: " + state + "\n"
                + "holder: " + holder + "\n"
                + "epoch: " + epoch + "\n"
                + "acquired: " + acquired + "\n"
                + "renewed: " + renewed + "\n"
                + "ttl: " + ttl + "\n";
    }

    private static long parseEpoch(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
