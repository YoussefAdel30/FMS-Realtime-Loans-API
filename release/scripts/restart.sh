#!/usr/bin/env bash
# Restart the MSISDN eligibility API. The stop and start scripts share a lock
# through systemd or through the PID file. Start refuses a second instance.

set -o errexit
set -o nounset
set -o pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if use_systemd; then
    log "restarting through systemctl"
    systemctl restart "${SERVICE_NAME}.service"
    exit $?
fi

"${SCRIPT_DIR}/stop.sh"
"${SCRIPT_DIR}/start.sh"
