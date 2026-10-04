package network.ike.lease.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The library-loan vocabulary of IKE-Network/ike-issues#1215: take, renew,
 * return, recall — with the v2 verbs and the v2 record spelling still
 * understood while the fleet moves over.
 */
class LeaseVocabularyTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final String ME = "Test-Machine-AAAA";
    private static final String OTHER = "Other-Machine-ZZZZ";
    private static final String WS = "my-ws";

    @TempDir
    Path tempDir;

    private Path ikeDev;

    private LeaseProtocol protocol() throws IOException {
        Path home = tempDir.resolve("home");
        ikeDev = home.resolve("ike-dev");
        Files.createDirectories(ikeDev.resolve("leases"));
        Files.createDirectories(ikeDev.resolve(WS));
        Files.writeString(home.resolve(".ike-machine-id"), ME + "\n",
                StandardCharsets.UTF_8);
        return new LeaseProtocol(ikeDev, home, ikeDev, "PT10M", 0);
    }

    @Test
    void bothReturnedSpellingsReadAsReturned() {
        assertEquals(RecordState.RETURNED, RecordState.parse("returned"));
        assertEquals(RecordState.RETURNED, RecordState.parse("released"),
                "records written by cores before ike-lease 7");
        assertEquals(RecordState.HELD, RecordState.parse("held"));
        assertEquals(RecordState.UNKNOWN, RecordState.parse(""));
        assertEquals(RecordState.UNKNOWN, RecordState.parse(null));
        assertEquals(RecordState.UNKNOWN, RecordState.parse("borrowed"),
                "an unknown state is never mistaken for a returned one");
    }

    @Test
    void aLegacyReleasedRecordIsFreeToTake() throws IOException {
        LeaseProtocol protocol = protocol();
        writeRaw("state: released", OTHER, 4, stamp(10));

        assertEquals(0, protocol.take(WS, true, false).exitCode(),
                "a record an older core returned is free, however fresh");
        assertEquals(5, record().epoch());
        assertEquals(RecordState.HELD, record().state());
    }

    @Test
    void takeRefusesALiveLeaseAndNamesTheRecallCommand() throws IOException {
        LeaseProtocol protocol = protocol();
        writeRaw("state: held", OTHER, 4, stamp(10));

        LeaseProtocol.Outcome outcome = protocol.take(WS, false, false);

        assertEquals(1, outcome.exitCode());
        assertTrue(outcome.stderr().contains("lease.sh recall '" + WS + "'"),
                outcome.stderr());
        assertEquals(OTHER, record().holder(), "nothing written");
    }

    @Test
    void recallTakesALiveLeaseAndFencesTheHolder() throws IOException {
        LeaseProtocol protocol = protocol();
        writeRaw("state: held", OTHER, 4, stamp(10));

        LeaseProtocol.Outcome outcome = protocol.recall(WS, false, false);

        assertEquals(0, outcome.exitCode());
        assertTrue(outcome.stdout().startsWith("RECALLED " + WS + " from "
                + OTHER + " (epoch 5)"), outcome.stdout());
        assertEquals(ME, record().holder());
    }

    @Test
    void theCliAcceptsNewVerbsAndV2Aliases() throws IOException {
        LeaseProtocol protocol = protocol();

        assertEquals(0, LeaseProtocolCli.run(protocol, 0,
                new String[] {"take", WS, "--quiet"}));
        assertEquals(RecordState.HELD, record().state());
        assertEquals(0, LeaseProtocolCli.run(protocol, 0,
                new String[] {"return", WS}));
        assertEquals(RecordState.RETURNED, record().state());

        assertEquals(0, LeaseProtocolCli.run(protocol, 0,
                new String[] {"acquire", WS, "--quiet"}), "v2 alias of take");
        assertEquals(RecordState.HELD, record().state());
        assertEquals(0, LeaseProtocolCli.run(protocol, 0,
                new String[] {"release", WS}), "v2 alias of return");
        assertEquals(RecordState.RETURNED, record().state());

        writeRaw("state: held", OTHER, 9, stamp(10));
        assertEquals(1, LeaseProtocolCli.run(protocol, 0,
                new String[] {"take", WS, "--quiet"}), "live elsewhere");
        assertEquals(0, LeaseProtocolCli.run(protocol, 0,
                new String[] {"acquire", WS, "--force", "--quiet"}),
                "v2 alias of recall");
        assertEquals(10, record().epoch());
        assertEquals(ME, record().holder());

        writeRaw("state: held", OTHER, 11, stamp(10));
        assertEquals(0, LeaseProtocolCli.run(protocol, 0,
                new String[] {"recall", WS, "--quiet"}));
        assertEquals(12, record().epoch());
    }

    private void writeRaw(String stateLine, String holder, long epoch,
                          String renewed) throws IOException {
        Files.writeString(ikeDev.resolve("leases/" + WS + ".lease"),
                "working-set: " + WS + "\n" + stateLine + "\nholder: "
                        + holder + "\nepoch: " + epoch + "\nacquired: "
                        + renewed + "\nrenewed: " + renewed + "\nttl: PT10M\n",
                StandardCharsets.UTF_8);
    }

    private LeaseRecord record() {
        return LeaseRecord.read(ikeDev.resolve("leases/" + WS + ".lease"))
                .orElseThrow();
    }

    private static String stamp(long secondsAgo) {
        return ISO.format(Instant.now().minusSeconds(secondsAgo));
    }
}
