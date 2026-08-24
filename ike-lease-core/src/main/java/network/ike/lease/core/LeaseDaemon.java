package network.ike.lease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * The per-machine reconciliation daemon — the v3 stage of the lease
 * protocol (IKE-Network/ike-issues#1006), run as a one-shot pass by a
 * launchd agent watching {@code leases/} with an interval fallback.
 *
 * <p>Smaller than first specified, because the fleet grew the other arms
 * first: renewal is already covered (the IDE plugin's watcher renews
 * every open project, and the Claude fence renews at half-life on every
 * write), self-fencing is already covered (the watcher and the fence
 * both stand down on their own), materialization lives at the open
 * gesture (#1057), and the resident-socket idea died of a measurement
 * (the wrapper answers in 60ms). What no other arm does — and what this
 * pass is — remains:
 *
 * <ol>
 *   <li><b>Fenced notification.</b> Between passes the daemon remembers
 *       which leases were MINE; one now held live by another machine
 *       means this machine slept through a takeover, and the human gets
 *       told promptly instead of discovering it at the next denied
 *       write.</li>
 *   <li><b>Record garbage collection.</b> A lease record whose working
 *       set no longer exists is noise forever, but GC must never eat a
 *       record whose <em>tree simply has not synced yet</em> — records
 *       travel faster than trees (the scan-force covers only
 *       {@code leases/}). So only released records, or ones long past
 *       their staleness horizon, are collected.</li>
 *   <li><b>The non-participant alarm.</b> A lease is a claim, and only
 *       participants make claims; the open-project probe is the monitor
 *       for that control. Scoped to working sets whose lease is held
 *       and renewed within its staleness horizon — renewal is the
 *       activity signal, so probing follows actual work and stops when
 *       the machine idles (IKE-Network/ike-issues#1075). Rate-limited,
 *       and notify-never-block — the probe rides SSH, and a denial
 *       that depends on reaching a peer would break the rule that
 *       correctness never does.</li>
 * </ol>
 */
public final class LeaseDaemon {

    /** GC eligibility for a non-released record: a day past renewal. */
    private static final long GC_AGE_SECONDS = 24L * 3600L;

    /** Minimum seconds between probe passes. */
    private static final long PROBE_INTERVAL_SECONDS = 1800L;

    private final Path ikeDev;
    private final Path home;
    private final Path stateFile;
    private final Function<String, String> prober;
    private final BiConsumer<String, String> notifier;
    private final List<String> log = new ArrayList<>();

    /**
     * Creates a daemon pass with injectable seams.
     *
     * @param ikeDev    the development-folder root
     * @param home      the home directory (machine identity)
     * @param stateFile where the pass persists its between-run memory
     * @param prober    runs the open-project probe for a working set and
     *                  returns its full output ({@code working-set-check.sh})
     * @param notifier  posts a user notification (title, message)
     */
    LeaseDaemon(Path ikeDev, Path home, Path stateFile,
                Function<String, String> prober,
                BiConsumer<String, String> notifier) {
        this.ikeDev = ikeDev;
        this.home = home;
        this.stateFile = stateFile;
        this.prober = prober;
        this.notifier = notifier;
    }

    /**
     * Entry point for the launchd agent: one pass, then exit.
     *
     * @param args {@code --force-probe} runs the alarm probe regardless of
     *             the rate limit
     */
    public static void main(String[] args) {
        String home = orDefault(System.getenv("HOME"),
                System.getProperty("user.home"));
        String ikeDev = orDefault(System.getenv("IKE_DEV"),
                home + "/ike-dev");
        Path stateFile = Path.of(home,
                "Library/Application Support/ike-lease-daemon/state.properties");
        Path probeScript = Path.of(ikeDev, "scripts/working-set-check.sh");
        LeaseDaemon daemon = new LeaseDaemon(Path.of(ikeDev), Path.of(home),
                stateFile,
                ws -> runProbe(probeScript, ws),
                LeaseDaemon::notifyMacos);
        boolean forceProbe = List.of(args).contains("--force-probe");
        for (String line : daemon.pass(forceProbe)) {
            System.out.println(line);
        }
    }

    /**
     * One reconciliation pass.
     *
     * @param forceProbe run the alarm probe regardless of the rate limit
     * @return the pass's log lines
     */
    List<String> pass(boolean forceProbe) {
        log.clear();
        Optional<String> me = machineId();
        if (me.isEmpty() || !Files.isDirectory(ikeDev)) {
            log.add("not applicable here (no machine identity or no "
                    + ikeDev + ") — nothing to do");
            return List.copyOf(log);
        }
        Properties state = loadState();
        Set<String> priorMine = splitSet(state.getProperty("mine", ""));

        Set<String> currentMine = new LinkedHashSet<>();
        List<String> records = leaseRecordNames();
        for (String ws : records) {
            Optional<LeaseRecord> record =
                    LeaseRecord.read(leaseFile(ws));
            if (record.isEmpty()) {
                continue;
            }
            boolean mine = record.get().holder().equals(me.get())
                    && !"released".equals(record.get().state());
            if (mine) {
                currentMine.add(ws);
            }
            if (priorMine.contains(ws) && !mine
                    && !"released".equals(record.get().state())) {
                // Held here last pass, now held elsewhere: this machine
                // slept through a takeover. The watcher and the fence
                // already stand down on their own; the human just gets
                // told promptly.
                String message = ws + " was taken over by "
                        + record.get().holder() + " (epoch "
                        + record.get().epoch() + ") while this machine "
                        + "was not looking. It is theirs now.";
                notifier.accept("Working-set lease fenced", message);
                log.add("FENCED: " + message);
            }
            garbageCollect(ws, record.get());
        }

        long lastProbe = parseLong(state.getProperty("lastProbe", "0"));
        long now = Instant.now().getEpochSecond();
        if (forceProbe || now - lastProbe >= PROBE_INTERVAL_SECONDS) {
            alarmProbe(me.get());
            state.setProperty("lastProbe", String.valueOf(now));
        }

        state.setProperty("mine", String.join(",", currentMine));
        saveState(state);
        log.add("pass complete: " + records.size() + " record(s), "
                + currentMine.size() + " held here");
        return List.copyOf(log);
    }

    /**
     * Collects a record whose working set is gone — but never one whose
     * tree may simply not have synced yet: records travel faster than
     * trees, so a live, freshly renewed record for a missing directory is
     * presumed to be a working set still on its way in.
     */
    private void garbageCollect(String ws, LeaseRecord record) {
        if (Files.isDirectory(ikeDev.resolve(ws))) {
            return;
        }
        boolean released = "released".equals(record.state());
        boolean longExpired = renewedEpoch(record)
                .map(renewed -> Instant.now().getEpochSecond() - renewed
                        > GC_AGE_SECONDS)
                .orElse(false);
        if (released || longExpired) {
            try {
                Files.deleteIfExists(leaseFile(ws));
                log.add("GC: removed record for missing working set " + ws
                        + " (" + (released ? "released" : "long expired")
                        + ")");
            } catch (IOException e) {
                log.add("GC: could not remove " + ws + ": " + e.getMessage());
            }
        } else {
            log.add("GC: " + ws + " has no tree here but its record is "
                    + "live — presumed still syncing, left alone");
        }
    }

    /**
     * The non-participant alarm: for every <em>actively held</em>
     * working set — lease state {@code held}, renewed within its own
     * TTL, any holder — ask the probe who actually has it open and
     * compare against the lease. An IDE holding a working set open on
     * a machine that does not hold its lease is exactly what a
     * claim-based protocol cannot see alone.
     *
     * <p>The freshness scope (IKE-Network/ike-issues#1075) is the
     * protocol's own LIVE-versus-EXPIRED staleness test reused as the
     * activity filter: the watcher and the fence renew only during
     * real work, so a held-but-stale record is an idle claim on an
     * idle tree — no live work for a rogue opener to collide with, and
     * the next open gesture re-runs confirmed acquisition regardless.
     * Probing therefore follows actual work: a handful of connections
     * during active development, none when the fleet idles. Probe
     * errors (a reachable peer whose check failed) are logged, not
     * alarmed — a flaky headless ssh must not cry wolf.
     */
    private void alarmProbe(String me) {
        for (String ws : liveHeldWorkingSets()) {
            String output;
            try {
                output = prober.apply(ws);
            } catch (RuntimeException e) {
                log.add("probe failed for " + ws + ": " + e.getMessage());
                continue;
            }
            Set<String> openOn = parseOpenMachines(output, me);
            if (openOn.isEmpty()) {
                continue;
            }
            Optional<LeaseRecord> record = LeaseRecord.read(leaseFile(ws));
            String holder = record
                    .filter(r -> !"released".equals(r.state()))
                    .map(LeaseRecord::holder).orElse("");
            for (String machine : openOn) {
                if (!machine.equals(holder)) {
                    String message = ws + " is open in an IDE on " + machine
                            + (holder.isEmpty()
                                    ? " with no lease at all"
                                    : " but the lease is held by " + holder)
                            + " — someone is writing without a claim.";
                    notifier.accept("Working set open without its lease",
                            message);
                    log.add("ALARM: " + message);
                }
            }
        }
    }

    /**
     * Extracts the machine ids the probe reports as having the working
     * set open, from both the local line ({@code OPEN here (<id>)}) and
     * peer lines ({@code OPEN on <id> (<peer>)}).
     */
    static Set<String> parseOpenMachines(String probeOutput, String selfId) {
        Set<String> open = new LinkedHashSet<>();
        for (String line : probeOutput.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("OPEN here (")) {
                open.add(selfId);
            } else if (trimmed.startsWith("OPEN on ")) {
                String rest = trimmed.substring("OPEN on ".length());
                int space = rest.indexOf(' ');
                open.add(space > 0 ? rest.substring(0, space) : rest);
            }
        }
        return open;
    }

    // ── plumbing ────────────────────────────────────────────────────

    private List<String> leaseRecordNames() {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                ikeDev.resolve("leases"), "*.lease")) {
            for (Path file : stream) {
                String name = file.getFileName().toString();
                names.add(name.substring(0,
                        name.length() - ".lease".length()));
            }
        } catch (IOException e) {
            // No leases directory: nothing to reconcile.
        }
        names.sort(String::compareTo);
        return names;
    }

    /**
     * Working sets whose lease record is held and renewed within its
     * own TTL — the alarm's scope (IKE-Network/ike-issues#1075). An
     * unparseable renewal stamp counts as live, mirroring the
     * protocol's own safe-side staleness reading.
     */
    private List<String> liveHeldWorkingSets() {
        List<String> sets = new ArrayList<>();
        long now = Instant.now().getEpochSecond();
        for (String ws : leaseRecordNames()) {
            Optional<LeaseRecord> record = LeaseRecord.read(leaseFile(ws));
            if (record.isEmpty()
                    || !"held".equals(record.get().state())) {
                continue;
            }
            long ttl = LeaseProtocol.ttlToSeconds(record.get().ttl());
            boolean live = renewedEpoch(record.get())
                    .map(renewed -> now - renewed <= ttl)
                    .orElse(true);
            if (live) {
                sets.add(ws);
            }
        }
        return sets;
    }

    private Optional<String> machineId() {
        Path idFile = home.resolve(".ike-machine-id");
        if (!Files.isRegularFile(idFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(idFile,
                    StandardCharsets.UTF_8).replaceAll("\\s", ""));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path leaseFile(String ws) {
        return ikeDev.resolve("leases").resolve(ws + ".lease");
    }

    private static Optional<Long> renewedEpoch(LeaseRecord record) {
        try {
            return Optional.of(Instant.from(java.time.format.DateTimeFormatter
                    .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(java.time.ZoneOffset.UTC)
                    .parse(record.renewed())).getEpochSecond());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private Properties loadState() {
        Properties state = new Properties();
        if (Files.isRegularFile(stateFile)) {
            try (var in = Files.newInputStream(stateFile)) {
                state.load(in);
            } catch (IOException e) {
                // A fresh pass with no memory is merely quieter.
            }
        }
        return state;
    }

    private void saveState(Properties state) {
        try {
            Files.createDirectories(stateFile.getParent());
            try (var out = Files.newOutputStream(stateFile)) {
                state.store(out, "ike-lease-daemon pass state");
            }
        } catch (IOException e) {
            log.add("could not persist state: " + e.getMessage());
        }
    }

    private static Set<String> splitSet(String joined) {
        Set<String> set = new LinkedHashSet<>();
        for (String part : joined.split(",")) {
            if (!part.isBlank()) {
                set.add(part.trim());
            }
        }
        return set;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String runProbe(Path script, String ws) {
        if (!Files.isExecutable(script)) {
            throw new IllegalStateException("no probe script at " + script);
        }
        try {
            Process process = new ProcessBuilder(script.toString(), ws)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("probe timed out");
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static void notifyMacos(String title, String message) {
        try {
            new ProcessBuilder("osascript", "-e",
                    "display notification \"" + message.replace("\"", "'")
                            + "\" with title \"" + title.replace("\"", "'")
                            + "\"")
                    .start().waitFor(10,
                            java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // A notification that cannot post is a log line's job.
        }
    }
}
