---
date_published: 1980-01-31
date_modified: 1980-01-31
canonical_url: https://ike.network/ike-base-parent/ike-lease/ike-lease-core/index.html
---

# About IKE Working-Set Leases — Core

The lease protocol (the exact port of lease.sh v2, golden-tested against a frozen reference) and the git-state materializer, as a plain-Java JPMS library with no IDE dependency — one core, thin hosts (IKE-Network/ike-issues#1057, #1067). The IntelliJ plugin embeds it; lease.sh execs its CLIs; the ws: goals may depend on it directly, which is what retires the goal-to-$HOME-script coupling (IKE-Network/ike-issues#1005).
