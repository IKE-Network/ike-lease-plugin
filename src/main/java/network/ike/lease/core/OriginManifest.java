package network.ike.lease.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedMap;

/**
 * The synced origin manifest: {@code scripts/origins.conf}.
 *
 * <p>Data, never executable scripts arriving by sync — the rule the
 * retired bootstrap taught (IKE-Network/ike-issues#1006). One line per
 * GitHub-rooted repository: the path relative to {@code ~/ike-dev},
 * whitespace, the canonical clone URL. Keying by relative path rather
 * than working-set name lets one entry name a workspace's nested member
 * ({@code ike-komet-wsr/komet-desktop}) with the same syntax as a
 * single-repo root. Sibling repositories never appear — their origins
 * are local parent paths, derived, not declared
 * (IKE-Network/ike-issues#992).
 *
 * <p>{@code #} starts a comment; blank lines are ignored. URLs are
 * canonical {@code https://github.com/...} forms: each machine's own
 * {@code insteadOf} rewrites route them to that machine's SSH
 * configuration, which keeps the manifest machine-neutral.
 */
public final class OriginManifest {

    /** The manifest's path relative to the development-folder root. */
    public static final String RELATIVE_PATH = "scripts/origins.conf";

    private final SequencedMap<String, String> originsByPath;

    private OriginManifest(SequencedMap<String, String> originsByPath) {
        this.originsByPath = originsByPath;
    }

    /**
     * Loads the manifest from its home under the given development folder.
     *
     * <p>A missing file loads as an empty manifest: materialization then
     * refuses per repository with the remedy of adding an entry, which is
     * a better failure than refusing to start at all.
     *
     * @param ikeDev the development-folder root, normally {@code ~/ike-dev}
     * @return the loaded manifest
     * @throws UncheckedIOException if the file exists but cannot be read
     */
    public static OriginManifest load(Path ikeDev) {
        Path file = ikeDev.resolve(RELATIVE_PATH);
        if (!Files.exists(file)) {
            return new OriginManifest(new LinkedHashMap<>());
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Parses manifest content.
     *
     * @param content the manifest text
     * @return the parsed manifest
     * @throws IllegalArgumentException if a non-comment line does not have
     *                                  exactly a path and a URL
     */
    public static OriginManifest parse(String content) {
        SequencedMap<String, String> origins = new LinkedHashMap<>();
        int lineNumber = 0;
        for (String raw : content.lines().toList()) {
            lineNumber++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        RELATIVE_PATH + " line " + lineNumber
                                + ": expected '<relative-path> <url>', got: "
                                + raw);
            }
            origins.put(parts[0], parts[1]);
        }
        return new OriginManifest(origins);
    }

    /**
     * Looks up the origin URL for a repository.
     *
     * @param relativePath the repository path relative to {@code ~/ike-dev}
     * @return the declared origin URL, or empty when the manifest has no
     *         entry for the path
     */
    public Optional<String> originOf(String relativePath) {
        return Optional.ofNullable(originsByPath.get(relativePath));
    }

    /**
     * Returns every entry belonging to one working set: the entry naming
     * the working set itself plus every entry nested under it, shallowest
     * first.
     *
     * @param workingSet the working set's directory name
     * @return the matching entries as path → URL, in path order
     */
    public SequencedMap<String, String> entriesFor(WorkingSetName workingSet) {
        SequencedMap<String, String> matches = new LinkedHashMap<>();
        originsByPath.entrySet().stream()
                .filter(entry -> entry.getKey().equals(workingSet.value())
                        || entry.getKey().startsWith(workingSet.value() + "/"))
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> matches.put(entry.getKey(), entry.getValue()));
        return matches;
    }

    /**
     * Reports the number of entries.
     *
     * @return the entry count
     */
    public int size() {
        return originsByPath.size();
    }
}
