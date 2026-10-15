#!/usr/bin/env bash
# Install the release directory to /opt/msisdn-api and prepare the service user.
# Run as root on RHEL. This script does not start the service and does not
# write a database password.

set -o errexit
set -o nounset
set -o pipefail

if [[ "$(id -u)" -ne 0 ]]; then
    echo "install.sh must be run as root" >&2
    exit 1
fi

SOURCE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="${TARGET_DIR:-/opt/msisdn-api}"
RUN_USER="${RUN_USER:-msisdn}"
RUN_GROUP="${RUN_GROUP:-msisdn}"

if ! id "${RUN_USER}" >/dev/null 2>&1; then
    useradd --system --home-dir "${TARGET_DIR}" --shell /sbin/nologin "${RUN_USER}"
fi

mkdir -p "${TARGET_DIR}"
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
    cat > "${TARGET_DIR}/msisdn-api.env" <<'EOF'
INSTALL_DIR=/opt/msisdn-api
JAVA_HOME=/opt/java/jdk-17
HTTP_PORT=8080
USE_SYSTEMD=auto
ORACLE_JDBC_URL=jdbc:oracle:thin:@//oracle-host:1521/service_name
ORACLE_USERNAME=MSISDN_API
ORACLE_PASSWORD=
MSISDN_API_KEY=
MSISDN_INSTANCE_ID=
EOF
    chmod 640 "${TARGET_DIR}/msisdn-api.env"
fi

chown -R "${RUN_USER}:${RUN_GROUP}" "${TARGET_DIR}"
chmod 750 "${TARGET_DIR}"
chmod 640 "${TARGET_DIR}/application.yml" "${TARGET_DIR}/msisdn-api.env"
find "${TARGET_DIR}/data" "${TARGET_DIR}/logs" "${TARGET_DIR}/run" -type d -exec chmod 750 {} \;

if [[ -f /etc/systemd/system && -d /etc/systemd/system ]]; then
    cp "${TARGET_DIR}/deploy/msisdn-api.service" /etc/systemd/system/msisdn-api.service
    systemctl daemon-reload
    echo "systemd unit installed. Enable it with: systemctl enable --now msisdn-api"
fi

echo "Installed to ${TARGET_DIR}. Edit ${TARGET_DIR}/msisdn-api.env before starting."
echo "Do not enable both an independent shell start and systemd. Leave USE_SYSTEMD=auto."
