#!/bin/bash
# lease.sh — working-set leases: single writer per working set across machines.
#
# The lease record lives in ~/ike-dev/leases/ and syncs like any other
# file, so writing a lease IS announcing it: every machine's enforcement
# components see the change within seconds. Correctness never depends on
# reaching a peer.
#
# Lifecycle (Jini-derived): grants are time-bounded, the holder renews,
# release is explicit, and expiry reclaims without the holder's
# cooperation. Departures from Jini: there is no landlord (the synced
# file is the grant), a monotonic `epoch` is the fencing token, and
# forced takeover is safe because one human drives this fleet.
#
# TTL is a STALENESS HORIZON, never a wait: takeover is immediate. It
# selects only whether acquisition needs confirmation (live lease) or
# happens silently (expired/released).
#
#   lease.sh status  <working-set>        FREE|MINE|EXPIRED|LIVE, exit 0/0/0/1
#   lease.sh ensure  <working-set> [--confirm]
#                                         hold it if no human call needed
#   lease.sh acquire <working-set> [--force] [--quiet] [--confirm]
#   lease.sh renew   <working-set>
#   lease.sh release <working-set>
#   lease.sh list                         every lease with computed state
#   lease.sh resolve <path>               path -> working-set name
#
# Design: dev-working-set-lease (ike-lab-documents), IKE-Network/ike-issues#1002.
set -uo pipefail

IKE_DEV="${IKE_DEV:-$HOME/ike-dev}"
LEASE_DIR="$IKE_DEV/leases"
ID_FILE="$HOME/.ike-machine-id"
DEFAULT_TTL="${IKE_LEASE_TTL:-PT10M}"

# ── identity ────────────────────────────────────────────────────────

machine_id() {
    if [ -f "$ID_FILE" ]; then
        tr -d '[:space:]' < "$ID_FILE"
    else
        echo "ERROR: no $ID_FILE — run scripts/install-machine-id.sh" >&2
        return 1
    fi
}

# ── time helpers (macOS and GNU date) ───────────────────────────────

now_iso()   { date -u +%Y-%m-%dT%H:%M:%SZ; }
now_epoch() { date -u +%s; }

# ISO-8601 UTC -> epoch seconds. Empty on unparseable input.
iso_to_epoch() {
    local ts="$1"
    [ -n "$ts" ] || return 1
    date -j -u -f "%Y-%m-%dT%H:%M:%SZ" "$ts" +%s 2>/dev/null \
        || date -u -d "$ts" +%s 2>/dev/null
}

# ISO-8601 duration (PT#H / PT#M / PT#S) -> seconds. Defaults to 600.
ttl_to_seconds() {
    local ttl="${1:-}" n
    case "$ttl" in
        PT*H) n="${ttl#PT}"; n="${n%H}"; echo $(( n * 3600 )) ;;
        PT*M) n="${ttl#PT}"; n="${n%M}"; echo $(( n * 60 )) ;;
        PT*S) n="${ttl#PT}"; n="${n%S}"; echo "$n" ;;
        *)    echo 600 ;;
    esac
}

# ── record I/O ──────────────────────────────────────────────────────
# Flat scalars only: valid YAML that shell can read without a parser.

lease_file() { echo "$LEASE_DIR/$1.lease"; }

field() {                       # field <file> <key>
    [ -f "$1" ] || return 1
    awk -v k="$2" '$1 == k":" { $1=""; sub(/^ /,""); print; exit }' "$1"
}

# Write is atomic: a half-written lease read by a peer mid-sync would be
# worse than a stale one.
write_lease() {                 # write_lease <ws> <state> <holder> <epoch> <acquired> <renewed>
    local ws="$1" state="$2" holder="$3" epoch="$4" acquired="$5" renewed="$6"
    local target tmp
    target="$(lease_file "$ws")"
    mkdir -p "$LEASE_DIR"
    tmp="$(mktemp "$LEASE_DIR/.lease.XXXXXX")" || return 1
    {
        echo "# Working-set lease — written by scripts/lease.sh."
        echo "# Holder has sole write access; see IKE-Network/ike-issues#1002."
        echo "working-set: $ws"
        echo "state: $state"
        echo "holder: $holder"
        echo "epoch: $epoch"
        echo "acquired: $acquired"
        echo "renewed: $renewed"
        echo "ttl: $DEFAULT_TTL"
    } > "$tmp" && mv -f "$tmp" "$target" && nudge_sync
}

