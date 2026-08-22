package network.ike.lease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daemon pass (IKE-Network/ike-issues#1006): fenced notification,
 * sync-safe garbage collection, and the non-participant alarm — with the
 * probe and the notifier injected, everything else real files.
 */
class LeaseDaemonTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final String ME = "Test-Machine-AAAA";
    private static final String OTHER = "Other-Machine-ZZZZ";

    @TempDir
    Path tempDir;

    private final List<String> notifications = new ArrayList<>();

    private Path ikeDev;
    private Path home;

    private LeaseDaemon daemon(java.util.function.Function<String, String> prober)
            throws IOException {
        home = tempDir.resolve("home");
        ikeDev = home.resolve("ike-dev");
        Files.createDirectories(ikeDev.resolve("leases"));
        Files.writeString(home.resolve(".ike-machine-id"), ME + "\n",
                StandardCharsets.UTF_8);
        return new LeaseDaemon(ikeDev, home,
                tempDir.resolve("state.properties"), prober,
                (title, message) -> notifications.add(title + ": " + message));
    }

    private void record(String ws, String state, String holder, long epoch,
                        String renewed) throws IOException {
        Files.writeString(ikeDev.resolve("leases").resolve(ws + ".lease"),
                new LeaseRecord(ws, state, holder, epoch, renewed, renewed,
                        "PT10M").serialize(),
                StandardCharsets.UTF_8);
    }

    private String stamp(long secondsAgo) {
        return ISO.format(Instant.now().minusSeconds(secondsAgo));
    }

    @Test
    void fencedTakeoverIsNotifiedOncePromptly() throws Exception {
        LeaseDaemon daemon = daemon(ws -> "");
        Files.createDirectories(ikeDev.resolve("my-ws"));
        record("my-ws", "held", ME, 3, stamp(10));
        daemon.pass(false);     // remembers my-ws as MINE
        assertTrue(notifications.isEmpty());

        record("my-ws", "held", OTHER, 4, stamp(5));    // the takeover lands
        List<String> log = daemon.pass(false);

        assertEquals(1, notifications.size());
        assertTrue(notifications.getFirst().contains("taken over by "
                + OTHER), notifications.toString());
        assertTrue(log.stream().anyMatch(l -> l.startsWith("FENCED:")));

        notifications.clear();
        daemon.pass(false);     // no longer MINE in memory: no repeat
        assertTrue(notifications.isEmpty(),
                "a fencing is announced once, not every pass");
    }

    @Test
    void gcSparesLiveRecordsWhoseTreeMayStillBeSyncing() throws Exception {
        LeaseDaemon daemon = daemon(ws -> "");
        record("still-syncing", "held", OTHER, 1, stamp(60));
        record("long-gone", "held", OTHER, 1, stamp(3L * 24 * 3600));
        record("finished", "released", ME, 2, stamp(60));

        List<String> log = daemon.pass(false);

        assertTrue(Files.exists(
                        ikeDev.resolve("leases/still-syncing.lease")),
                "a live record for a missing tree is presumed in-flight");
        assertFalse(Files.exists(ikeDev.resolve("leases/long-gone.lease")),
                "a day past its horizon with no tree = garbage");
        assertFalse(Files.exists(ikeDev.resolve("leases/finished.lease")),
                "a released record with no tree = garbage");
        assertTrue(log.stream().anyMatch(l -> l.contains("left alone")));
    }

    @Test
    void alarmFiresForAnIdeOpenWithoutTheLease() throws Exception {
        LeaseDaemon daemon = daemon(ws -> ws.equals("my-ws")
                ? "free here (" + ME + ")\nOPEN on Rogue-Machine-BBBB "
                        + "(rogue.local) — stand down there before working here\n"
                : "free here (" + ME + ")\n");
        Files.createDirectories(ikeDev.resolve("my-ws"));
        Files.createDirectories(ikeDev.resolve("other-ws"));
        record("my-ws", "held", ME, 3, stamp(10));

        List<String> log = daemon.pass(true);

        assertEquals(1, notifications.size());
        assertTrue(notifications.getFirst().contains("Rogue-Machine-BBBB"));
        assertTrue(notifications.getFirst().contains("held by " + ME));
        assertTrue(log.stream().anyMatch(l -> l.startsWith("ALARM:")));
    }

    @Test
    void alarmStaysQuietWhenTheHolderIsTheOpener() throws Exception {
        LeaseDaemon daemon = daemon(ws ->
                "OPEN here (" + ME + ") — close it or work here\n");
        Files.createDirectories(ikeDev.resolve("my-ws"));
        record("my-ws", "held", ME, 3, stamp(10));

        daemon.pass(true);

        assertTrue(notifications.isEmpty(),
                "the holder having it open is the normal state");
    }

    @Test
    void probeIsRateLimitedBetweenPasses() throws Exception {
        List<String> probed = new ArrayList<>();
        LeaseDaemon daemon = daemon(ws -> {
            probed.add(ws);
            return "";
        });
        Files.createDirectories(ikeDev.resolve("my-ws"));

        daemon.pass(false);     // first pass: lastProbe=0 → probes
        int afterFirst = probed.size();
        daemon.pass(false);     // within the interval → no probe

        assertTrue(afterFirst > 0, "the first pass probes");
        assertEquals(afterFirst, probed.size(),
                "the second pass is inside the rate limit");
    }

    @Test
    void openMachineParsingReadsLocalAndPeerLines() {
        Set<String> open = LeaseDaemon.parseOpenMachines("""
                OPEN here (Self-Id) — close it or work here
                free on Peer-A (peer-a.local)
                OPEN on Peer-B (peer-b.local) — stand down there
                offline: peer-c.local — proceeding per silent-peer policy
                """, "Self-Id");
        assertEquals(Set.of("Self-Id", "Peer-B"), open);
    }
}
