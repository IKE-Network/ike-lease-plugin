package network.ike.lease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The working-set lease protocol — the exact Java port of
 * {@code lease.sh} v2 (IKE-Network/ike-issues#1067, design
 * {@code dev-working-set-lease}, tracked in IKE-Network/ike-issues#1002).
 *
 * <p><b>This began as a port, not a redesign.</b> Exit code for exit
 * code and byte for byte on disk, this class reproduces what the shell
 * implementation did: the golden equivalence tests run both against
 * identical fixtures and compare everything. Behavior that looks quirky
 * here — the crude TTL parser's 600-second fallback, reconciliation
 * running as a side effect of every read, the {@code status} verb
 * reconciling twice — is the shell's, preserved, because a fencing system
 * whose implementations disagree is worse than one with no fencing at
 * all. Improvements belong after the flip, made once, here.
 *
 * <p>The first such improvement is the vocabulary
 * (IKE-Network/ike-issues#1215), modelled on a library loan: a machine
 * <em>takes</em> a working set, <em>renews</em> it while working,
 * <em>returns</em> it when done, and <em>recalls</em> one another machine
 * holds live — the human's decision. The equivalence tests compare modulo
 * exactly that vocabulary.
 *
 * <p>States: {@code FREE} (no record, or returned), {@code MINE} (held by
 * this machine), {@code EXPIRED} (held elsewhere but not renewed within
 * its ttl — taken silently), {@code LIVE} (held elsewhere and fresh —
 * recalling it requires a human decision). The TTL is a staleness horizon,
 * never a wait.
 *
 * <p>Siblings additionally carry their committed history in git bundles
 * beside the record (IKE-Network/ike-issues#1216): renewing refreshes the
 * bundles of members whose head moved, and returning writes them all, so
 * the next machine can align its refs to commits it has never seen.
 */
public final class LeaseProtocol {

    /**
     * One verb's complete result.
     *
     * @param exitCode the process exit code the CLI should return
     * @param stdout   everything the verb says on standard output
     * @param stderr   everything the verb says on standard error
     */
    public record Outcome(int exitCode, String stdout, String stderr) { }

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final Pattern TTL = Pattern.compile("^PT(\\d+)([HMS])$");
    private static final List<String> EXCLUDED_ROOTS =
            List.of("leases", "scripts", "notes", ".stfolder", ".stversions");

    private final Path ikeDev;
    private final Path home;
    private final Path workingDirectory;
    private final String defaultTtl;
    private final long settleSeconds;
    private final GitRunner git;

    /**
     * Creates a protocol instance.
     *
     * @param ikeDev           the development-folder root
     * @param home             the home directory (machine identity,
     *                         Syncthing config)
     * @param workingDirectory base for resolving relative paths in
     *                         {@link #resolve}
     * @param defaultTtl       the ttl written into every record
     *                         ({@code IKE_LEASE_TTL}, default PT10M)
     * @param settleSeconds    the confirm read-back's settle window
     *                         ({@code IKE_LEASE_SETTLE_SECONDS}, default 25)
     */
    public LeaseProtocol(Path ikeDev, Path home, Path workingDirectory,
                         String defaultTtl, long settleSeconds) {
        this(ikeDev, home, workingDirectory, defaultTtl, settleSeconds,
                new ProcessGitRunner());
    }

    /**
     * Creates a protocol instance with an explicit git runner — the one
     * that writes sibling bundles (IKE-Network/ike-issues#1216).
     *
     * @param ikeDev           the development-folder root
     * @param home             the home directory (machine identity,
     *                         Syncthing config)
     * @param workingDirectory base for resolving relative paths in
     *                         {@link #resolve}
     * @param defaultTtl       the ttl written into every record
     * @param settleSeconds    the confirm read-back's settle window
     * @param git              runs git for sibling bundles
     */
    public LeaseProtocol(Path ikeDev, Path home, Path workingDirectory,
                         String defaultTtl, long settleSeconds,
                         GitRunner git) {
        this.ikeDev = ikeDev;
        this.home = home;
        this.workingDirectory = workingDirectory;
        this.defaultTtl = defaultTtl;
        this.settleSeconds = settleSeconds;
        this.git = git;
    }

    // ── verbs ───────────────────────────────────────────────────────

    /**
     * Describes the lease; exit 1 exactly when it is held live elsewhere.
     *
     * @param workingSet the working-set name
     * @return the outcome
     */
    public Outcome status(String workingSet) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        String line = describe(workingSet, err);
        if (!line.isEmpty()) {
            out.append(line).append('\n');
        }
        String state = state(workingSet, err);
        return new Outcome("LIVE".equals(state) ? 1 : 0,
                out.toString(), err.toString());
    }

    /**
     * Takes the lease when it is free or expired — optimistically, unless
     * {@code confirm}. A lease held live elsewhere is refused with the
     * {@code recall} command that would take it.
     *
     * @param workingSet the working-set name
     * @param quiet      suppress success chatter
     * @param confirm    read the record back after the settle window
     * @return the outcome
     */
    public Outcome take(String workingSet, boolean quiet, boolean confirm) {
        return takeOrRecall(workingSet, false, quiet, confirm);
    }

    /**
     * Recalls the lease: takes it even when another machine holds it live,
     * fencing that machine. Always the human's decision — never called on
     * a tool's own initiative.
     *
     * @param workingSet the working-set name
     * @param quiet      suppress success chatter
     * @param confirm    read the record back after the settle window
     * @return the outcome
     */
    public Outcome recall(String workingSet, boolean quiet, boolean confirm) {
        return takeOrRecall(workingSet, true, quiet, confirm);
    }

    private Outcome takeOrRecall(String workingSet, boolean recall,
                                 boolean quiet, boolean confirm) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Optional<String> me = machineId(err);
        if (me.isEmpty()) {
            return new Outcome(2, out.toString(), err.toString());
        }
        String state = state(workingSet, err);
        Optional<LeaseRecord> record = LeaseRecord.read(leaseFile(workingSet));
        long epoch = record.map(LeaseRecord::epoch).orElse(0L);
        String now = ISO.format(Instant.now());

        // Ref stamps (IKE-Network/ike-issues#1069): a write by the current
        // holder refreshes them from the repositories — the holder's disk
        // is the truth. A write that CHANGES the holder carries the read
        // record's stamps forward instead: they describe the previous
        // holder's refs, which is exactly the alignment target the taker
        // is about to need, and the taker's own repositories are stale at
        // this moment by definition.
        List<RepoStamp> carried = record.map(LeaseRecord::stamps)
                .orElse(List.of());
        switch (state) {
            case "MINE" -> {
                List<RepoStamp> stamps = RefStamper.collect(ikeDev,
                        workingSet);
                refreshBundles(workingSet, record, me.get(), stamps, false,
                        err);
                write(workingSet, RecordState.HELD, me.get(), epoch,
                        record.map(LeaseRecord::acquired).orElse(""), now,
                        stamps);
                if (!quiet) {
                    out.append("already held here (epoch ").append(epoch)
                            .append(") — renewed\n");
                }
            }
            case "FREE", "EXPIRED" -> {
                epoch += 1;
                write(workingSet, RecordState.HELD, me.get(), epoch, now, now,
                        carried);
                if (!quiet) {
                    out.append("took ").append(workingSet)
                            .append(" (epoch ").append(epoch)
                            .append(", holder ").append(me.get())
                            .append(")\n");
                }
            }
            case "LIVE" -> {
                String prev = record.map(LeaseRecord::holder).orElse("");
                if (!recall) {
                    err.append("HELD by ").append(prev).append(" — renewed ")
                            .append(record.map(LeaseRecord::renewed).orElse(""))
                            .append(".\n");
                    err.append("Recalling a live lease is a human decision."
                            + " To recall it:\n");
                    err.append("  lease.sh recall '").append(workingSet)
                            .append("'\n");
                    return new Outcome(1, out.toString(), err.toString());
                }
                epoch += 1;
                write(workingSet, RecordState.HELD, me.get(), epoch, now, now,
                        carried);
                if (!quiet) {
                    out.append("RECALLED ").append(workingSet)
                            .append(" from ").append(prev)
                            .append(" (epoch ").append(epoch).append(") — ")
                            .append(prev).append(" is fenced\n");
                }
            }
            default -> {
                return new Outcome(2, out.toString(), err.toString());
            }
        }
        if (confirm && !confirmHold(workingSet, quiet, out, err)) {
            return new Outcome(1, out.toString(), err.toString());
        }
        return new Outcome(0, out.toString(), err.toString());
    }

    /**
     * Makes this machine the holder when that needs no human decision —
     * the single call for enforcement points.
     *
     * @param workingSet the working-set name
     * @param confirm    read the record back after the settle window
     * @return exit 0 held; 1 live-elsewhere or race lost (stdout carries
     *         the deny reason); 2 no identity
     */
    public Outcome ensure(String workingSet, boolean confirm) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        String state = state(workingSet, err);
        if (state == null) {
            return new Outcome(2, out.toString(), err.toString());
        }
        Optional<LeaseRecord> record = LeaseRecord.read(leaseFile(workingSet));
        switch (state) {
            case "MINE" -> {
                Optional<Long> renewed = record
                        .flatMap(r -> isoToEpoch(r.renewed()));
                if (renewed.isPresent()) {
                    long ttl = ttlToSeconds(
                            record.map(LeaseRecord::ttl).orElse(""));
                    long age = Instant.now().getEpochSecond() - renewed.get();
                    if (age >= ttl / 2) {
                        renew(workingSet);      // silently, as the shell did
                    }
                }
                if (confirm && !confirmHold(workingSet, true, out, err)) {
                    return new Outcome(1, out.toString(), err.toString());
                }
                return new Outcome(0, out.toString(), err.toString());
            }
            case "FREE", "EXPIRED" -> {
                Outcome taken = take(workingSet, true, confirm);
                return new Outcome(taken.exitCode() == 0 ? 0 : 1,
                        out + taken.stdout(), err + taken.stderr());
            }
            default -> {    // LIVE — the deny reason goes to stdout
                out.append("Working set '").append(workingSet)
                        .append("' is leased to ")
                        .append(record.map(LeaseRecord::holder).orElse(""))
                        .append(" (epoch ")
                        .append(record.map(r -> String.valueOf(r.epoch()))
                                .orElse("0"))
                        .append(", renewed ")
                        .append(record.map(LeaseRecord::renewed).orElse(""))
                        .append(").\n");
                out.append("Single-writer is enforced per working set, so "
                        + "writes from here are fenced.\n");
                out.append("Recalling a live lease is the human's call — "
                        + "ask before running:\n");
                out.append("  ~/ike-dev/scripts/lease.sh recall '")
                        .append(workingSet).append("'\n");
                return new Outcome(1, out.toString(), err.toString());
            }
        }
    }

    /**
     * Refreshes the renewal stamp of a lease this machine holds.
     *
     * @param workingSet the working-set name
     * @return the outcome
     */
    public Outcome renew(String workingSet) {
        StringBuilder err = new StringBuilder();
        String state = state(workingSet, err);
        if (!"MINE".equals(state)) {
            err.append("not held by this machine — not renewed\n");
            return new Outcome(1, "", err.toString());
        }
        Optional<LeaseRecord> record = LeaseRecord.read(leaseFile(workingSet));
        String me = machineId(err).orElse("");
        List<RepoStamp> stamps = RefStamper.collect(ikeDev, workingSet);
        refreshBundles(workingSet, record, me, stamps, false, err);
        write(workingSet, RecordState.HELD, me,
                record.map(LeaseRecord::epoch).orElse(0L),
                record.map(LeaseRecord::acquired).orElse(""),
                ISO.format(Instant.now()),
                stamps);
        return new Outcome(0, "", err.toString());
    }

    /**
     * Returns a lease this machine holds, so the next machine takes it
     * silently. Named {@code returnLease} because {@code return} is a Java
     * keyword; the verb everywhere else is {@code return}.
     *
     * @param workingSet the working-set name
     * @return the outcome
     */
    public Outcome returnLease(String workingSet) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Optional<String> me = machineId(err);
        if (me.isEmpty()) {
            return new Outcome(2, out.toString(), err.toString());
        }
        if (!Files.isRegularFile(leaseFile(workingSet))) {
            out.append("no lease record for ").append(workingSet).append('\n');
            return new Outcome(0, out.toString(), err.toString());
        }
        String state = state(workingSet, err);
        if (!"MINE".equals(state)) {
            // Describe first: its own reconcile chatter must land before
            // this message starts, as the shell's command substitution did.
            String description = describe(workingSet, err);
            err.append("not held by this machine (").append(description)
                    .append(") — nothing returned\n");
            return new Outcome(1, out.toString(), err.toString());
        }
        Optional<LeaseRecord> record = LeaseRecord.read(leaseFile(workingSet));
        // The return stamp is the one that matters most: it records the
        // refs the machine switch will align to (ike-issues#1069). For a
        // sibling the bundles carry the commits themselves (#1216); they
        // are written before the record, so a record never announces a
        // return whose history has not been written.
        List<RepoStamp> stamps = RefStamper.collect(ikeDev, workingSet);
        refreshBundles(workingSet, record, me.get(), stamps, true, err);
        write(workingSet, RecordState.RETURNED, me.get(),
                record.map(LeaseRecord::epoch).orElse(0L),
                record.map(LeaseRecord::acquired).orElse(""),
                ISO.format(Instant.now()),
                stamps);
        out.append("returned ").append(workingSet).append('\n');
        return new Outcome(0, out.toString(), err.toString());
    }

    /**
     * Describes every lease record.
     *
     * @return the outcome
     */
    public Outcome list() {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream =
                Files.newDirectoryStream(ikeDev.resolve("leases"), "*.lease")) {
            stream.forEach(files::add);
        } catch (IOException e) {
            // No directory: same as no records.
        }
        files.sort(java.util.Comparator.comparing(p ->
                p.getFileName().toString()));
        if (files.isEmpty()) {
            out.append("no lease records in ")
                    .append(ikeDev.resolve("leases")).append('\n');
            return new Outcome(0, out.toString(), err.toString());
        }
        for (Path file : files) {
            String name = file.getFileName().toString();
            String ws = name.substring(0, name.length() - ".lease".length());
            out.append(describe(ws, err)).append('\n');
        }
        return new Outcome(0, out.toString(), err.toString());
    }

    /**
     * Resolves a path to the working set containing it — textually, never
     * touching the filesystem, exactly as the shell did.
     *
     * @param path the path, absolute or relative
     * @return the outcome: the name on stdout, or exit 1 for paths outside
     *         the development folder, its root, or its infrastructure
     */
    public Outcome resolve(String path) {
        if (path == null || path.isEmpty()) {
            return new Outcome(1, "", "");
        }
        String p = path.startsWith("/") ? path
                : workingDirectory + "/" + path;
        while (p.contains("/./")) {
            p = p.replace("/./", "/");
        }
        if (p.endsWith("/.")) {
            p = p.substring(0, p.length() - 2);
        }
        String prefix = ikeDev + "/";
        if (!p.startsWith(prefix)) {
            return new Outcome(1, "", "");
        }
        String rel = p.substring(prefix.length());
        int slash = rel.indexOf('/');
        if (slash >= 0) {
            rel = rel.substring(0, slash);
        }
        if (rel.isEmpty() || EXCLUDED_ROOTS.contains(rel)) {
            return new Outcome(1, "", "");
        }
        return new Outcome(0, rel + "\n", "");
    }

    // ── state, description, reconciliation ──────────────────────────

    /**
     * Computes the lease's state, reconciling conflict copies first.
     *
     * @return FREE, MINE, EXPIRED or LIVE; {@code null} when the machine
     *         identity is missing (the shell's error path)
     */
    private String state(String workingSet, StringBuilder err) {
        reconcile(workingSet, err);
        Optional<LeaseRecord> record = LeaseRecord.read(leaseFile(workingSet));
        if (record.isEmpty()) {
            return "FREE";
        }
        if (record.get().state() == RecordState.RETURNED) {
            return "FREE";
        }
        Optional<String> me = machineId(err);
        if (me.isEmpty()) {
            return null;
        }
        if (record.get().holder().equals(me.get())) {
            return "MINE";
        }
        Optional<Long> renewed = isoToEpoch(record.get().renewed());
        if (renewed.isEmpty()) {
            return "LIVE";      // unparseable stamp: assume live, stay safe
        }
        long ttl = ttlToSeconds(record.get().ttl());
        long age = Instant.now().getEpochSecond() - renewed.get();
        return age > ttl ? "EXPIRED" : "LIVE";
    }

    private String describe(String workingSet, StringBuilder err) {
        String state = state(workingSet, err);
        Optional<LeaseRecord> record = LeaseRecord.read(leaseFile(workingSet));
        if (record.isEmpty()) {
            return workingSet + ": FREE (no lease record)";
        }
        LeaseRecord r = record.get();
        long age = isoToEpoch(r.renewed())
                .map(t -> (Instant.now().getEpochSecond() - t) / 60)
                .orElse(0L);
        return switch (state == null ? "" : state) {
            case "FREE" -> workingSet + ": FREE (returned by " + r.holder()
                    + ", epoch " + r.epoch() + ")";
            case "MINE" -> workingSet + ": MINE (" + r.holder() + ", epoch "
                    + r.epoch() + ", renewed " + age + "m ago)";
            case "EXPIRED" -> workingSet + ": EXPIRED (was " + r.holder()
                    + ", epoch " + r.epoch() + ", renewed " + age + "m ago)";
            case "LIVE" -> workingSet + ": HELD by " + r.holder() + " (epoch "
                    + r.epoch() + ", renewed " + age + "m ago)";
            default -> "";
        };
    }

    /**
     * Folds {@code .sync-conflict-*} copies into the main record: highest
     * epoch wins, ties break on the lexicographically greatest machine id,
     * and the copies are removed — the healing that runs on every read.
     */
    private void reconcile(String workingSet, StringBuilder err) {
        List<Path> conflicts = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                ikeDev.resolve("leases"),
                workingSet + ".sync-conflict-*.lease")) {
            stream.forEach(conflicts::add);
        } catch (IOException e) {
            return;
        }
        if (conflicts.isEmpty()) {
            return;
        }
        Path main = leaseFile(workingSet);
        Path bestFile = main;
        long bestEpoch = LeaseRecord.read(main)
                .map(LeaseRecord::epoch).orElse(0L);
        String bestHolder = LeaseRecord.read(main)
                .map(LeaseRecord::holder).orElse("");
        for (Path conflict : conflicts) {
            long epoch = LeaseRecord.read(conflict)
                    .map(LeaseRecord::epoch).orElse(0L);
            String holder = LeaseRecord.read(conflict)
                    .map(LeaseRecord::holder).orElse("");
            if (epoch > bestEpoch
                    || (epoch == bestEpoch
                            && holder.compareTo(bestHolder) > 0)) {
                bestFile = conflict;
                bestEpoch = epoch;
                bestHolder = holder;
            }
        }
        try {
            if (!bestFile.equals(main)) {
                Files.copy(bestFile, main,
                        StandardCopyOption.REPLACE_EXISTING);
                err.append("reconciled ").append(workingSet)
                        .append(": contested take resolved to ")
                        .append(bestHolder).append(" (epoch ")
                        .append(bestEpoch).append(")\n");
            }
            for (Path conflict : conflicts) {
                Files.deleteIfExists(conflict);
            }
        } catch (IOException e) {
            // Reads must not fail on a half-healed split; next read retries.
        }
    }

    // ── plumbing ────────────────────────────────────────────────────

    private boolean confirmHold(String workingSet, boolean quiet,
                                StringBuilder out, StringBuilder err) {
        if (!quiet) {
            out.append("confirming (waiting ").append(settleSeconds)
                    .append("s for the sync layer)…\n");
        }
        try {
            Thread.sleep(settleSeconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String state = state(workingSet, err);
        if ("MINE".equals(state)) {
            if (!quiet) {
                out.append("confirmed: ").append(workingSet)
                        .append(" is held here\n");
            }
            return true;
        }
        err.append("LOST THE RACE for ").append(workingSet)
                .append(" — another machine's claim won.\n");
        err.append("  ").append(describe(workingSet, err)).append('\n');
        err.append("  Do not write to this working set; take it again "
                + "deliberately if you meant to have it.\n");
        return false;
    }

    /**
     * Keeps a sibling's bundles in step with its committed history
     * (IKE-Network/ike-issues#1216). On a return every member is written;
     * on a renew only when this machine's heads moved since its own last
     * stamps, so the half-life renew stays cheap when nothing was
     * committed. Bundle trouble is reported, never fatal: the lease write
     * that follows is the fencing state, and it must not depend on git.
     */
    private void refreshBundles(String workingSet,
                                Optional<LeaseRecord> previous, String me,
                                List<RepoStamp> stamps, boolean returning,
                                StringBuilder err) {
        WorkingSetName name;
        try {
            name = new WorkingSetName(workingSet);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (!name.isSibling()) {
            return;
        }
        boolean headsMoved = previous
                .filter(r -> r.holder().equals(me))
                .map(r -> !r.stamps().equals(stamps))
                .orElse(true);
        if (!returning && !headsMoved
                && SiblingBundles.complete(ikeDev, name, stamps)) {
            return;
        }
        for (String problem : SiblingBundles.write(ikeDev, name, git)) {
            err.append("bundle: ").append(problem).append('\n');
        }
    }

    private void write(String workingSet, RecordState state, String holder,
                       long epoch, String acquired, String renewed,
                       List<RepoStamp> stamps) {
        LeaseRecord record = new LeaseRecord(workingSet, state, holder, epoch,
                acquired, renewed, defaultTtl, stamps);
        try {
            Path dir = ikeDev.resolve("leases");
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, ".lease.", "");
            Files.writeString(tmp, record.serialize(), StandardCharsets.UTF_8);
            Files.move(tmp, leaseFile(workingSet),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            SyncthingNudge.nudge(home);
        } catch (IOException e) {
            // The shell's mv failure surfaced as a failed command; the
            // caller's exit code carries it. Nothing more to do here.
        }
    }

    private Optional<String> machineId(StringBuilder err) {
        Path idFile = home.resolve(".ike-machine-id");
        if (!Files.isRegularFile(idFile)) {
            err.append("ERROR: no ").append(idFile)
                    .append(" — run scripts/install-machine-id.sh\n");
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(idFile, StandardCharsets.UTF_8)
                    .replaceAll("\\s", ""));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private Path leaseFile(String workingSet) {
        return ikeDev.resolve("leases").resolve(workingSet + ".lease");
    }

    private static Optional<Long> isoToEpoch(String stamp) {
        if (stamp == null || stamp.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.from(ISO.parse(stamp))
                    .getEpochSecond());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The shell's deliberately crude duration parser: {@code PT<n>H|M|S},
     * anything else 600 seconds. Preserved, not improved — the two
     * implementations must agree on every input.
     */
    static long ttlToSeconds(String ttl) {
        Matcher m = TTL.matcher(ttl == null ? "" : ttl);
        if (!m.matches()) {
            return 600L;
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "H" -> n * 3600L;
            case "M" -> n * 60L;
            default -> n;
        };
    }
}
