package network.ike.lease.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The IDE's build lock against the released {@code ike-workspace-extension}
 * 15's, between real processes (IKE-Network/ike-issues#1315): a Maven-held
 * lock makes the IDE wait and name the holder, an IDE-held lock makes Maven
 * wait and name the IDE, and neither side's death wedges the tree.
 */
class BuildLockInteropTest {

    private static final String IDE_GOALS = "IntelliJ make";
    private static final String IDE_MODULES = "kview, komet-framework";

    @TempDir
    Path tree;

    private final List<Probe> probes = new ArrayList<>();
    private final BuildLockClient client = new BuildLockClient(BuildLockClient.defaultHelperCommand());

    @AfterEach
    void stop() {
        probes.forEach(probe -> probe.process.destroyForcibly());
        client.close();
    }

    // ---- a Maven build holds the tree ----------------------------------------------------

    @Test
    void ideWaitsForMavenAndNamesIt() throws Exception {
        Probe maven = extension(Duration.ofSeconds(30));
        assertTrue(maven.next(Duration.ofSeconds(20)).startsWith("ACQUIRED"));

        Recorder waiter = new Recorder(() -> false, () -> false);
        Thread finisher = new Thread(() -> {
            sleep(Duration.ofMillis(1500));
            maven.finish();
        });
        finisher.start();
        BuildLockClient.Outcome outcome = client.acquire(tree, IDE_GOALS, IDE_MODULES, waiter);
        finisher.join();

        BuildLockClient.Acquired acquired = assertInstanceOf(BuildLockClient.Acquired.class, outcome);
        assertTrue(acquired.waited().toMillis() >= 1000, "waited only " + acquired.waited());
        BuildLock.Holder holder = acquired.holder().orElseThrow();
        assertEquals(maven.process.pid(), holder.pid(), "the IDE named the Maven build");
        assertEquals("clean compile", holder.goals());
        assertEquals("komet/kview", holder.projects());
        assertFalse(waiter.seen.isEmpty(), "the waiter was told while it waited");
        assertTrue(waiter.seen.stream().allMatch(seen -> seen.isEmpty()
                || seen.get().pid() == maven.process.pid()));
    }

    @Test
    void ideCanCancelOrBuildAnywayWhileMavenHolds() throws Exception {
        Probe maven = extension(Duration.ofSeconds(30));
        assertTrue(maven.next(Duration.ofSeconds(20)).startsWith("ACQUIRED"));

        BuildLockClient.Outcome cancelled = client.acquire(tree, IDE_GOALS, IDE_MODULES,
                new Recorder(() -> true, () -> false));
        BuildLockClient.Cancelled cancel = assertInstanceOf(BuildLockClient.Cancelled.class, cancelled);
        assertEquals(maven.process.pid(), cancel.holder().orElseThrow().pid());

        BuildLockClient.Outcome anyway = client.acquire(tree, IDE_GOALS, IDE_MODULES,
                new Recorder(() -> false, () -> true));
        BuildLockClient.Overridden overridden = assertInstanceOf(BuildLockClient.Overridden.class, anyway);
        assertEquals(maven.process.pid(), overridden.holder().orElseThrow().pid());

        // Building anyway took nothing: Maven still holds, and its record is intact.
        assertEquals(maven.process.pid(), BuildLock.read(BuildLock.lockFile(tree)).orElseThrow().pid());
        assertFalse(client.release(tree), "nothing to release after an unlocked build");
    }

    @Test
    void aKilledMavenBuildNeverWedgesTheIde() throws Exception {
        Probe maven = extension(Duration.ofSeconds(30));
        assertTrue(maven.next(Duration.ofSeconds(20)).startsWith("ACQUIRED"));
        maven.process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);

