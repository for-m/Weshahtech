#!/bin/sh
# shellcheck shell=dash disable=SC3043
# WESHAH agent — configuration backup and verified, reversible restore.
#
# Backup  : `sysupgrade -b` (OpenWrt's own mechanism) + a manifest identifying the
#           board, so a backup taken on a different model is detected.
# Restore : two explicit steps. validate stages the archive and inspects it without
#           touching the system; apply re-validates, snapshots the CURRENT config,
#           restores with `sysupgrade -r` and arms an automatic rollback that fires
#           unless the client confirms the router came back healthy after reboot.
# Nothing here ever writes firmware: only /etc and /root configuration files.

RESTORE_DIR="$RUN_DIR/restore"
STAGED="$RESTORE_DIR/staged.tar.gz"
PRE_DIR="$STATE_DIR/pre-restore"
RESTORE_PENDING="$STATE_DIR/restore.pending"
MANIFEST_NAME="etc/weshah-agent/backup-manifest.json"
BACKUP_MAX_BYTES=5242880
BACKUP_MAX_FILES=2000
BACKUP_MAX_UNPACKED=20971520
RESTORE_CONFIRM_SECONDS="${RESTORE_CONFIRM_SECONDS:-300}"

router_board() { cat /tmp/sysinfo/board_name 2>/dev/null; }

write_manifest() {
    mkdir -p "$STATE_DIR"
    printf '{"board":%s,"model":%s,"release":%s,"agent":"%s","created":%s}\n' \
        "$(jstr "$(router_board)")" "$(jstr "$(cat /tmp/sysinfo/model 2>/dev/null)")" \
        "$(jstr "$(sed -n "s/^DISTRIB_DESCRIPTION='\(.*\)'/\1/p" /etc/openwrt_release 2>/dev/null)")" \
        "$WESHAH_VERSION" "$(epoch_now)" > "$STATE_DIR/backup-manifest.json"
}

# Writes the archive path to stdout. rc != 0 on failure.
create_backup_file() {
    local out="$RUN_DIR/backup.$$.tar.gz"
    have_cmd sysupgrade || return 1
    write_manifest
    sysupgrade -b "$out" >/dev/null 2>&1 || { rm -f "$out"; return 1; }
    [ -s "$out" ] || { rm -f "$out"; return 1; }
    printf '%s' "$out"
}

