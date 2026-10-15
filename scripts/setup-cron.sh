#!/usr/bin/env bash
# Install the one-minute watchdog crontab for the service user.
# Cron does not restart the API the moment it dies. The next minute is the
# earliest a stopped process can be noticed.

set -o errexit
set -o nounset
set -o pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_command crontab

TARGET_USER="${1:-${RUN_USER}}"
WATCHDOG_PATH="${INSTALL_DIR}/scripts/watchdog.sh"
LOG_PATH="${INSTALL_DIR}/logs/application/watchdog.log"
LINE="* * * * * ${WATCHDOG_PATH} >> ${LOG_PATH} 2>&1"

if [[ "$(id -un)" != "root" && "$(id -un)" != "${TARGET_USER}" ]]; then
    die "run as root or as ${TARGET_USER}"
fi

mkdir -p "$(dirname "${LOG_PATH}")"
existing=""
if [[ "$(id -un)" == "root" && "${TARGET_USER}" != "root" ]]; then
    existing="$(crontab -u "${TARGET_USER}" -l 2>/dev/null || true)"
else
    existing="$(crontab -l 2>/dev/null || true)"
fi

if printf '%s\n' "${existing}" | grep -F "${WATCHDOG_PATH}" >/dev/null 2>&1; then
    log "watchdog crontab entry already present for ${TARGET_USER}"
    exit 0
fi

{
    printf '%s\n' "${existing}" | sed '/^$/d'
    printf '%s\n' "${LINE}"
} | if [[ "$(id -un)" == "root" && "${TARGET_USER}" != "root" ]]; then
    crontab -u "${TARGET_USER}" -
else
    crontab -
fi

log "installed watchdog cron for ${TARGET_USER}: ${LINE}"
log "cron runs once per minute. With USE_SYSTEMD=false this crontab is what brings the process back."
