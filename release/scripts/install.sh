#!/usr/bin/env bash
# Install the release tree into a directory the current user can write.
#
# FMSUser (and any other non-root account) runs this script directly.
# It does not call sudo, does not create a user, and does not install a
# systemd system unit. The process is started with scripts/start.sh and
# kept up by that user's crontab.
#
# An administrator who can become root may set TARGET_DIR=/opt/msisdn-api.
# Only that path installs the system unit.

set -o errexit
set -o nounset
set -o pipefail

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="/opt/msisdn-api"
RUN_AS="$(id -un)"
RUN_GROUP="$(id -gn)"

if [[ "$(id -u)" -eq 0 ]]; then
    TARGET_DIR="${TARGET_DIR:-/opt/msisdn-api}"
    RUN_USER="${RUN_USER:-msisdn}"
    RUN_GROUP="${RUN_GROUP:-msisdn}"
    USE_SYSTEMD_VALUE="auto"
else
    TARGET_DIR="${TARGET_DIR:-${HOME}/msisdn-api}"
    RUN_USER="${RUN_USER:-${RUN_AS}}"
    USE_SYSTEMD_VALUE="false"
    if [[ "${RUN_USER}" != "${RUN_AS}" ]]; then
        echo "install.sh is running as ${RUN_AS} and cannot install for ${RUN_USER} without root" >&2
        exit 1
    fi
fi

if [[ "${TARGET_DIR}" == *"|"* || "${TARGET_DIR}" == *"&"* || "${TARGET_DIR}" == *'\'* ]]; then
    echo "TARGET_DIR contains a character the path rewrite cannot handle: ${TARGET_DIR}" >&2
    exit 1
fi

mkdir -p "${TARGET_DIR}"
if [[ ! -w "${TARGET_DIR}" ]]; then
    echo "cannot write to ${TARGET_DIR}. Choose a directory owned by ${RUN_AS}, for example ${HOME}/msisdn-api" >&2
    exit 1
fi

[[ -f "${SOURCE_DIR}/msisdn-api.jar" ]] || {
    echo "msisdn-api.jar was not found in ${SOURCE_DIR}. Run install.sh from the release directory." >&2
    exit 1
}

cp -a "${SOURCE_DIR}/msisdn-api.jar" "${TARGET_DIR}/msisdn-api.jar"
cp -a "${SOURCE_DIR}/application.yml" "${TARGET_DIR}/application.yml"
cp -a "${SOURCE_DIR}/docs.md" "${TARGET_DIR}/docs.md"
if [[ -f "${SOURCE_DIR}/README.md" ]]; then
    cp -a "${SOURCE_DIR}/README.md" "${TARGET_DIR}/README.md"
fi
mkdir -p "${TARGET_DIR}/config" "${TARGET_DIR}/sql" "${TARGET_DIR}/scripts" "${TARGET_DIR}/deploy"
cp -a "${SOURCE_DIR}/config/." "${TARGET_DIR}/config/"
cp -a "${SOURCE_DIR}/sql/." "${TARGET_DIR}/sql/"
cp -a "${SOURCE_DIR}/scripts/." "${TARGET_DIR}/scripts/"
cp -a "${SOURCE_DIR}/deploy/." "${TARGET_DIR}/deploy/"
chmod 755 "${TARGET_DIR}/scripts/"*.sh

if [[ "${TARGET_DIR}" != "${PREFIX}" ]]; then
    while IFS= read -r -d '' file; do
        if grep -q "${PREFIX}" "${file}"; then
            sed -i "s|${PREFIX}|${TARGET_DIR}|g" "${file}"
        fi
    done < <(find "${TARGET_DIR}" -type f \
        \( -name '*.yml' -o -name '*.xml' -o -name '*.service' -o -name '*.example' -o -name '*.md' \) \
        -print0)
fi

mkdir -p \
    "${TARGET_DIR}/run" \
    "${TARGET_DIR}/logs/application" \
    "${TARGET_DIR}/logs/audit/active" \
    "${TARGET_DIR}/logs/audit/completed" \
    "${TARGET_DIR}/logs/audit/archive" \
    "${TARGET_DIR}/logs/audit/quarantine" \
    "${TARGET_DIR}/data/blacklist/incoming" \
    "${TARGET_DIR}/data/blacklist/processed" \
    "${TARGET_DIR}/data/blacklist/rejected" \
    "${TARGET_DIR}/data/blacklist/snapshot" \
    "${TARGET_DIR}/data/checkpoints/oracle" \
    "${TARGET_DIR}/data/checkpoints/csv" \
    "${TARGET_DIR}/data/export"

if [[ ! -f "${TARGET_DIR}/msisdn-api.env" ]]; then
    cat > "${TARGET_DIR}/msisdn-api.env" <<EOF
# Loaded by the shell scripts. Do not commit this file after you fill in secrets.
INSTALL_DIR=${TARGET_DIR}
RUN_USER=${RUN_USER}
HTTP_PORT=8080
USE_SYSTEMD=${USE_SYSTEMD_VALUE}
MSISDN_LOG_DIR=${TARGET_DIR}/logs
# Uncomment and set this when Java 17 is not on PATH.
# JAVA_HOME=/path/to/jdk-17
ORACLE_JDBC_URL=jdbc:oracle:thin:@//oracle-host:1521/service_name
ORACLE_USERNAME=MSISDN_API
ORACLE_PASSWORD=
MSISDN_API_KEY=
EOF
    chmod 600 "${TARGET_DIR}/msisdn-api.env"
fi

chmod 640 "${TARGET_DIR}/application.yml" || true
find "${TARGET_DIR}/data" "${TARGET_DIR}/logs" "${TARGET_DIR}/run" -type d -exec chmod 750 {} \;

if [[ "$(id -u)" -eq 0 ]]; then
    if ! id "${RUN_USER}" >/dev/null 2>&1; then
        useradd --system --home-dir "${TARGET_DIR}" --shell /sbin/nologin "${RUN_USER}"
    fi
    chown -R "${RUN_USER}:${RUN_GROUP}" "${TARGET_DIR}"
    chmod 750 "${TARGET_DIR}"
    if [[ -d /etc/systemd/system ]]; then
        cp "${TARGET_DIR}/deploy/msisdn-api.service" /etc/systemd/system/msisdn-api.service
        systemctl daemon-reload
        echo "systemd unit installed. An administrator enables it with: systemctl enable --now msisdn-api"
    fi
else
    chmod 750 "${TARGET_DIR}" || true
fi

echo "Installed to ${TARGET_DIR} as ${RUN_USER}."
echo "Edit ${TARGET_DIR}/msisdn-api.env and set ORACLE_JDBC_URL, ORACLE_PASSWORD, and MSISDN_API_KEY."
if [[ "$(id -u)" -eq 0 ]]; then
    echo "Leave USE_SYSTEMD=auto when the system unit is installed."
else
    echo "Start it with: ${TARGET_DIR}/scripts/start.sh"
    echo "Install the per-minute watchdog with: ${TARGET_DIR}/scripts/setup-cron.sh"
    echo "USE_SYSTEMD is false. FMSUser does not need sudo, and the scripts will not call systemctl."
fi
