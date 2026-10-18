#!/usr/bin/env bash
# Exit 0 when the process is alive and the liveness endpoint is UP.
# Readiness is reported but does not fail this check: an Oracle outage or a
# blacklist reload must not be treated as a dead process.
#
# Exit codes:
#   0 process is alive and liveness is UP
#   3 process is not running
#   4 process is running but liveness is DOWN

set -o errexit
set -o nounset
set -o pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_command curl

if use_systemd; then
    if ! systemctl is-active --quiet "${SERVICE_NAME}.service"; then
        log "process is not active under systemd"
        exit 3
    fi
else
    if ! pid="$(running_pid || true)" || [[ -z "${pid}" ]]; then
        log "process is not running"
        exit 3
    fi
fi

if ! curl_ok "${LIVENESS_URL}"; then
    log "liveness endpoint failed: ${LIVENESS_URL}"
    exit 4
fi

if curl_ok "${READINESS_URL}"; then
    log "liveness UP, readiness UP"
else
    log "liveness UP, readiness DOWN"
fi
exit 0
