#!/usr/bin/env bash
# Remove the watchdog crontab entry.

set -o errexit
set -o nounset
set -o pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"
require_command crontab

TARGET_USER="${1:-${RUN_USER}}"
WATCHDOG_PATH="${INSTALL_DIR}/scripts/watchdog.sh"

if [[ "$(id -un)" != "root" && "$(id -un)" != "${TARGET_USER}" ]]; then
    die "run as root or as ${TARGET_USER}"
fi

if [[ "$(id -un)" == "root" && "${TARGET_USER}" != "root" ]]; then
    existing="$(crontab -u "${TARGET_USER}" -l 2>/dev/null || true)"
else
    existing="$(crontab -l 2>/dev/null || true)"
fi

filtered="$(printf '%s\n' "${existing}" | grep -F -v "${WATCHDOG_PATH}" || true)"
if [[ "$(id -un)" == "root" && "${TARGET_USER}" != "root" ]]; then
    printf '%s\n' "${filtered}" | crontab -u "${TARGET_USER}" -
else
    printf '%s\n' "${filtered}" | crontab -
fi
log "removed watchdog crontab entry for ${TARGET_USER}"
