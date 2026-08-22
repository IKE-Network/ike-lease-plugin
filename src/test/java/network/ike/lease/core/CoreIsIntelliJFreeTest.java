package network.ike.lease.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one-core/two-thin-hosts contract, enforced
 * (IKE-Network/ike-issues#1057): the core package must be loadable on the
 * headless CLI host's classpath, where no IntelliJ platform jar exists,
 * so nothing in it may touch the platform. Hosts contribute the git
 * runner, the interaction surface, and the trigger — nothing
 * decision-shaped leaves the core, and nothing platform-shaped enters it.
 */
class CoreIsIntelliJFreeTest {

    @Test
    void corePackageImportsNothingFromTheIntelliJPlatform()
            throws IOException {
        Path corePackage = Path.of("src/main/java/network/ike/lease/core");
        assertTrue(Files.isDirectory(corePackage),
                "core package moved? expected " + corePackage.toAbsolutePath());
        try (Stream<Path> sources = Files.list(corePackage)) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                List<String> offending = Files
                        .readString(source, StandardCharsets.UTF_8)
                        .lines()
                        .filter(line -> line.strip().startsWith("import ")
                                && line.contains("com.intellij"))
                        .toList();
                assertTrue(offending.isEmpty(),
                        source.getFileName() + " imports the platform: "
                                + offending);
            }
        }
    }
}