# Prints a JSON verdict; returns 0 when the archive is structurally safe.
validate_backup() { # <file>
    local f="$1" size entries listing bad warnings="" unpacked files sha manifest bboard rboard match=null verified=false
    [ -f "$f" ] || { fail "$RC_BAD" "no_archive"; return; }
    size=$(wc -c < "$f")
    [ "$size" -gt 100 ] && [ "$size" -le "$BACKUP_MAX_BYTES" ] || { fail "$RC_BAD" "archive_size_out_of_range"; return; }
    gzip -t < "$f" 2>/dev/null || { fail "$RC_BAD" "not_a_valid_gzip"; return; }
    listing="$RUN_DIR/restore.listing"
    tar tzvf "$f" > "$listing" 2> "$RUN_DIR/restore.tarerr" || { fail "$RC_BAD" "not_a_valid_tar"; return; }
    # BusyBox silently rewrites absolute and ../ member names and only warns on stderr: never accept that.
    if [ -s "$RUN_DIR/restore.tarerr" ]; then
        fail "$RC_BAD" "unsafe_path:$(head -n 1 "$RUN_DIR/restore.tarerr" | tr -d '\n"' | cut -c1-80)"
        return
    fi
    files=$(wc -l < "$listing")
    [ "$files" -ge 1 ] && [ "$files" -le "$BACKUP_MAX_FILES" ] || { fail "$RC_BAD" "file_count_out_of_range"; return; }
    # entry type must be a regular file or directory; the name must be a safe relative path under etc/ or root/
    bad=$(awk '{
            t = substr($1, 1, 1); name = $NF
            if (t != "-" && t != "d") { print "unsupported_entry_type:" name; exit }
            if (name ~ /^\// || name ~ /(^|\/)\.\.(\/|$)/) { print "unsafe_path:" name; exit }
            if (name !~ /^(etc|root)(\/|$)/) { print "path_outside_etc_root:" name; exit }
        }' "$listing")
    [ -n "$bad" ] && { fail "$RC_BAD" "$bad"; return; }
    grep -q ' etc/config/system$' "$listing" || { fail "$RC_BAD" "missing_etc_config_system"; return; }
    unpacked=$(awk '$1 ~ /^-/ { s += $3 } END { print s + 0 }' "$listing")
    [ "$unpacked" -le "$BACKUP_MAX_UNPACKED" ] || { fail "$RC_BAD" "unpacked_size_too_large"; return; }
    sha=$(sha256sum "$f" | cut -d' ' -f1)
    rboard=$(router_board)
    manifest=$(tar xzOf "$f" "$MANIFEST_NAME" 2>/dev/null)
    if [ -n "$manifest" ]; then
        verified=true
        bboard=$(json_get "$manifest" '@.board')
        if [ -n "$bboard" ] && [ -n "$rboard" ]; then
            if [ "$bboard" = "$rboard" ]; then match=true; else match=false; fi
        fi
    else
        warnings="\"no_manifest_board_unverified\""
    fi
    [ "$match" = false ] && warnings="${warnings:+$warnings,}\"board_mismatch\""
    printf '{"success":true,"error":null,"valid":true,"sha256":"%s","sizeBytes":%s,"files":%s,"unpackedBytes":%s,"hasManifest":%s,"boardMatch":%s,"backupBoard":%s,"routerBoard":%s,"warnings":[%s]}' \
        "$sha" "$size" "$files" "$unpacked" "$verified" "$match" "$(jstr_n "$bboard")" "$(jstr_n "$rboard")" "$warnings"
}

stage_restore() { # <uploaded file> → validation JSON; keeps the archive only if valid
    local out rc
    mkdir -p "$RESTORE_DIR"
    rm -f "$STAGED"
    out=$(validate_backup "$1"); rc=$?
    if [ "$rc" = 0 ]; then mv "$1" "$STAGED"; else rm -f "$1"; fi
    printf '%s' "$out"
    return "$rc"
}

apply_restore() { # <json body: sha256, allowUnverified, reboot>
    local body="$1" want allow reboot out rc sha match verified snap
    have_cmd sysupgrade || { fail "$RC_UNSUPPORTED" "sysupgrade_missing"; return; }
    [ -f "$STAGED" ] || { fail "$RC_NOTFOUND" "nothing_staged"; return; }
    want=$(json_get "$body" '@.sha256'); allow=$(json_get "$body" '@.allowUnverified'); reboot=$(json_get "$body" '@.reboot')
    out=$(validate_backup "$STAGED"); rc=$?
    [ "$rc" != 0 ] && { printf '%s' "$out"; return "$rc"; }
    sha=$(json_get "$out" '@.sha256')
    [ -n "$want" ] && [ "$want" = "$sha" ] || { fail "$RC_CONFLICT" "sha256_mismatch_with_staged_archive"; return; }
    match=$(json_get "$out" '@.boardMatch'); verified=$(json_get "$out" '@.hasManifest')
    [ "$match" = false ] && { fail "$RC_CONFLICT" "board_mismatch_refused"; return; }
    [ "$verified" != true ] && [ "$allow" != true ] && { fail "$RC_CONFLICT" "unverified_backup_requires_allowUnverified"; return; }
    [ -f "$RESTORE_PENDING" ] && { fail "$RC_CONFLICT" "previous_restore_not_confirmed"; return; }
    mkdir -p "$PRE_DIR"
    snap="$PRE_DIR/pre-restore-$(epoch_now).tar.gz"
    write_manifest
    sysupgrade -b "$snap" >/dev/null 2>&1 && [ -s "$snap" ] || { rm -f "$snap"; fail "$RC_FAIL" "pre_restore_snapshot_failed"; return; }
    ls -1t "$PRE_DIR"/pre-restore-*.tar.gz 2>/dev/null | tail -n +4 | while read -r old; do rm -f "$old"; done
    echo "$snap $(boot_id) applied 0" > "$RESTORE_PENDING"
    if ! sysupgrade -r "$STAGED" >/dev/null 2>&1; then
        rm -f "$RESTORE_PENDING"
        fail "$RC_FAIL" "sysupgrade_restore_failed"
        return
    fi
    rm -f "$STAGED"
    log "config restored from staged archive; snapshot=$snap"
    if [ "$reboot" = true ]; then
        ( sleep 5; sh -c "$REBOOT_CMD" ) >/dev/null 2>&1 </dev/null &
    fi
    printf '{"success":true,"error":null,"rebootRequired":true,"rebootScheduled":%s,"snapshot":%s,"confirmWithinSecondsAfterBoot":%s}' \
        "$([ "$reboot" = true ] && echo true || echo false)" "$(jstr "$snap")" "$RESTORE_CONFIRM_SECONDS"
}

confirm_restore() {
    if [ -f "$RESTORE_PENDING" ]; then rm -f "$RESTORE_PENDING"; log "restore confirmed"; ok_json
    else printf '{"success":true,"error":null,"note":"nothing_pending"}'; fi
}

# Tick/boot hook. After a restore the router reboots; the first boot gets
# RESTORE_CONFIRM_SECONDS to be confirmed, otherwise the pre-restore snapshot is
# put back and the router reboots again.
restore_rollback_check() {
    local snap armed state deadline now
    [ -f "$RESTORE_PENDING" ] || return 0
    read -r snap armed state deadline < "$RESTORE_PENDING"
    now=$(uptime_s)
    if [ "$armed" = "$(boot_id)" ]; then return 0; fi
    if [ "$state" = applied ]; then
        echo "$snap $armed booted $((now + RESTORE_CONFIRM_SECONDS))" > "$RESTORE_PENDING"
        return 0
    fi
    [ "$now" -lt "$deadline" ] && return 0
    log "restore not confirmed after boot: rolling back to $snap"
    if [ -f "$snap" ] && sysupgrade -r "$snap" >/dev/null 2>&1; then
        rm -f "$RESTORE_PENDING"
        ( sleep 5; sh -c "$REBOOT_CMD" ) >/dev/null 2>&1 </dev/null &
    else
        log "ROLLBACK FAILED: snapshot $snap missing or not restorable"
        mv "$RESTORE_PENDING" "$RESTORE_PENDING.failed"
    fi
}