# Tell Syncthing to scan leases/ right now instead of waiting to notice.
#
# Measured 2026-08-12: a lease written on one machine did not reach the
# other until a scan was forced — the filesystem watcher had not picked
# up the directory, leaving the hourly rescan (rescanIntervalS=3600) as
# the fallback. A bus with an hour of latency is not a bus. Forcing a
# targeted scan of one small directory is cheap and makes delivery
# prompt and deterministic rather than hopeful.
#
# Best-effort and non-fatal: if Syncthing is absent or the API moves, the
# lease is still written and still eventually syncs.
nudge_sync() {
    local cfg key folder
    cfg="$HOME/Library/Application Support/Syncthing/config.xml"
    [ -f "$cfg" ] || cfg="$HOME/.config/syncthing/config.xml"
    [ -f "$cfg" ] || return 0
    command -v curl >/dev/null 2>&1 || return 0
    key="$(grep -oE '<apikey>[^<]+' "$cfg" 2>/dev/null | head -1 | sed 's/<apikey>//')"
    [ -n "$key" ] || return 0
    folder="$(grep -oE '<folder id="[^"]+" label="[^"]*" path="[^"]*ike-dev"' "$cfg" 2>/dev/null \
        | head -1 | sed -E 's/.*id="([^"]+)".*/\1/')"
    [ -n "$folder" ] || return 0
    # Synchronous on purpose. Backgrounding this loses the request when
    # the script exits immediately after — which is exactly what happens
    # when a peer runs `ssh host lease.sh acquire`, and it silently cost
    # us the prompt delivery this function exists to guarantee. A local
    # API call is milliseconds; the timeout only bounds the pathological
    # case.
    curl -s -m 2 -X POST -H "X-API-Key: $key" \
        "http://127.0.0.1:8384/rest/db/scan?folder=${folder}&sub=leases" \
        >/dev/null 2>&1
    return 0
}

# ── state ───────────────────────────────────────────────────────────
# FREE     no record, or explicitly released
# MINE     held by this machine
# EXPIRED  held, but not renewed within its ttl — reclaim silently
# LIVE     held and fresh — takeover requires a human decision

