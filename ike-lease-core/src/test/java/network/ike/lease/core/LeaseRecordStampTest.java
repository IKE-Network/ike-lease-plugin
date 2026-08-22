package network.ike.lease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stamp tail of the record format (IKE-Network/ike-issues#1069):
 * stamped records round-trip, stampless records stay byte-identical to
 * v2, and nothing malformed can make a record unreadable.
 */
class LeaseRecordStampTest {

    private static final String SHA_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @TempDir
    Path tempDir;

    @Test
    void stampedRecordRoundTripsThroughDisk() throws IOException {
        LeaseRecord written = new LeaseRecord("my-ws", "held", "Machine-A",
                7, "2026-08-22T10:00:00Z", "2026-08-22T10:05:00Z", "PT10M",
                List.of(new RepoStamp(".", "main", SHA_A),
                        new RepoStamp("member", "feature/x", SHA_B)));
        Path file = tempDir.resolve("my-ws.lease");
        Files.writeString(file, written.serialize(), StandardCharsets.UTF_8);

        LeaseRecord read = LeaseRecord.read(file).orElseThrow();

        assertEquals(written, read);
        assertEquals(List.of(new RepoStamp(".", "main", SHA_A),
                        new RepoStamp("member", "feature/x", SHA_B)),
                read.stamps());
    }

    @Test
    void stamplessRecordSerializesByteIdenticallyToV2() {
        LeaseRecord record = new LeaseRecord("my-ws", "held", "Machine-A",
                7, "2026-08-22T10:00:00Z", "2026-08-22T10:05:00Z", "PT10M");
        String expected =
                "# Working-set lease — written by scripts/lease.sh.\n"
                + "# Holder has sole write access; see IKE-Network/ike-issues#1002.\n"
                + "working-set: my-ws\n"
                + "state: held\n"
                + "holder: Machine-A\n"
                + "epoch: 7\n"
                + "acquired: 2026-08-22T10:00:00Z\n"
                + "renewed: 2026-08-22T10:05:00Z\n"
                + "ttl: PT10M\n";
        assertEquals(expected, record.serialize(),
                "the v2 byte contract must hold without stamps");
    }

    @Test
    void malformedStampLinesAreIgnoredNotFatal() throws IOException {
        Path file = tempDir.resolve("my-ws.lease");
        Files.writeString(file,
                "working-set: my-ws\n"
                + "state: held\n"
                + "holder: Machine-A\n"
                + "epoch: 3\n"
                + "acquired: 2026-08-22T10:00:00Z\n"
                + "renewed: 2026-08-22T10:05:00Z\n"
                + "ttl: PT10M\n"
                + "stamp: only-two-tokens\n"
                + "stamp: one two three four five\n"
                + "stamp: . main " + SHA_A + "\n"
                + "some-future-key: whatever\n",
                StandardCharsets.UTF_8);

        LeaseRecord read = LeaseRecord.read(file).orElseThrow();

        assertEquals("held", read.state());
        assertEquals(3, read.epoch());
        assertEquals(List.of(new RepoStamp(".", "main", SHA_A)),
                read.stamps(),
                "only the well-formed stamp survives; nothing breaks");
    }

    @Test
    void preStampReaderSemanticsIgnoreStampLines() {
        // field() is the v2 read path every pre-stamp core uses: a
        // stamped record must answer exactly as an unstamped one does.
        List<String> lines = new LeaseRecord("my-ws", "held", "Machine-A",
                7, "2026-08-22T10:00:00Z", "2026-08-22T10:05:00Z", "PT10M",
                List.of(new RepoStamp(".", "main", SHA_A)))
                .serialize().lines().toList();
        assertEquals("held",
                LeaseRecord.field(lines, "state").orElseThrow());
        assertEquals("Machine-A",
                LeaseRecord.field(lines, "holder").orElseThrow());
        assertTrue(LeaseRecord.field(lines, "nonexistent").isEmpty());
    }
}
