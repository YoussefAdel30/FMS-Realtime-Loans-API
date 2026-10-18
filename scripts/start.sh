#!/usr/bin/env bash
# Start the MSISDN eligibility API. Exit 0 when ready, 2 when the process is up
# but not ready, 1 when startup failed.

set -o errexit
set -o nounset
set -o pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

if use_systemd; then
    log "systemd unit ${SERVICE_NAME} is enabled; starting through systemctl"
    systemctl start "${SERVICE_NAME}.service"
    exit $?
fi

require_command flock
require_command curl
mkdir -p "$(dirname "${LOCK_FILE}")"
exec 9>"${LOCK_FILE}"
if ! flock -n 9; then
    die "another start is already in progress"
fi

if pid="$(running_pid || true)" && [[ -n "${pid}" ]]; then
    log "already running with pid ${pid}"
    exit 0
fi

[[ -f "${JAR_PATH}" ]] || die "JAR not found at ${JAR_PATH}"
[[ -f "${CONFIG_PATH}" ]] || die "configuration not found at ${CONFIG_PATH}"

JAVA_BIN="$(resolve_java || true)"
[[ -n "${JAVA_BIN}" ]] || die "Java 17 was not found. Set JAVA_HOME to a JDK 17 installation."
log "using Java ${JAVA_BIN}"
"${JAVA_BIN}" -version

ensure_runtime_dirs

if [[ -f "${LOG_CONFIG}" ]]; then
    LOG_CONFIG_ARG=(-Dlogging.config="${LOG_CONFIG}")
else
    LOG_CONFIG_ARG=()
fi

log "starting ${JAR_PATH}"
# shellcheck disable=SC2086
nohup "${JAVA_BIN}" ${JAVA_OPTS} "${LOG_CONFIG_ARG[@]}" \
    -jar "${JAR_PATH}" \
    --spring.config.additional-location="file:${CONFIG_PATH}" \
    >> "${INSTALL_DIR}/logs/application/console.log" 2>&1 &
pid=$!
printf '%s\n' "${pid}" > "${PID_FILE}"
log "started pid ${pid}; waiting up to ${STARTUP_TIMEOUT}s for readiness"

deadline=$((SECONDS + STARTUP_TIMEOUT))
while (( SECONDS < deadline )); do
    if ! pid_is_ours "${pid}"; then
        die "process ${pid} exited during startup. See ${INSTALL_DIR}/logs/application/console.log"
    fi
    if curl_ok "${READINESS_URL}"; then
        log "ready pid ${pid}"
        exit 0
    fi
    sleep 1
done

if pid_is_ours "${pid}" && curl_ok "${LIVENESS_URL}"; then
    log "process ${pid} is live but not ready. Readiness URL ${READINESS_URL}"
    exit 2
fi

die "startup timed out and liveness did not succeed"
