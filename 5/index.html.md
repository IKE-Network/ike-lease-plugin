---
date_published: 1980-01-31
date_modified: 1980-01-31
canonical_url: https://ike.network/ike-base-parent/ike-lease/index.html
---

# IKE Working-Set Leases

The working-set lease machinery of IKE’s multi-machine development fabric: **one writer at a time per working set** in a Syncthing-synchronized development folder, with the synced lease record as both grant and bus. Delivered in full under [IKE-Network/ike-issues#1002](https://github.com/IKE-Network/ike-issues/issues/1002)[1] and its descendants; canonical design in the `dev-working-set-lease` topic of the lab corpus.

Two Maven modules, one reactor (`network.ike:ike-lease`):

| Module | What it is |
| --- | --- |
| `ike-lease-core` | The canonical lease protocol — verbs, epoch fencing, TTL staleness, reconcile-on-read, confirmed acquisition, ref stamps — plus git-state **materialization** for synced bare trees, root **ref alignment** to the holder’s stamps, the reconciliation daemon, and the headless CLIs. IntelliJ-free by construction. |
| `ike-lease-plugin` | The IntelliJ host: opening a project is the acquisition gesture (confirmed against the sync-propagation race), materialization and alignment run right after it, a watcher renews every open project, and a pre-push guard keeps sibling working sets off remote remotes. |

`~/ike-dev/scripts/lease.sh` is a thin wrapper that execs the core’s CLI, so the fence, the operator, and the IDE all run the same single implementation. Installing the plugin zip is what provisions the command line — the wrapper globs the installed `lib/` for the core jar.

## [#deliberately-outside-the-release-cascade](#deliberately-outside-the-release-cascade)Deliberately outside the release cascade

This repository releases manually (`ike:release-draft` / `ike:release-publish`), publishes to the internal Nexus and GitHub — never Maven Central — and is **not** a node of the foundation release cascade: the lease layer sits below the build system, and per-machine installs go through `setup-machine.sh` rather than artifact consumption. The full rationale, protocol description, and operating guide live in the repository [README](https://github.com/IKE-Network/ike-lease-plugin#readme)[2].
