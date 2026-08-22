package network.ike.lease.core;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tells the local Syncthing to scan {@code leases/} right now, instead of
 * waiting to notice — the delivery guarantee every lease write depends on.
 *
 * <p>Measured 2026-08-12: a lease written on one machine did not reach
 * the other until a scan was forced; the filesystem watcher had not
 * picked up the directory, leaving the hourly rescan as the fallback,
 * and a bus with an hour of latency is not a bus. The call is
 * <b>synchronous</b> on purpose: backgrounding it loses the request when
 * the process exits immediately after — exactly what a peer running the
 * command over ssh does.
 *
 * <p>Best-effort and non-fatal, like the shell original: no Syncthing
 * config, no curl-equivalent reachability, or any error at all, and the
 * lease is still written and still eventually syncs.
 */
final class SyncthingNudge {

    private static final Pattern API_KEY = Pattern.compile("<apikey>([^<]+)");
    private static final Pattern FOLDER = Pattern.compile(
            "<folder id=\"([^\"]+)\" label=\"[^\"]*\" path=\"[^\"]*ike-dev\"");

    private SyncthingNudge() { }

    /**
     * Fires the targeted scan of {@code leases/}, swallowing every
     * failure.
     *
     * @param home the home directory whose Syncthing config to consult
     */
    static void nudge(Path home) {
        try {
            Path config = home.resolve(
                    "Library/Application Support/Syncthing/config.xml");
            if (!Files.isRegularFile(config)) {
                config = home.resolve(".config/syncthing/config.xml");
            }
            if (!Files.isRegularFile(config)) {
                return;
            }
            String xml = Files.readString(config, StandardCharsets.UTF_8);
            Matcher key = API_KEY.matcher(xml);
            Matcher folder = FOLDER.matcher(xml);
            if (!key.find() || !folder.find()) {
                return;
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:8384/rest/db/scan?folder="
                            + folder.group(1) + "&sub=leases"))
                    .header("X-API-Key", key.group(1))
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            // Best-effort by contract.
        }
    }
}
