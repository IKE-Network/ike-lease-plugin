package network.ike.lease.core;

import java.util.SequencedMap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing and lookup of the synced origin manifest. */
class OriginManifestTest {

    private static final String CONTENT = """
            # origins.conf — comment
            ike-tooling https://github.com/IKE-Network/ike-tooling.git

            ike-komet-wsr https://github.com/IKE-Network/ike-komet-wsr.git
            ike-komet-wsr/komet-desktop https://github.com/IKE-Network/komet-desktop.git
            ike-komet-wsr/tinkar-core https://github.com/IKE-Network/tinkar-core.git
            """;

    @Test
    void looksUpByRelativePath() {
        OriginManifest manifest = OriginManifest.parse(CONTENT);
        assertEquals(4, manifest.size());
        assertEquals("https://github.com/IKE-Network/komet-desktop.git",
                manifest.originOf("ike-komet-wsr/komet-desktop").orElseThrow());
        assertTrue(manifest.originOf("absent").isEmpty());
    }

    @Test
    void entriesForAWorkingSetIncludeNestedMembersShallowestFirst() {
        OriginManifest manifest = OriginManifest.parse(CONTENT);
        SequencedMap<String, String> entries = manifest.entriesFor(
                new WorkingSetName("ike-komet-wsr"));
        assertEquals(3, entries.size());
        assertEquals("ike-komet-wsr", entries.firstEntry().getKey());
    }

    @Test
    void entriesForDoesNotMatchNamePrefixesOfOtherWorkingSets() {
        OriginManifest manifest = OriginManifest.parse(
                "ike-doc https://example/a.git\n"
                        + "ike-docs https://example/b.git\n");
        assertEquals(1, manifest.entriesFor(
                new WorkingSetName("ike-doc")).size());
    }

    @Test
    void malformedLinesAreRejectedWithTheLineNumber() {
        IllegalArgumentException problem = assertThrows(
                IllegalArgumentException.class,
                () -> OriginManifest.parse("one-token-only\n"));
        assertTrue(problem.getMessage().contains("line 1"));
    }
}
