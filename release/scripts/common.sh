#!/usr/bin/env bash
# Shared helpers for the MSISDN eligibility API operations scripts.
# This file is sourced. It is not executed directly.

set -o nounset
set -o pipefail

_msisdn_script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
_msisdn_root="$(cd "${_msisdn_script_dir}/.." && pwd)"

if [[ -f "${_msisdn_root}/msisdn-api.jar" ]]; then
    DEFAULT_INSTALL_DIR="${_msisdn_root}"
else
    DEFAULT_INSTALL_DIR="/opt/msisdn-api"
fi

INSTALL_DIR="${INSTALL_DIR:-${DEFAULT_INSTALL_DIR}}"
ENV_FILE="${ENV_FILE:-${INSTALL_DIR}/msisdn-api.env}"

if [[ -f "${ENV_FILE}" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "${ENV_FILE}"
    set +a
fi

INSTALL_DIR="${INSTALL_DIR:-${DEFAULT_INSTALL_DIR}}"
JAVA_HOME="${JAVA_HOME:-/opt/java/jdk-17}"
JAR_PATH="${JAR_PATH:-${INSTALL_DIR}/msisdn-api.jar}"
CONFIG_PATH="${CONFIG_PATH:-${INSTALL_DIR}/application.yml}"
LOG_CONFIG="${LOG_CONFIG:-${INSTALL_DIR}/config/log4j2-spring.xml}"
PID_FILE="${PID_FILE:-${INSTALL_DIR}/run/msisdn-api.pid}"
LOCK_FILE="${LOCK_FILE:-${INSTALL_DIR}/run/msisdn-api.lock}"
WATCHDOG_LOCK="${WATCHDOG_LOCK:-${INSTALL_DIR}/run/watchdog.lock}"
WATCHDOG_STATE="${WATCHDOG_STATE:-${INSTALL_DIR}/run/watchdog.state}"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-180}"
STOP_TIMEOUT="${STOP_TIMEOUT:-60}"
HTTP_PORT="${HTTP_PORT:-8080}"
LIVENESS_URL="${LIVENESS_URL:-http://127.0.0.1:${HTTP_PORT}/actuator/health/liveness}"
READINESS_URL="${READINESS_URL:-http://127.0.0.1:${HTTP_PORT}/actuator/health/readiness}"
STATUS_URL="${STATUS_URL:-http://127.0.0.1:${HTTP_PORT}/api/v1/system/status}"
SERVICE_NAME="${SERVICE_NAME:-msisdn-api}"
RUN_USER="${RUN_USER:-$(id -un)}"
USE_SYSTEMD="${USE_SYSTEMD:-auto}"
export MSISDN_LOG_DIR="${MSISDN_LOG_DIR:-${INSTALL_DIR}/logs}"
WATCHDOG_LIVENESS_FAILURES="${WATCHDOG_LIVENESS_FAILURES:-3}"
WATCHDOG_GRACE_SECONDS="${WATCHDOG_GRACE_SECONDS:-180}"
WATCHDOG_COOLDOWN_SECONDS="${WATCHDOG_COOLDOWN_SECONDS:-300}"
WATCHDOG_MAX_RESTARTS_PER_HOUR="${WATCHDOG_MAX_RESTARTS_PER_HOUR:-4}"
JAVA_OPTS="${JAVA_OPTS:--Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=50 -Djava.net.preferIPv4Stack=true -Dmsisdn.log.dir=${INSTALL_DIR}/logs}"

log() {
    printf '%s %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"
}

die() {
    log "ERROR: $*"
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

java_is_17() {
    local bin="$1"
    [[ -x "${bin}" ]] || return 1
    local version
    version="$("${bin}" -version 2>&1 || true)"
    [[ "${version}" == *'"17.'* || "${version}" == *' version "17'* || "${version}" == *'openjdk version "17'* ]]
}

resolve_java() {
    local candidate
    for candidate in \
        "${JAVA_BIN:-}" \
        "${JAVA_HOME}/bin/java" \
        "/opt/java/jdk-17/bin/java" \
        "/usr/lib/jvm/java-17-openjdk/bin/java" \
        "/usr/lib/jvm/java-17-openjdk-amd64/bin/java" \
        "/usr/lib/jvm/jre-17/bin/java"
    do
        if [[ -n "${candidate}" ]] && java_is_17 "${candidate}"; then
            printf '%s\n' "${candidate}"
            return 0
        fi
    done
    if command -v java >/dev/null 2>&1 && java_is_17 "$(command -v java)"; then
        command -v java
        return 0
    fi
    return 1
}

pid_is_ours() {
    local pid="$1"
    [[ -n "${pid}" ]] || return 1
    [[ -d "/proc/${pid}" ]] || return 1
    local cmdline
    cmdline="$(tr '\0' ' ' < "/proc/${pid}/cmdline" 2>/dev/null || true)"
    [[ "${cmdline}" == *"${JAR_PATH}"* || "${cmdline}" == *"msisdn-api.jar"* ]]
}

read_pid() {
    if [[ -f "${PID_FILE}" ]]; then
        tr -d '[:space:]' < "${PID_FILE}"
    fi
}

running_pid() {
    local pid
    pid="$(read_pid || true)"
    if pid_is_ours "${pid}"; then
        printf '%s\n' "${pid}"
        return 0
    fi
    return 1
}

systemd_unit_enabled() {
    command -v systemctl >/dev/null 2>&1 || return 1
    systemctl cat "${SERVICE_NAME}.service" >/dev/null 2>&1 || return 1
    systemctl is-enabled "${SERVICE_NAME}.service" >/dev/null 2>&1
}

use_systemd() {
    # A system unit can be started only by an administrator. FMSUser runs the
    # shell scripts and the user crontab instead, even when USE_SYSTEMD=auto.
    if [[ "$(id -u)" -ne 0 ]]; then
        return 1
    fi
    case "${USE_SYSTEMD}" in
        true|TRUE|yes|YES|1) systemd_unit_enabled ;;
        false|FALSE|no|NO|0) return 1 ;;
        auto) systemd_unit_enabled ;;
        *) return 1 ;;
    esac
}

ensure_runtime_dirs() {
    mkdir -p \
        "${INSTALL_DIR}/run" \
        "${INSTALL_DIR}/logs/application" \
        "${INSTALL_DIR}/logs/audit/active" \
        "${INSTALL_DIR}/logs/audit/completed" \
        "${INSTALL_DIR}/logs/audit/archive" \
        "${INSTALL_DIR}/logs/audit/quarantine" \
        "${INSTALL_DIR}/data/blacklist/incoming" \
        "${INSTALL_DIR}/data/blacklist/processed" \
        "${INSTALL_DIR}/data/blacklist/rejected" \
        "${INSTALL_DIR}/data/blacklist/snapshot" \
        "${INSTALL_DIR}/data/checkpoints/oracle" \
        "${INSTALL_DIR}/data/checkpoints/csv" \
        "${INSTALL_DIR}/data/export" \
        "${INSTALL_DIR}/config"
}

curl_ok() {
    local url="$1"
    curl --silent --show-error --fail \
        --connect-timeout 2 \
        --max-time 5 \
        "${url}" >/dev/null 2>&1
}
