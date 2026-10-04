package network.ike.lease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stamping on the protocol's write path (IKE-Network/ike-issues#1069):
 * a write by the current holder refreshes the stamps from its
 * repositories, a write that changes the holder carries the previous
 * record's stamps forward — they are the taker's alignment target — and
 * siblings are stamped like roots (IKE-Network/ike-issues#1216).
 */
class LeaseProtocolStampTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final String ME = "Test-Machine-AAAA";
    private static final String OTHER = "Other-Machine-ZZZZ";
    private static final String SHA_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final GitRunner git = new ProcessGitRunner();

    @TempDir
    Path tempDir;

    private Path home;
    private Path ikeDev;

    private LeaseProtocol protocol() throws IOException {
        home = tempDir.resolve("home");
        ikeDev = home.resolve("ike-dev");
        Files.createDirectories(ikeDev.resolve("leases"));
        Files.writeString(home.resolve(".ike-machine-id"), ME + "\n",
                StandardCharsets.UTF_8);
        return new LeaseProtocol(ikeDev, home, ikeDev, "PT10M", 0);
    }

    @Test
    void renewByTheHolderRefreshesStampsFromTheRepositories()
            throws IOException {
        LeaseProtocol protocol = protocol();
        Path root = repo(ikeDev.resolve("my-ws"), "main");
        assertEquals(0, protocol.take("my-ws", true, false)
                .exitCode());

        assertEquals(0, protocol.renew("my-ws").exitCode());

        LeaseRecord record = record("my-ws");
        assertEquals(List.of(new RepoStamp(".", "main",
                        revParse(root, "HEAD"))), record.stamps(),
                "the holder's renewal stamps its refs into the record");
    }

    @Test
    void takingAReturnedLeaseCarriesTheStampsForward()
            throws IOException {
        LeaseProtocol protocol = protocol();
        Files.createDirectories(ikeDev.resolve("my-ws"));
        writeRecord(new LeaseRecord("my-ws", RecordState.RETURNED, OTHER, 4,
                stamp(600), stamp(60), "PT10M",
                List.of(new RepoStamp(".", "main", SHA_A))));

        assertEquals(0, protocol.take("my-ws", true, false)
                .exitCode());

        LeaseRecord record = record("my-ws");
        assertEquals(ME, record.holder());
        assertEquals(5, record.epoch());
        assertEquals(List.of(new RepoStamp(".", "main", SHA_A)),
                record.stamps(),
                "the previous holder's stamps are the alignment target; "
                        + "the taker's own stale refs must not replace them");
    }

    @Test
    void recallOfALiveLeaseCarriesTheStampsForward()
            throws IOException {
        LeaseProtocol protocol = protocol();
        Files.createDirectories(ikeDev.resolve("my-ws"));
        writeRecord(new LeaseRecord("my-ws", RecordState.HELD, OTHER, 4,
                stamp(600), stamp(10), "PT10M",
                List.of(new RepoStamp(".", "main", SHA_A))));

        assertEquals(0, protocol.recall("my-ws", true, false)
                .exitCode());

        LeaseRecord record = record("my-ws");
        assertEquals(ME, record.holder());
        assertEquals(List.of(new RepoStamp(".", "main", SHA_A)),
                record.stamps());
    }

    @Test
    void returnStampsTheFinalRefs() throws IOException {
        LeaseProtocol protocol = protocol();
        Path root = repo(ikeDev.resolve("my-ws"), "main");
        assertEquals(0, protocol.take("my-ws", true, false)
                .exitCode());

        assertEquals(0, protocol.returnLease("my-ws").exitCode());

        LeaseRecord record = record("my-ws");
        assertEquals(RecordState.RETURNED, record.state());
        assertTrue(Files.readString(ikeDev.resolve("leases/my-ws.lease"),
                        StandardCharsets.UTF_8).contains("state: returned\n"),
                "this core writes the new spelling");
        assertEquals(List.of(new RepoStamp(".", "main",
                        revParse(root, "HEAD"))), record.stamps(),
                "the return stamp is what the machine switch aligns to");
    }

    @Test
    void siblingsAreStampedLikeRoots() throws IOException {
        LeaseProtocol protocol = protocol();
        Path sibling = repo(ikeDev.resolve("my-ws꞉feature"),
                "feature/feature");
        assertEquals(0, protocol.take("my-ws꞉feature", true, false)
                .exitCode());

        assertEquals(0, protocol.renew("my-ws꞉feature").exitCode());

        assertEquals(List.of(new RepoStamp(".", "feature/feature",
                        revParse(sibling, "HEAD"))),
                record("my-ws꞉feature").stamps(),
                "a sibling's history now travels (ike-issues#1216)");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private LeaseRecord record(String ws) {
        return LeaseRecord.read(ikeDev.resolve("leases")
                .resolve(ws + ".lease")).orElseThrow();
    }

    private void writeRecord(LeaseRecord record) throws IOException {
        Files.writeString(ikeDev.resolve("leases")
                        .resolve(record.workingSet() + ".lease"),
                record.serialize(), StandardCharsets.UTF_8);
    }

    private String stamp(long secondsAgo) {
        return ISO.format(Instant.now().minusSeconds(secondsAgo));
    }

    private Path repo(Path directory, String branch) throws IOException {
        Files.createDirectories(directory);
        run(directory, "init", "--initial-branch=" + branch);
        run(directory, "config", "user.email", "test@ike.network");
        run(directory, "config", "user.name", "Lease Protocol Stamp Test");
        run(directory, "config", "commit.gpgsign", "false");
        run(directory, "config", "core.hooksPath", ".git/hooks");
        Files.writeString(directory.resolve("tracked.txt"), "content\n",
                StandardCharsets.UTF_8);
        run(directory, "add", ".");
        run(directory, "commit", "-m", "initial");
        return directory;
    }

    private String revParse(Path repo, String ref) {
        GitRunner.GitResult result = git.run(repo,
                List.of("rev-parse", ref));
        assertTrue(result.ok(), "git rev-parse failed: " + result.stderr());
        return result.stdoutTrimmed();
    }

    private void run(Path repo, String... args) {
        GitRunner.GitResult result = git.run(repo, List.of(args));
        assertTrue(result.ok(), "git " + String.join(" ", args)
                + " failed: " + result.stderr());
    }
}
