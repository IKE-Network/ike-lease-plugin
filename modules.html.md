---
date_published: 1980-01-31
date_modified: 1980-01-31
canonical_url: https://ike.network/ike-base-parent/ike-lease/modules.html
---

# Project Modules

This project has declared the following modules:

| Name | Description |
| --- | --- |
| [IKE Working-Set Leases — Core](./ike-lease-core/index.html)[1] | The lease protocol (the exact port of lease.sh v2, golden-tested against a frozen reference) and the git-state materializer, as a plain-Java JPMS library with no IDE dependency — one core, thin hosts (IKE-Network/ike-issues#1057, #1067). The IntelliJ plugin embeds it; lease.sh execs its CLIs; the ws: goals may depend on it directly, which is what retires the goal-to-$HOME-script coupling (IKE-Network/ike-issues#1005). |
| [IKE Working-Set Leases — IntelliJ Plugin](./ike-lease-plugin/index.html)[2] | The IDE host of the lease core: opening a project acquires its lease and materializes its git state; being fenced by a takeover elsewhere saves open documents and closes the project; sibling pushes to remote remotes abort in the push dialog. Ships as a zip bundling this jar and ike-lease-core under lib/. |
