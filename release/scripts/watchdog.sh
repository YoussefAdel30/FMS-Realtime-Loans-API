#!/usr/bin/env bash
# Cron watchdog. It restarts the API only when the process is gone or liveness
# has failed for the configured number of consecutive checks.
# Readiness failures do not restart the process.
#
# Exit codes:
#   0 healthy, restarted, or restart suppressed by grace
#   2 restart suppressed by cooldown or the hourly cap
#   3 liveness is failing but the threshold has not been reached
#   1 unexpected error

set -o errexit
set -o nounset
set -o pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_command flock
require_command curl
mkdir -p "$(dirname "${WATCHDOG_LOCK}")" "${INSTALL_DIR}/logs/application"
exec 8>"${WATCHDOG_LOCK}"
if ! flock -n 8; then
    log "watchdog already running"
    exit 0
fi

failures=0
last_restart=0
restarts_in_window=0
window_start=0
if [[ -f "${WATCHDOG_STATE}" ]]; then
    # shellcheck disable=SC1090
    source "${WATCHDOG_STATE}"
fi
failures="${consecutive_liveness_failures:-0}"
last_restart="${last_restart_epoch:-0}"
restarts_in_window="${restarts_in_window:-0}"
window_start="${window_start_epoch:-0}"

now="$(date +%s)"
if (( window_start == 0 || now - window_start >= 3600 )); then
    window_start="${now}"
    restarts_in_window=0
fi

write_state() {
    cat > "${WATCHDOG_STATE}.tmp" <<EOF
consecutive_liveness_failures=${failures}
last_restart_epoch=${last_restart}
restarts_in_window=${restarts_in_window}
window_start_epoch=${window_start}
EOF
    mv "${WATCHDOG_STATE}.tmp" "${WATCHDOG_STATE}"
}

process_up=0
if use_systemd; then
    if systemctl is-active --quiet "${SERVICE_NAME}.service"; then
        process_up=1
    fi
else
    if pid="$(running_pid || true)" && [[ -n "${pid}" ]]; then
        process_up=1
    fi
fi

restart() {
    if (( now - last_restart < WATCHDOG_GRACE_SECONDS && last_restart > 0 )); then
        log "restart suppressed; still inside the ${WATCHDOG_GRACE_SECONDS}s grace period"
        write_state
        exit 0
    fi
    if (( now - last_restart < WATCHDOG_COOLDOWN_SECONDS && last_restart > 0 )); then
        log "restart suppressed by ${WATCHDOG_COOLDOWN_SECONDS}s cooldown"
        write_state
        exit 2
    fi
    if (( restarts_in_window >= WATCHDOG_MAX_RESTARTS_PER_HOUR )); then
        log "restart suppressed; ${restarts_in_window} restarts already recorded in the current hour"
        write_state
        exit 2
    fi
    log "restarting ${SERVICE_NAME}"
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    if use_systemd; then
        systemctl restart "${SERVICE_NAME}.service"
    else
        "${SCRIPT_DIR}/start.sh" || true
    fi
    last_restart="$(date +%s)"
    restarts_in_window=$((restarts_in_window + 1))
    failures=0
    write_state
    log "restart attempt recorded"
}

if (( process_up == 0 )); then
    log "process is not running"
    restart
    exit 0
fi

if curl_ok "${LIVENESS_URL}"; then
    if (( failures != 0 )); then
        log "liveness recovered"
    fi
    failures=0
    write_state
    exit 0
fi

failures=$((failures + 1))
log "liveness failed (${failures}/${WATCHDOG_LIVENESS_FAILURES}); readiness is not used for this decision"
write_state
if (( failures >= WATCHDOG_LIVENESS_FAILURES )); then
    restart
    exit 0
fi
exit 3
