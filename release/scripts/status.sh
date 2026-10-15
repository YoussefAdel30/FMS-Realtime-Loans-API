#!/usr/bin/env bash
# Report process state, HTTP liveness, readiness, and the active blacklist version.

set -o errexit
set -o nounset
set -o pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_command curl

exit_code=0

if use_systemd; then
    echo "supervisor: systemd"
    systemctl status "${SERVICE_NAME}.service" --no-pager || exit_code=$?
else
    echo "supervisor: shell pid file"
    if pid="$(running_pid || true)" && [[ -n "${pid}" ]]; then
        echo "process: running"
        echo "pid: ${pid}"
    else
        echo "process: stopped"
        echo "pid: none"
        exit_code=3
    fi
fi

if body="$(curl --silent --show-error --connect-timeout 2 --max-time 5 "${LIVENESS_URL}" 2>/dev/null)"; then
    echo "liveness: UP"
else
    echo "liveness: DOWN"
    exit_code=4
fi

if body="$(curl --silent --show-error --connect-timeout 2 --max-time 5 "${READINESS_URL}" 2>/dev/null)"; then
    echo "readiness: UP"
else
    echo "readiness: DOWN"
    if [[ "${exit_code}" -eq 0 ]]; then
        exit_code=2
    fi
fi

auth=()
if [[ -n "${MSISDN_API_KEY:-}" ]]; then
    auth=(-H "X-API-Key: ${MSISDN_API_KEY}")
fi
if status_body="$(curl --silent --show-error --connect-timeout 2 --max-time 5 "${auth[@]}" "${STATUS_URL}" 2>/dev/null)"; then
    version="$(printf '%s' "${status_body}" | sed -n 's/.*"version":"\([^"]*\)".*/\1/p' | head -n 1)"
    if [[ -n "${version}" ]]; then
        echo "blacklist-version: ${version}"
    else
        echo "blacklist-version: unavailable"
    fi
else
    echo "blacklist-version: unavailable"
fi

exit "${exit_code}"