# Concurrent acquisitions do not merge: Syncthing keeps each machine's
# own file and sidelines the other as `<ws>.sync-conflict-<stamp>-<dev>.lease`.
# Measured 2026-08-12 — the two machines then hold DIFFERENT epochs
# forever, each convinced it is the holder. Re-reading your own record
# cannot detect this, because your own record is the one that survived.
#
# So every read reconciles first. The winner is the highest epoch, ties
# broken by the lexicographically greatest machine id — a rule both
# machines evaluate independently on the same inputs and therefore agree
# on without talking to each other. The losing copy is folded in and
# removed, which is what makes the split heal instead of persisting.
reconcile_conflicts() {         # reconcile_conflicts <ws>
    local ws="$1" f c best_file best_epoch best_holder epoch holder found=false
    f="$(lease_file "$ws")"
    shopt -s nullglob
    local conflicts=("$LEASE_DIR/$ws".sync-conflict-*.lease)
    [ ${#conflicts[@]} -gt 0 ] || return 0

    best_file="$f"
    best_epoch="$(field "$f" epoch 2>/dev/null)"; [ -n "${best_epoch:-}" ] || best_epoch=0
    best_holder="$(field "$f" holder 2>/dev/null)"

    for c in "${conflicts[@]}"; do
        found=true
        epoch="$(field "$c" epoch 2>/dev/null)"; [ -n "${epoch:-}" ] || epoch=0
        holder="$(field "$c" holder 2>/dev/null)"
        if [ "$epoch" -gt "$best_epoch" ] \
           || { [ "$epoch" -eq "$best_epoch" ] && [[ "$holder" > "$best_holder" ]]; }; then
            best_file="$c"; best_epoch="$epoch"; best_holder="$holder"
        fi
    done
    $found || return 0

    if [ "$best_file" != "$f" ]; then
        cp -f "$best_file" "$f"
        echo "reconciled $ws: contested acquisition resolved to $best_holder (epoch $best_epoch)" >&2
    fi
    rm -f "${conflicts[@]}"
    return 0
}

lease_state() {                 # lease_state <ws>  -> state on stdout
    local ws="$1" f me state holder renewed ttl age limit
    reconcile_conflicts "$ws"
    f="$(lease_file "$ws")"
    [ -f "$f" ] || { echo FREE; return; }
    state="$(field "$f" state)"
    [ "$state" = "released" ] && { echo FREE; return; }
    holder="$(field "$f" holder)"
    me="$(machine_id)" || return 1
    [ "$holder" = "$me" ] && { echo MINE; return; }
    renewed="$(iso_to_epoch "$(field "$f" renewed)")" || { echo LIVE; return; }
    ttl="$(ttl_to_seconds "$(field "$f" ttl)")"
    age=$(( $(now_epoch) - renewed ))
    limit="$ttl"
    if [ "$age" -gt "$limit" ]; then echo EXPIRED; else echo LIVE; fi
}

describe() {                    # describe <ws> — one human line
    local ws="$1" f st holder epoch renewed age
    f="$(lease_file "$ws")"
    st="$(lease_state "$ws")"
    if [ ! -f "$f" ]; then
        echo "$ws: FREE (no lease record)"
        return
    fi
    holder="$(field "$f" holder)"
    epoch="$(field "$f" epoch)"
    renewed="$(field "$f" renewed)"
    age=$(( ( $(now_epoch) - $(iso_to_epoch "$renewed") ) / 60 ))
    case "$st" in
        FREE)    echo "$ws: FREE (released by $holder, epoch $epoch)" ;;
        MINE)    echo "$ws: MINE ($holder, epoch $epoch, renewed ${age}m ago)" ;;
        EXPIRED) echo "$ws: EXPIRED (was $holder, epoch $epoch, renewed ${age}m ago)" ;;
        LIVE)    echo "$ws: HELD by $holder (epoch $epoch, renewed ${age}m ago)" ;;
    esac
}

# ── operations ──────────────────────────────────────────────────────

# Acquisition is optimistic: it writes and returns. That is safe for the
# common case (one human, one machine at a time) but NOT at the moment of
# genuine contention — see confirm_hold below.
cmd_acquire() {                 # cmd_acquire <ws> [--force] [--quiet] [--confirm]
    local ws="$1" force=false quiet=false confirm=false me f st epoch now
    shift
    while [ $# -gt 0 ]; do
        case "$1" in
            --force)   force=true ;;
            --quiet)   quiet=true ;;
            --confirm) confirm=true ;;
        esac
        shift
    done
    me="$(machine_id)" || return 2
    f="$(lease_file "$ws")"
    st="$(lease_state "$ws")"
    now="$(now_iso)"
    epoch="$(field "$f" epoch 2>/dev/null)"
    [ -n "${epoch:-}" ] || epoch=0

    case "$st" in
        MINE)
            write_lease "$ws" held "$me" "$epoch" "$(field "$f" acquired)" "$now"
            $quiet || echo "already held here (epoch $epoch) — renewed"
            # Confirm on renewal too: a holder that has been fenced by a
            # takeover elsewhere finds out here, and nowhere else.
            $confirm && { confirm_hold "$ws" "$quiet" || return 1; }
            return 0
            ;;
        FREE|EXPIRED)
            # Auto-acquire: free and expired records are takeable in
            # silence. The epoch still advances, so anything holding the
            # previous epoch is fenced.
            epoch=$(( epoch + 1 ))
            write_lease "$ws" held "$me" "$epoch" "$now" "$now"
            $quiet || echo "acquired $ws (epoch $epoch, holder $me)"
            $confirm && { confirm_hold "$ws" "$quiet" || return 1; }
            return 0
            ;;
        LIVE)
            local prev
            prev="$(field "$f" holder)"      # capture before overwriting
            if ! $force; then
                echo "HELD by $prev — renewed $(field "$f" renewed)." >&2
                echo "Takeover of a live lease is a human decision. To take it:" >&2
                echo "  $(basename "$0") acquire '$ws' --force" >&2
                return 1
            fi
            epoch=$(( epoch + 1 ))
            write_lease "$ws" held "$me" "$epoch" "$now" "$now"
            $quiet || echo "TOOK OVER $ws from $prev (epoch $epoch) — $prev is fenced"
            $confirm && { confirm_hold "$ws" "$quiet" || return 1; }
            return 0
            ;;
    esac
}

