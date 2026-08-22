package network.ike.lease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The golden equivalence suite of IKE-Network/ike-issues#1067: every
 * scenario runs twice — once against the frozen {@code lease.sh} v2
 * reference (vendored as a test resource, because the live script is now
 * a wrapper around the very code under test) and once against
 * {@link LeaseProtocol} — on byte-identical fixtures, and compares exit
 * code, stdout, stderr, and the resulting record file.
 *
 * <p>Timestamps the implementations mint themselves are normalized; a
 * scenario that asserts NO write happened compares the record byte for
 * byte instead. A fencing system's two halves must not merely both work —
 * they must agree.
 */
class LeaseProtocolEquivalenceTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final String ME = "Test-Machine-AAAA";
    private static final String OTHER = "Other-Machine-ZZZZ";
    private static final String WS = "some-working-set";

    private static Path referenceScript;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void unpackReference() throws IOException {
        referenceScript = Files.createTempFile("lease-v2-reference", ".sh");
        try (var in = LeaseProtocolEquivalenceTest.class
                .getResourceAsStream("/lease-v2-reference.sh")) {
            Files.copy(in, referenceScript,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.setPosixFilePermissions(referenceScript,
                PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /** One side's sandbox: a throwaway HOME with an ike-dev inside. */
    record Sandbox(Path home, Path ikeDev) {

        static Sandbox create(Path root) throws IOException {
            Path home = root.resolve("home");
            Path ikeDev = home.resolve("ike-dev");
            Files.createDirectories(ikeDev.resolve("leases"));
            Files.createDirectories(ikeDev.resolve(WS));
            Files.writeString(home.resolve(".ike-machine-id"), ME + "\n",
                    StandardCharsets.UTF_8);
            // Canonical (symlink-resolved) paths throughout: bash computes
            // PWD physically, so a /var vs /private/var split between the
            // env and the working directory would be the harness's
            // artifact, not a protocol behavior.
            return new Sandbox(home.toRealPath(), ikeDev.toRealPath());
        }

        Path leaseFile(String ws) {
            return ikeDev.resolve("leases").resolve(ws + ".lease");
        }

        void writeRecord(String name, String content) throws IOException {
            Files.writeString(ikeDev.resolve("leases").resolve(name),
                    content, StandardCharsets.UTF_8);
        }
    }

    /** A completed invocation, normalized for comparison. */
    record Run(int exit, String stdout, String stderr, String record,
               long conflictCopies) { }

    private static String record(String ws, String state, String holder,
                                 long epoch, String acquired, String renewed) {
        return new LeaseRecord(ws, state, holder, epoch, acquired, renewed,
                "PT10M").serialize();
    }

    private static String freshStamp() {
        return ISO.format(Instant.now().minusSeconds(10));
    }

    private static String staleStamp() {
        return ISO.format(Instant.now().minusSeconds(3600));
    }

    // ── the dual runner ─────────────────────────────────────────────

    /**
     * Runs the same invocation on both sides over identically prepared
     * sandboxes and asserts the outcomes agree.
     *
     * @param label     scenario name for failure messages
     * @param prepare   fixture writer, applied to each sandbox
     * @param verb      protocol verb + arguments
     * @param exactFile compare the record byte-for-byte (for no-write
     *                  scenarios) instead of timestamp-normalized
     */
    private void assertEquivalent(String label,
                                  Prepare prepare,
                                  List<String> verb,
                                  boolean exactFile) throws Exception {
        Sandbox shellSide = Sandbox.create(tempDir.resolve(label + "-sh"));
        Sandbox javaSide = Sandbox.create(tempDir.resolve(label + "-jv"));
        prepare.apply(shellSide);
        prepare.apply(javaSide);

        Run shell = runShell(shellSide, verb, 0);
        Run java = runJava(javaSide, verb, 0);

        assertEquals(shell.exit(), java.exit(), label + ": exit code");
        assertEquals(normalize(shell.stdout(), shellSide),
                normalize(java.stdout(), javaSide), label + ": stdout");
        assertEquals(normalize(shell.stderr(), shellSide),
                normalize(java.stderr(), javaSide), label + ": stderr");
        if (exactFile) {
            assertEquals(shell.record(), java.record(),
                    label + ": record must be untouched, byte for byte");
        } else {
            assertEquals(normalize(shell.record(), shellSide),
                    normalize(java.record(), javaSide), label + ": record");
        }
        assertEquals(shell.conflictCopies(), java.conflictCopies(),
                label + ": conflict copies remaining");
    }

    private Run runShell(Sandbox sandbox, List<String> verb,
                         long settleSeconds) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(referenceScript.toString());
        command.addAll(verb);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(sandbox.ikeDev().toFile());
        builder.environment().put("HOME", sandbox.home().toString());
        builder.environment().put("IKE_DEV", sandbox.ikeDev().toString());
        builder.environment().put("IKE_LEASE_SETTLE_SECONDS",
                String.valueOf(settleSeconds));
        builder.environment().remove("IKE_LEASE_TTL");
        Process process = builder.start();
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS),
                "reference script hung");
        return finish(sandbox, process.exitValue(), out, err);
    }

    private Run runJava(Sandbox sandbox, List<String> verb,
                        long settleSeconds) throws Exception {
        LeaseProtocol protocol = new LeaseProtocol(sandbox.ikeDev(),
                sandbox.home(), sandbox.ikeDev(), "PT10M", settleSeconds);
        String v = verb.get(0);
        List<String> rest = verb.subList(1, verb.size());
        LeaseProtocol.Outcome outcome = switch (v) {
            case "status" -> protocol.status(rest.get(0));
            case "acquire" -> protocol.acquire(rest.get(0),
                    rest.contains("--force"), rest.contains("--quiet"),
                    rest.contains("--confirm"));
            case "ensure" -> protocol.ensure(rest.get(0),
                    rest.contains("--confirm"));
            case "renew" -> protocol.renew(rest.get(0));
            case "release" -> protocol.release(rest.get(0));
            case "list" -> protocol.list();
            case "resolve" -> protocol.resolve(rest.get(0));
            default -> throw new IllegalArgumentException(v);
        };
        return finish(sandbox, outcome.exitCode(), outcome.stdout(),
                outcome.stderr());
    }

    private Run finish(Sandbox sandbox, int exit, String out, String err)
            throws IOException {
        Path record = sandbox.leaseFile(WS);
        String content = Files.isRegularFile(record)
                ? Files.readString(record, StandardCharsets.UTF_8) : "(none)";
        long conflicts;
        try (var stream = Files.newDirectoryStream(
                sandbox.ikeDev().resolve("leases"), "*sync-conflict*")) {
            conflicts = java.util.stream.StreamSupport
                    .stream(stream.spliterator(), false).count();
        }
        return new Run(exit, out, err, content, conflicts);
    }

    private static String normalize(String text, Sandbox sandbox) {
        return text
                .replaceAll("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z", "TS")
                .replace(sandbox.home().toString(), "HOME")
                .replaceAll("lease-v2-reference\\d*\\.sh", "lease.sh");
    }

    @FunctionalInterface
    interface Prepare {
        void apply(Sandbox sandbox) throws IOException;
    }

    private static final Prepare EMPTY = sandbox -> { };

    // ── status ──────────────────────────────────────────────────────

    @Test
    void status_noRecord() throws Exception {
        assertEquivalent("status-none", EMPTY,
                List.of("status", WS), true);
    }

    @Test
    void status_mineFresh() throws Exception {
        String fresh = freshStamp();
        assertEquivalent("status-mine", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", ME, 3, fresh, fresh)),
                List.of("status", WS), true);
    }

    @Test
    void status_liveOther_exitsOne() throws Exception {
        String fresh = freshStamp();
        assertEquivalent("status-live", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", OTHER, 5, fresh, fresh)),
                List.of("status", WS), true);
    }

    @Test
    void status_expiredOther() throws Exception {
        String stale = staleStamp();
        assertEquivalent("status-expired", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", OTHER, 5, stale, stale)),
                List.of("status", WS), true);
    }

    @Test
    void status_released() throws Exception {
        String stale = staleStamp();
        assertEquivalent("status-released", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "released", OTHER, 5, stale, stale)),
                List.of("status", WS), true);
    }

    // ── acquire ─────────────────────────────────────────────────────

    @Test
    void acquire_free_mintsEpochOne() throws Exception {
        assertEquivalent("acquire-free", EMPTY,
                List.of("acquire", WS), false);
    }

    @Test
    void acquire_expired_advancesEpoch() throws Exception {
        String stale = staleStamp();
        assertEquivalent("acquire-expired", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", OTHER, 7, stale, stale)),
                List.of("acquire", WS), false);
    }

    @Test
    void acquire_mine_renewsKeepingEpoch() throws Exception {
        String fresh = freshStamp();
        assertEquivalent("acquire-mine", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", ME, 4, fresh, fresh)),
                List.of("acquire", WS), false);
    }

    @Test
    void acquire_live_refusedWithoutForce() throws Exception {
        String fresh = freshStamp();
        assertEquivalent("acquire-live", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", OTHER, 5, fresh, fresh)),
                List.of("acquire", WS), true);
    }

    @Test
    void acquire_live_forceTakesOver() throws Exception {
        String fresh = freshStamp();
        assertEquivalent("acquire-force", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", OTHER, 5, fresh, fresh)),
                List.of("acquire", WS, "--force"), false);
    }

    // ── release / renew ─────────────────────────────────────────────

    @Test
    void release_mine() throws Exception {
        String fresh = freshStamp();
        assertEquivalent("release-mine", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", ME, 4, fresh, fresh)),
                List.of("release", WS), false);
    }

    @Test
    void release_heldElsewhere_refused() throws Exception {
        String fresh = freshStamp();
        assertEquivalent("release-other", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", OTHER, 5, fresh, fresh)),
                List.of("release", WS), true);
    }

    @Test
    void release_noRecord_isFine() throws Exception {
        assertEquivalent("release-none", EMPTY,
                List.of("release", WS), true);
    }

    @Test
    void renew_mine_updatesStamp() throws Exception {
        String fresh = freshStamp();
        assertEquivalent("renew-mine", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", ME, 4, staleStamp(), fresh)),
                List.of("renew", WS), false);
    }

    @Test
    void renew_heldElsewhere_refused() throws Exception {
        String fresh = freshStamp();
        assertEquivalent("renew-other", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", OTHER, 5, fresh, fresh)),
                List.of("renew", WS), true);
    }

    // ── ensure ──────────────────────────────────────────────────────

    @Test
    void ensure_mineFresh_writesNothing() throws Exception {
        String fresh = freshStamp();
        assertEquivalent("ensure-fresh", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", ME, 4, fresh, fresh)),
                List.of("ensure", WS), true);
    }

    @Test
    void ensure_minePastHalfLife_renews() throws Exception {
        String old = ISO.format(Instant.now().minusSeconds(400));
        assertEquivalent("ensure-halflife", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", ME, 4, old, old)),
                List.of("ensure", WS), false);
    }

    @Test
    void ensure_free_acquiresSilently() throws Exception {
        assertEquivalent("ensure-free", EMPTY,
                List.of("ensure", WS), false);
    }

    @Test
    void ensure_live_deniesWithReason() throws Exception {
        String fresh = freshStamp();
        assertEquivalent("ensure-live", sandbox -> sandbox.writeRecord(
                        WS + ".lease", record(WS, "held", OTHER, 5, fresh, fresh)),
                List.of("ensure", WS), true);
    }

    @Test
    void ensure_confirm_winsOnAnUncontestedClaim() throws Exception {
        assertEquivalent("ensure-confirm", EMPTY,
                List.of("ensure", WS, "--confirm"), false);
    }

    // ── reconciliation ──────────────────────────────────────────────

    @Test
    void reconcile_higherEpochConflictWins() throws Exception {
        String fresh = freshStamp();
        Prepare split = sandbox -> {
            sandbox.writeRecord(WS + ".lease",
                    record(WS, "held", ME, 5, fresh, fresh));
            sandbox.writeRecord(
                    WS + ".sync-conflict-20260822-100000-AAAAAAA.lease",
                    record(WS, "held", OTHER, 6, fresh, fresh));
        };
        assertEquivalent("reconcile-epoch", split,
                List.of("status", WS), true);
    }

    @Test
    void reconcile_tieBreaksOnGreatestMachineId() throws Exception {
        String fresh = freshStamp();
        Prepare split = sandbox -> {
            sandbox.writeRecord(WS + ".lease",
                    record(WS, "held", ME, 5, fresh, fresh));
            sandbox.writeRecord(
                    WS + ".sync-conflict-20260822-100000-AAAAAAA.lease",
                    record(WS, "held", OTHER, 5, fresh, fresh));
        };
        assertEquivalent("reconcile-tie", split,
                List.of("status", WS), true);
    }

    // ── the race, lost mid-settle (#1005's heart) ───────────────────

    @Test
    void ensureConfirm_losesWhenACompetingClaimLandsMidSettle()
            throws Exception {
        Sandbox shellSide = Sandbox.create(tempDir.resolve("race-sh"));
        Sandbox javaSide = Sandbox.create(tempDir.resolve("race-jv"));
        String competing = record(WS, "held", OTHER, 2,
                freshStamp(), freshStamp());

        Run shell;
        {
            CompletableFuture<Run> running = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return runShell(shellSide,
                                    List.of("ensure", WS, "--confirm"), 3);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            Thread.sleep(1000);
            shellSide.writeRecord(WS + ".lease", competing);
            shell = running.get(60, TimeUnit.SECONDS);
        }
        Run java;
        {
            CompletableFuture<Run> running = CompletableFuture.supplyAsync(
                    () -> {
                        try {
                            return runJava(javaSide,
                                    List.of("ensure", WS, "--confirm"), 3);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
            Thread.sleep(1000);
            javaSide.writeRecord(WS + ".lease", competing);
            java = running.get(60, TimeUnit.SECONDS);
        }

        assertEquals(1, shell.exit(), "shell must lose the race");
        assertEquals(shell.exit(), java.exit(), "race: exit");
        assertEquals(normalize(shell.stderr(), shellSide),
                normalize(java.stderr(), javaSide), "race: stderr");
        assertEquals(normalize(shell.stdout(), shellSide),
                normalize(java.stdout(), javaSide), "race: stdout");
    }

    // ── resolve / list ──────────────────────────────────────────────

    @Test
    void resolve_matchesOnEveryPathShape() throws Exception {
        Sandbox shellSide = Sandbox.create(tempDir.resolve("resolve-sh"));
        Sandbox javaSide = Sandbox.create(tempDir.resolve("resolve-jv"));
        Function<Sandbox, List<String>> cases = sandbox -> List.of(
                sandbox.ikeDev() + "/" + WS,
                sandbox.ikeDev() + "/" + WS + "/deep/file.txt",
                sandbox.ikeDev() + "/parent꞉feature/member/pom.xml",
                sandbox.ikeDev() + "/leases/" + WS + ".lease",
                sandbox.ikeDev() + "/scripts/lease.sh",
                sandbox.ikeDev().toString(),
                "/somewhere/else/entirely",
                "relative/inside");
        List<String> shellCases = cases.apply(shellSide);
        List<String> javaCases = cases.apply(javaSide);
        for (int i = 0; i < shellCases.size(); i++) {
            Run shell = runShell(shellSide,
                    List.of("resolve", shellCases.get(i)), 0);
            Run java = runJava(javaSide,
                    List.of("resolve", javaCases.get(i)), 0);
            assertEquals(shell.exit(), java.exit(),
                    "resolve exit: " + shellCases.get(i));
            assertEquals(normalize(shell.stdout(), shellSide),
                    normalize(java.stdout(), javaSide),
                    "resolve out: " + shellCases.get(i));
        }
    }


    /**
     * A deliberate, documented divergence — the one place the port fixes
     * v2 rather than reproducing it. Under {@code /bin/bash} 3.2 (the
     * shebang interpreter, so what production actually ran), the
     * substitution {@code ${p//\\/.\\//\\/}} leaves a literal backslash in
     * the path ({@code /a/./b} → {@code /a\\/b}), so v2's resolve refused
     * every path containing {@code /./} — a small fence-enforcement hole,
     * latent since v2 shipped and exposed by this suite's first run. The
     * port implements the code's stated intent ("normalises textually")
     * and resolves such paths correctly.
     */
    @Test
    void resolve_dotSegments_fixesTheBash32Defect() throws Exception {
        Sandbox javaSide = Sandbox.create(tempDir.resolve("dotseg-jv"));
        Run java = runJava(javaSide, List.of("resolve",
                javaSide.ikeDev() + "/./" + WS + "/x"), 0);
        assertEquals(0, java.exit(), "the port resolves /./ paths");
        assertEquals(WS + "\n", java.stdout());
    }


    /**
     * The CLI honors the {@code HOME} environment variable, as the shell
     * always did — the JVM's OS-derived {@code user.home} ignores it, and
     * the sandboxed suites (this one, and the v2 shell suite that caught
     * the regression) depend on it.
     */
    @Test
    void cli_honorsTheHomeEnvironmentVariable() throws Exception {
        Sandbox sandbox = Sandbox.create(tempDir.resolve("cli-home"));
        String fresh = freshStamp();
        sandbox.writeRecord(WS + ".lease",
                record(WS, "held", ME, 3, fresh, fresh));
        ProcessBuilder builder = new ProcessBuilder("java", "-cp",
                "target/classes", "network.ike.lease.core.LeaseProtocolCli",
                "status", WS);
        builder.environment().put("HOME", sandbox.home().toString());
        builder.environment().put("IKE_DEV", sandbox.ikeDev().toString());
        Process process = builder.start();
        process.getOutputStream().close();
        String out = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI hung");
        assertEquals(0, process.exitValue());
        assertTrue(out.contains("MINE (" + ME),
                "the sandbox identity must be honored, got: " + out);
    }

    @Test
    void list_describesEveryRecordInNameOrder() throws Exception {
        String fresh = freshStamp();
        String stale = staleStamp();
        Prepare several = sandbox -> {
            sandbox.writeRecord("alpha.lease",
                    record("alpha", "held", ME, 1, fresh, fresh));
            sandbox.writeRecord("beta.lease",
                    record("beta", "held", OTHER, 2, stale, stale));
            sandbox.writeRecord("gamma.lease",
                    record("gamma", "released", ME, 3, stale, stale));
        };
        assertEquivalent("list", several, List.of("list"), true);
    }

    @Test
    void list_emptyDirectorySaysSo() throws Exception {
        assertEquivalent("list-empty", EMPTY, List.of("list"), true);
    }
}
