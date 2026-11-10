#!/usr/bin/env bash
# Stop the MSISDN eligibility API with SIGTERM. This script does not send SIGKILL.

set -o errexit
set -o nounset
set -o pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

if use_systemd; then
    log "systemd unit ${SERVICE_NAME} is enabled; stopping through systemctl"
    systemctl stop "${SERVICE_NAME}.service"
    exit $?
fi

pid="$(running_pid || true)"
if [[ -z "${pid}" ]]; then
    log "not running"
    rm -f "${PID_FILE}"
    exit 0
fi

if ! pid_is_ours "${pid}"; then
    die "pid ${pid} is not the MSISDN API; refusing to signal it"
fi

log "sending SIGTERM to pid ${pid}"
kill -TERM "${pid}"

deadline=$((SECONDS + STOP_TIMEOUT))
while (( SECONDS < deadline )); do
    if ! pid_is_ours "${pid}"; then
        rm -f "${PID_FILE}"
        log "stopped"
        exit 0
    fi
    sleep 1
done

log "pid ${pid} is still running after ${STOP_TIMEOUT}s. SIGKILL was not sent."
log "Inspect the process and, only with an approved change, run: kill -KILL ${pid}"
exit 1