cmd_release() {
    local ws="$1" me f st
    me="$(machine_id)" || return 2
    f="$(lease_file "$ws")"
    [ -f "$f" ] || { echo "no lease record for $ws"; return 0; }
    st="$(lease_state "$ws")"
    if [ "$st" != "MINE" ]; then
        echo "not held by this machine ($(describe "$ws")) — nothing released" >&2
        return 1
    fi
    write_lease "$ws" released "$me" "$(field "$f" epoch)" \
        "$(field "$f" acquired)" "$(now_iso)"
    echo "released $ws"
}

cmd_renew() {
    local ws="$1" f
    f="$(lease_file "$ws")"
    if [ "$(lease_state "$ws")" != "MINE" ]; then
        echo "not held by this machine — not renewed" >&2
        return 1
    fi
    write_lease "$ws" held "$(machine_id)" "$(field "$f" epoch)" \
        "$(field "$f" acquired)" "$(now_iso)"
}

# Re-read the lease after the sync layer has had time to deliver a
# competing write, and confirm this machine is still the holder.
#
# Measured 2026-08-12, not assumed: two machines acquiring the same free
# lease inside the propagation window BOTH succeed, and Syncthing settles
# it by last-writer-wins with NO `.sync-conflict-*` file. The loser is
# never told. Propagation here is ~10-25s (Syncthing's fsWatcherDelayS is
# 10s before it even scans), so the window is wide enough to matter, and
# an epoch alone cannot help — both machines mint the SAME epoch from the
# same starting record.
#
# The read-back is therefore what makes an acquisition trustworthy.
# Callers that are about to do something consequential (open an IDE on
# it, start a finish) should pay the wait; the fast optimistic path stays
# the default for routine edits, where one human at one keyboard means
# contention is rare.
confirm_hold() {                # confirm_hold <ws> <quiet>
    local ws="$1" quiet="${2:-false}" settle state
    settle="${IKE_LEASE_SETTLE_SECONDS:-25}"
    $quiet || echo "confirming (waiting ${settle}s for the sync layer)…"
    sleep "$settle"
    state="$(lease_state "$ws")"
    if [ "$state" = "MINE" ]; then
        $quiet || echo "confirmed: $ws is held here"
        return 0
    fi
    echo "LOST THE RACE for $ws — another machine's claim won." >&2
    echo "  $(describe "$ws")" >&2
    echo "  Do not write to this working set; re-acquire deliberately if you meant to take it." >&2
    return 1
}

# Single call for enforcement points (Claude hook, IDE plugin): make this
# machine the holder if that needs no human decision, and say so.
#   exit 0  — you hold it, proceed
#   exit 1  — held live elsewhere, OR --confirm lost the race; stdout
#             explains, for the deny reason
#   exit 2  — not a working set / no identity: not our business, proceed
#
# Renews at half-life rather than on every call: the lease record syncs,
# so rewriting it per keystroke would be pure Syncthing churn.
#
# `--confirm` adds the read-back described at confirm_hold. It is for
# consequential callers — opening an IDE project on the working set,
# starting a `ws:` finish or release — and costs the settle window, so the
# per-tool-call Claude fence must never pass it.
#
# Note that MINE confirms too, even though nothing was written when the
# lease was still fresh. A machine that slept through a takeover reads its
# own stale record and believes it still holds the lease; the read-back
# after reconciliation is where it finds out otherwise. Paying the settle
# window once, at project open, is the cheapest place to learn that.
cmd_ensure() {
    local ws="$1" confirm=false f st renewed ttl age
    shift || true
    while [ $# -gt 0 ]; do
        case "$1" in
            --confirm) confirm=true ;;
        esac
        shift
    done
    f="$(lease_file "$ws")"
    st="$(lease_state "$ws")" || return 2
    case "$st" in
        MINE)
            renewed="$(iso_to_epoch "$(field "$f" renewed)")" || return 0
            ttl="$(ttl_to_seconds "$(field "$f" ttl)")"
            age=$(( $(now_epoch) - renewed ))
            [ "$age" -ge $(( ttl / 2 )) ] && cmd_renew "$ws" >/dev/null 2>&1
            $confirm && { confirm_hold "$ws" true || return 1; }
            return 0
            ;;
        FREE|EXPIRED)
            if $confirm; then
                cmd_acquire "$ws" --quiet --confirm || return 1
            else
                cmd_acquire "$ws" --quiet
            fi
            return 0
            ;;
        LIVE)
            echo "Working set '$ws' is leased to $(field "$f" holder)" \
                 "(epoch $(field "$f" epoch), renewed $(field "$f" renewed))."
            echo "Single-writer is enforced per working set, so writes from here are fenced."
            echo "Taking over a live lease is the human's call — ask before running:"
            echo "  ~/ike-dev/scripts/lease.sh acquire '$ws' --force"
            return 1
            ;;
    esac
}

