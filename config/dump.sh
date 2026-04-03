#!/usr/bin/env bash
set -euo pipefail

# 1) Run existing export conf to dump parquet
# 2) Run Java generator to put restore_*.conf and restore_*.sh in each output folder
#
# Usage:
#   bash /opt/seatunnel/config/dump.sh
# Config:
#   /opt/seatunnel/config/.env

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "${ENV_FILE}"
  set +a
fi

SEATUNNEL_HOME="${SEATUNNEL_HOME:-/opt/seatunnel}"
EXPORT_CONF="${EXPORT_CONF:-${SEATUNNEL_HOME}/config/milvus_to_local_multi.conf}"
MILVUS_URL="${MILVUS_URL:-http://192.162.11.52:29530}"
TOKEN="${TOKEN:-root:Milvus}"
SRC_DB="${SRC_DB:-default}"

echo "[1/2] export parquet by ${EXPORT_CONF}"
# Override source connection at runtime from .env (MILVUS_URL/TOKEN/SRC_DB)
tmp_export_conf="$(mktemp /tmp/export.XXXXXX.conf)"
sed -E \
  -e "s@^[[:space:]]*url[[:space:]]*=.*@    url = \"${MILVUS_URL}\"@" \
  -e "s@^[[:space:]]*token[[:space:]]*=.*@    token = \"${TOKEN}\"@" \
  "${EXPORT_CONF}" > "${tmp_export_conf}"
bash "${SEATUNNEL_HOME}/bin/seatunnel.sh" --config "${tmp_export_conf}" -m local
rm -f "${tmp_export_conf}"

echo "[2/2] generate restore conf/script per folder by Java"
cd "${SEATUNNEL_HOME}"

# Build full classpath from all jars under SEATUNNEL_HOME to avoid missing runtime deps (e.g. slf4j)
JAR_CP="$(find "${SEATUNNEL_HOME}" -type f -name '*.jar' | paste -sd: -)"
if [[ -z "${JAR_CP}" ]]; then
  echo "no jars found under ${SEATUNNEL_HOME}"
  exit 1
fi

javac -cp "${JAR_CP}" "${SEATUNNEL_HOME}/config/GenerateRestorePerFolder.java"
java -cp "${SEATUNNEL_HOME}/config:${JAR_CP}" GenerateRestorePerFolder

echo "[extra] export index metadata to _indexes.json by Java"
javac -cp "${JAR_CP}" "${SEATUNNEL_HOME}/config/ExportIndexJson.java"
java -cp "${SEATUNNEL_HOME}/config:${JAR_CP}" ExportIndexJson

echo "dump finished"
