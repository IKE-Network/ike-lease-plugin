package network.ike.lease.core;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * The lease protocol's command-line face — argv-compatible with
 * {@code lease.sh} v2, which is now a thin wrapper that {@code exec}s
 * this class (IKE-Network/ike-issues#1067). Same verbs, same flags, same
 * messages, same exit codes; the golden equivalence tests hold the two
 * to that.
 *
 * <p>Environment, exactly as the shell read it: {@code IKE_DEV}
 * (default {@code $HOME/ike-dev}), {@code IKE_LEASE_TTL} (default
 * {@code PT10M}), {@code IKE_LEASE_SETTLE_SECONDS} (default 25).
 */
public final class LeaseProtocolCli {

    private LeaseProtocolCli() { }

    /**
     * Entry point.
     *
     * @param args {@code status|ensure|acquire|renew|release|list|resolve}
     *             with their arguments, as {@code lease.sh} always took them
     */
    public static void main(String[] args) {
        String home = System.getProperty("user.home");
        String ikeDev = orDefault(System.getenv("IKE_DEV"),
                home + "/ike-dev");
        String ttl = orDefault(System.getenv("IKE_LEASE_TTL"), "PT10M");
        long settle = parseSettle(
                System.getenv("IKE_LEASE_SETTLE_SECONDS"), 25L);
        LeaseProtocol protocol = new LeaseProtocol(Path.of(ikeDev),
                Path.of(home), Path.of(System.getProperty("user.dir")),
                ttl, settle);
        System.exit(run(protocol, settle, args));
    }

    /**
     * Dispatches one invocation.
     *
     * @param protocol the protocol instance
     * @param settle   the settle window, for the usage text
     * @param args     the argv
     * @return the process exit code
     */
    static int run(LeaseProtocol protocol, long settle, String[] args) {
        if (args.length < 1) {
            return usage(settle);
        }
        String verb = args[0];
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        LeaseProtocol.Outcome outcome = switch (verb) {
            case "status" -> rest.isEmpty() ? null
                    : protocol.status(rest.get(0));
            case "acquire" -> rest.isEmpty() ? null
                    : protocol.acquire(rest.get(0),
                            rest.contains("--force"),
                            rest.contains("--quiet"),
                            rest.contains("--confirm"));
            case "ensure" -> rest.isEmpty() ? null
                    : protocol.ensure(rest.get(0), rest.contains("--confirm"));
            case "renew" -> rest.isEmpty() ? null
                    : protocol.renew(rest.get(0));
            case "release" -> rest.isEmpty() ? null
                    : protocol.release(rest.get(0));
            case "list" -> protocol.list();
            case "resolve" -> rest.isEmpty() ? null
                    : protocol.resolve(rest.get(0));
            default -> null;
        };
        if (outcome == null) {
            return usage(settle);
        }
        System.out.print(outcome.stdout());
        System.err.print(outcome.stderr());
        return outcome.exitCode();
    }

    private static int usage(long settle) {
        System.err.print("""
                usage: lease.sh <command> [args]

                  status  <ws>              describe the lease; exit 1 if held live elsewhere
                  ensure  <ws> [--confirm]  hold it if that needs no human decision
                  acquire <ws> [--force] [--quiet] [--confirm]
                  renew   <ws>              refresh the renewal stamp
                  release <ws>              give up a lease this machine holds
                  list                      every lease record and its state
                  resolve <path>            the working set a path belongs to

                  --confirm  read the record back after the sync-layer settle
                """
                + "             window (" + settle + "s) and fail if\n"
                + """
                             another machine's claim won. For consequential steps
                             only — never the per-tool-call fence.
                """);
        return 2;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static long parseSettle(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