cmd_list() {
    local f ws found=false
    shopt -s nullglob
    for f in "$LEASE_DIR"/*.lease; do
        found=true
        ws="$(basename "$f" .lease)"
        describe "$ws"
    done
    $found || echo "no lease records in $LEASE_DIR"
}

# A working set is the top-level directory under ~/ike-dev — the thing
# IntelliJ opens. Workspace-nested subprojects lease at workspace
# granularity, so they resolve to their workspace.
# Normalises textually, never touching the filesystem: a path being
# written for the first time does not exist yet, and it still has to
# resolve to its working set.
cmd_resolve() {
    local p="$1" rel
    [ -n "$p" ] || return 1
    # ike-dev's own infrastructure is not a working set. Without this,
    # any command touching scripts/ or leases/ mints a lease for it.
    local excluded=" leases scripts notes .stfolder .stversions "
    [ "${p:0:1}" = "/" ] || p="$PWD/$p"
    while [ "$p" != "${p//\/.\//\/}" ]; do p="${p//\/.\//\/}"; done
    p="${p%/.}"
    case "$p" in
        "$IKE_DEV"/*) rel="${p#"$IKE_DEV"/}" ;;
        *) return 1 ;;
    esac
    rel="${rel%%/*}"
    [ -n "$rel" ] || return 1
    [[ "$excluded" == *" $rel "* ]] && return 1
    echo "$rel"
}

usage() {
    local me
    me="$(basename "$0")"
    {
        echo "usage: $me <command> [args]"
        echo
        echo "  status  <ws>              describe the lease; exit 1 if held live elsewhere"
        echo "  ensure  <ws> [--confirm]  hold it if that needs no human decision"
        echo "  acquire <ws> [--force] [--quiet] [--confirm]"
        echo "  renew   <ws>              refresh the renewal stamp"
        echo "  release <ws>              give up a lease this machine holds"
        echo "  list                      every lease record and its state"
        echo "  resolve <path>            the working set a path belongs to"
        echo
        echo "  --confirm  read the record back after the sync-layer settle"
        echo "             window (${IKE_LEASE_SETTLE_SECONDS:-25}s) and fail if"
        echo "             another machine's claim won. For consequential steps"
        echo "             only — never the per-tool-call fence."
    } >&2
    exit 2
}

[ $# -ge 1 ] || usage
CMD="$1"; shift

case "$CMD" in
    status)  [ $# -ge 1 ] || usage
             describe "$1"
             [ "$(lease_state "$1")" = "LIVE" ] && exit 1 || exit 0 ;;
    acquire) [ $# -ge 1 ] || usage; cmd_acquire "$@" ;;
    ensure)  [ $# -ge 1 ] || usage; cmd_ensure "$@" ;;
    renew)   [ $# -ge 1 ] || usage; cmd_renew "$1" ;;
    release) [ $# -ge 1 ] || usage; cmd_release "$1" ;;
    list)    cmd_list ;;
    resolve) [ $# -ge 1 ] || usage; cmd_resolve "$1" ;;
    *)       usage ;;
esac
