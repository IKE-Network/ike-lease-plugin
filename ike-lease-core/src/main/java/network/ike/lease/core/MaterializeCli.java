package network.ike.lease.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The headless host of the materializer core
 * (IKE-Network/ike-issues#1057).
 *
 * <p>Per the one-core/two-thin-hosts contract, this class contributes
 * exactly a trigger (its {@code main}), a git runner
 * ({@link ProcessGitRunner}), and an interaction surface (a line-per-repo
 * text report and an exit code, with every decision point defaulting to
 * refuse-and-report — never a guess, never a prompt). Its consumers are
 * the lease daemon (IKE-Network/ike-issues#1006), the operator at a
 * terminal, and a Claude session that meets a bare tree:
 *
 * <pre>{@code java -cp ike-lease-plugin.jar \
 *     network.ike.lease.core.MaterializeCli materialize <working-set>}</pre>
 *
 * <p>Exit codes: {@code 0} success, {@code 1} at least one repository was
 * refused, {@code 2} usage error.
 */
public final class MaterializeCli {

    private MaterializeCli() { }

    /**
     * Entry point.
     *
     * @param args {@code materialize}, {@code verify}, or {@code repair},
     *             each followed by {@code <working-set>}, optionally with
     *             {@code --ike-dev <path>} to override the development
     *             folder (default: the {@code IKE_DEV} environment
     *             variable, else {@code ~/ike-dev})
     */
    public static void main(String[] args) {
        System.exit(run(args, System.getenv("IKE_DEV")));
    }

    /**
     * Runs one command and reports through standard output.
     *
     * @param args      the command-line arguments
     * @param ikeDevEnv the {@code IKE_DEV} environment value, or
     *                  {@code null} when unset
     * @return the process exit code
     */
    static int run(String[] args, String ikeDevEnv) {
        List<String> positional = new ArrayList<>();
        Path ikeDev = null;
        for (int i = 0; i < args.length; i++) {
            if ("--ike-dev".equals(args[i])) {
                if (i + 1 >= args.length) {
                    return usage("--ike-dev needs a path");
                }
                ikeDev = Path.of(args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        if (positional.size() != 2) {
            return usage("expected: materialize|verify|repair <working-set>");
        }
        if (ikeDev == null) {
            ikeDev = ikeDevEnv != null && !ikeDevEnv.isBlank()
                    ? Path.of(ikeDevEnv)
                    : Path.of(System.getProperty("user.home"), "ike-dev");
        }

        String command = positional.get(0);
        WorkingSetName name;
        try {
            name = new WorkingSetName(positional.get(1));
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }

        Materializer materializer = new Materializer(ikeDev,
                new ProcessGitRunner(), OriginManifest.load(ikeDev),
                line -> System.out.println("  " + line));
        RefAligner aligner = new RefAligner(ikeDev, new ProcessGitRunner(),
                line -> System.out.println("  " + line));
        SiblingAligner siblingAligner = new SiblingAligner(ikeDev,
                new ProcessGitRunner(), line -> System.out.println("  " + line));
        List<RepoStamp> stamps = name.isSibling() ? List.of()
                : RefAligner.recordedStamps(ikeDev, name.value());
        boolean bundled = SiblingAligner.hasBundles(ikeDev, name);

        // Ref alignment reports in the same three verbs: `verify` checks
        // refs offline, `repair` aligns them, and `materialize` aligns
        // freshly created repositories the same way — always by moving
        // refs and `reset --mixed`, tree untouched. A root aligns to the
        // holder's stamps (ike-issues#1069); a sibling to the history its
        // last holder left in bundles (ike-issues#1216), and its repair
        // also re-points legacy origins.
        MaterializeReport report;
        RefAligner.AlignReport alignment = null;
        switch (command) {
            case "materialize" -> {
                report = materializer.materialize(name);
                if (name.isSibling() && bundled) {
                    alignment = siblingAligner.align(name);
                } else if (!name.isSibling() && !stamps.isEmpty()) {
                    alignment = aligner.align(name, stamps);
                }
            }
            case "verify" -> {
                report = materializer.verify(name);
                if (name.isSibling() && bundled) {
                    alignment = siblingAligner.check(name);
                } else if (!name.isSibling() && !stamps.isEmpty()) {
                    alignment = aligner.check(name, stamps);
                }
            }
            case "repair" -> {
                if (name.isSibling()) {
                    report = materializer.repair(name);
                    if (bundled) {
                        alignment = siblingAligner.align(name);
                    }
                } else {
                    report = null;
                    alignment = aligner.align(name, stamps);
                }
            }
            default -> {
                return usage("unknown command: " + command);
            }
        }
        boolean ok = true;
        if (report != null) {
            for (MaterializeReport.Entry entry : report.entries()) {
                System.out.println(entry.render());
            }
            ok = report.ok();
        }
        if (alignment != null) {
            for (RefAligner.Entry entry : alignment.entries()) {
                System.out.println(entry.render());
            }
            ok = ok && alignment.ok();
        }
        return ok ? 0 : 1;
    }

    private static int usage(String problem) {
        System.out.println("materialize: " + problem);
        System.out.println("usage: materialize|verify|repair <working-set> "
                + "[--ike-dev <path>]");
        return 2;
    }
}