        BuildLockClient.Outcome outcome = client.acquire(tree, IDE_GOALS, IDE_MODULES,
                new Recorder(() -> false, () -> false));
        assertInstanceOf(BuildLockClient.Acquired.class, outcome);
    }

    // ---- the IDE holds the tree ----------------------------------------------------------

    @Test
    void mavenWaitsForTheIdeAndNamesIt() throws Exception {
        assertInstanceOf(BuildLockClient.Acquired.class, client.acquire(tree, IDE_GOALS, IDE_MODULES,
                new Recorder(() -> false, () -> false)));
        long helper = client.helperPid().orElseThrow();

        Probe maven = extension(Duration.ofSeconds(30));
        List<String> notices = new ArrayList<>();
        String first = maven.next(Duration.ofSeconds(20));
        assertTrue(first.startsWith("WAITING "), "Maven waited: " + first);
        notices.add(first);
        assertTrue(first.contains("pid " + helper + " on "), first);
        assertTrue(first.contains(": " + IDE_GOALS + " -pl " + IDE_MODULES), first);

        sleep(Duration.ofMillis(1000));
        assertTrue(client.release(tree));
        String result = maven.result(Duration.ofSeconds(20), notices);
        assertTrue(result.startsWith("ACQUIRED waited="), result);
        long waited = Long.parseLong(result.substring("ACQUIRED waited=".length()));
        assertTrue(waited >= 900, "Maven waited only " + waited + " ms");
    }

    @Test
    void mavenTimesOutNamingTheIde() throws Exception {
        assertInstanceOf(BuildLockClient.Acquired.class, client.acquire(tree, IDE_GOALS, IDE_MODULES,
                new Recorder(() -> false, () -> false)));
        long helper = client.helperPid().orElseThrow();

        Probe maven = extension(Duration.ofMillis(1200));
        String result = maven.result(Duration.ofSeconds(20), new ArrayList<>());
        assertTrue(result.startsWith("TIMEDOUT "), result);
        assertTrue(result.contains("held by pid " + helper + " on "), result);
        assertTrue(result.contains(IDE_GOALS), result);
    }

    @Test
    void aDeadIdeNeverWedgesMaven() throws Exception {
        assertInstanceOf(BuildLockClient.Acquired.class, client.acquire(tree, IDE_GOALS, IDE_MODULES,
                new Recorder(() -> false, () -> false)));
        long helper = client.helperPid().orElseThrow();
        ProcessHandle.of(helper).orElseThrow().destroyForcibly();
        ProcessHandle.of(helper).ifPresent(handle -> handle.onExit().join());

        Probe maven = extension(Duration.ofSeconds(30));
        assertTrue(maven.result(Duration.ofSeconds(20), new ArrayList<>()).startsWith("ACQUIRED"));
        assertFalse(client.release(tree), "a dead helper's lock died with it");
    }

    @Test
    void closingTheClientReleasesAndTheNextCompileRestartsTheHelper() throws Exception {
        assertInstanceOf(BuildLockClient.Acquired.class, client.acquire(tree, IDE_GOALS, IDE_MODULES,
                new Recorder(() -> false, () -> false)));
        long first = client.helperPid().orElseThrow();
        client.close();

        Probe maven = extension(Duration.ofSeconds(30));
        assertTrue(maven.result(Duration.ofSeconds(20), new ArrayList<>()).startsWith("ACQUIRED"));
        maven.finish();

        assertInstanceOf(BuildLockClient.Acquired.class, client.acquire(tree, IDE_GOALS, IDE_MODULES,
                new Recorder(() -> false, () -> false)));
        assertTrue(client.helperPid().orElseThrow() != first, "a fresh helper");
    }

    @Test
    void aSecondAcquireOfAHeldTreeIsTheSameHold() throws Exception {
        Recorder idle = new Recorder(() -> false, () -> false);
        assertInstanceOf(BuildLockClient.Acquired.class, client.acquire(tree, IDE_GOALS, IDE_MODULES, idle));
        assertInstanceOf(BuildLockClient.Acquired.class, client.acquire(tree, IDE_GOALS, IDE_MODULES, idle));
        assertTrue(idle.seen.isEmpty(), "never waited on itself");
        assertTrue(client.release(tree));
        assertFalse(client.release(tree), "release is idempotent");
    }

    // ---- the record ----------------------------------------------------------------------

    @Test
    void theRecordIsTheExtensionsFormatBothWays() throws Exception {
        Class<?> holderType = Class.forName("network.ike.extension.BuildLock$Holder");
        Method current = holderType.getDeclaredMethod("current", Path.class, List.class, List.class);
        current.setAccessible(true);
        Method render = holderType.getDeclaredMethod("render");
        render.setAccessible(true);
        Method parse = holderType.getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        Method describe = holderType.getDeclaredMethod("describe", Instant.class);
        describe.setAccessible(true);

        Object theirs = current.invoke(null, tree, List.of("clean", "install"), List.of("a", "b/c"));
        String theirText = (String) render.invoke(theirs);
        BuildLock.Holder ours = BuildLock.Holder.parse(theirText).orElseThrow();
        assertEquals(theirText, ours.render(), "the extension's record reads and re-renders identically");
        Instant now = ours.started().plusSeconds(75);
        assertEquals(describe.invoke(theirs, now), ours.describe(now), "the same description");

        BuildLock.Holder ide = new BuildLock.Holder(4242, "graphlet.local",
                Instant.parse("2026-10-21T18:00:00.123Z"), IDE_GOALS, IDE_MODULES, tree);
        Optional<?> back = (Optional<?>) parse.invoke(null, ide.render());
        assertTrue(back.isPresent(), "the extension reads the IDE's record");
        assertEquals(ide.render(), render.invoke(back.get()));
    }

    @Test
    void theHelperWritesTheRecordThroughTheLockedChannel() throws Exception {
        assertInstanceOf(BuildLockClient.Acquired.class, client.acquire(tree, IDE_GOALS, IDE_MODULES,
                new Recorder(() -> false, () -> false)));
        // This process holds nothing, so reading is safe here.
        BuildLock.Holder holder = BuildLock.read(BuildLock.lockFile(tree)).orElseThrow();
        assertEquals(client.helperPid().orElseThrow(), holder.pid());
        assertEquals(IDE_GOALS, holder.goals());
        assertEquals(IDE_MODULES, holder.projects());
        assertEquals(tree.toAbsolutePath().normalize(), holder.root());
    }

    // ---- which tree ----------------------------------------------------------------------

    @Test
    void theTreeIsTheNearestMvnRootWhenItLoadsTheExtension() throws IOException {
        Path ws = workingSet(tree.resolve("ike-komet-wsr꞉document-surface"), "komet", "tinkar-core");
        Path deep = Files.createDirectories(ws.resolve("komet/kview/src/main/java"));
        assertEquals(Optional.of(ws), BuildLock.treeRoot(ws));
        assertEquals(Optional.of(ws), BuildLock.treeRoot(deep));

        // A member with its own .mvn, built from its own root, locks the working set.
        Path member = ws.resolve("komet");
        Files.createDirectories(member.resolve(".mvn"));
        assertEquals(Optional.of(ws), BuildLock.treeRoot(member));

        // A directory with its own .mvn that the manifest does not list is its own tree.
        Path stray = ws.resolve("scratch");
        Files.createDirectories(stray.resolve(".mvn"));
        Files.writeString(stray.resolve(".mvn/extensions.xml"),
                "<extensions><extension><artifactId>ike-workspace-extension</artifactId></extension></extensions>");
        assertEquals(Optional.of(stray), BuildLock.treeRoot(stray));
    }

    @Test
    void aTreeWhoseMavenTakesNoLockIsLeftAlone() throws IOException {
        Path repo = tree.resolve("ike-lease-plugin");
        Files.createDirectories(repo.resolve(".mvn"));
        Files.writeString(repo.resolve(".mvn/extensions.xml"),
                "<extensions><extension><artifactId>ike-version-management-extension</artifactId>"
                        + "</extension></extensions>");
        assertEquals(Optional.empty(), BuildLock.treeRoot(repo));
        assertEquals(Optional.empty(), BuildLock.treeRoot(Files.createDirectories(tree.resolve("plain"))));
    }

    // ---- helpers -------------------------------------------------------------------------

    private static Path workingSet(Path root, String... members) throws IOException {
        Files.createDirectories(root.resolve(".mvn"));
        Files.writeString(root.resolve(".mvn/extensions.xml"), """
                <extensions>
                    <extension>
                        <groupId>network.ike.tooling</groupId>
                        <artifactId>ike-workspace-extension</artifactId>
                        <version>15</version>
                    </extension>
                </extensions>
                """);
        StringBuilder manifest = new StringBuilder("schema-version: \"1.0\"\nsubprojects:\n");
        for (String member : members) {
            manifest.append("  # the ").append(member).append(" member\n");
            manifest.append("  ").append(member).append(":\n    type: software\n");
            Files.createDirectories(root.resolve(member));
        }
        Files.writeString(root.resolve("working-set.yaml"), manifest);
        return root;
    }

    /** Records what a waiting acquire was told; answers from the given suppliers. */
    private static final class Recorder implements BuildLockClient.Waiter {
        final List<Optional<BuildLock.Holder>> seen = new CopyOnWriteArrayList<>();
        private final BooleanSupplier cancelled;
        private final BooleanSupplier buildAnyway;

        Recorder(BooleanSupplier cancelled, BooleanSupplier buildAnyway) {
            this.cancelled = cancelled;
            this.buildAnyway = buildAnyway;
        }

        @Override
        public void waiting(Optional<BuildLock.Holder> holder, Duration waited) {
            seen.add(holder);
        }

        @Override
        public boolean cancelled() {
            return cancelled.getAsBoolean();
        }

        @Override
        public boolean buildAnyway() {
            return buildAnyway.getAsBoolean();
        }
    }

    /** An extension probe process and the lines it has printed. */
    private record Probe(Process process, BlockingQueue<String> lines, AtomicBoolean finished) {

        String next(Duration within) throws InterruptedException {
            String line = lines.poll(within.toMillis(), TimeUnit.MILLISECONDS);
            assertNotNull(line, "the probe said nothing within " + within);
            return line;
        }

        /** The next line that is not a waiting notice, collecting the notices. */
        String result(Duration within, List<String> notices) throws InterruptedException {
            Instant deadline = Instant.now().plus(within);
            while (true) {
                String line = next(Duration.between(Instant.now(), deadline));
                if (!line.startsWith("WAITING ")) {
                    return line;
                }
                notices.add(line);
            }
        }

        void finish() {
            if (finished.compareAndSet(false, true)) {
                try {
                    process.getOutputStream().close();
                    process.waitFor(10, TimeUnit.SECONDS);
                } catch (IOException | InterruptedException e) {
                    process.destroyForcibly();
                }
            }
        }
    }

    private Probe extension(Duration timeout) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ExtensionLockProbe.class.getName(), tree.toString(), Long.toString(timeout.toMillis()))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // The probe ended.
            }
        });
        reader.setDaemon(true);
        reader.start();
        Probe probe = new Probe(process, lines, new AtomicBoolean());
        probes.add(probe);
        return probe;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
