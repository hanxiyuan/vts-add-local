#!/usr/bin/env bash
set -euo pipefail

# Run all generated restore_*.sh under /data/output/<collection>/restore
#
# Usage:
#   bash /opt/seatunnel/config/restore.sh

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"
if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "${ENV_FILE}"
  set +a
fi

OUT_ROOT="${OUT_ROOT:-/data/output}"
SEATUNNEL_HOME="${SEATUNNEL_HOME:-/opt/seatunnel}"
MILVUS_URL="${MILVUS_URL:-http://192.162.11.52:29530}"
TOKEN="${TOKEN:-root:Milvus}"
TARGET_DB="${TARGET_DB:-bak2}"

echo "compile java index builder"
cd "${SEATUNNEL_HOME}"
JAR_CP="$(find "${SEATUNNEL_HOME}" -type f -name '*.jar' | paste -sd: -)"
javac -cp "${JAR_CP}" "${SEATUNNEL_HOME}/config/BuildIndexAndLoad.java"

found=0
for d in "${OUT_ROOT}"/*; do
  [[ -d "${d}" ]] || continue
  restore_dir="${d}/restore"
  [[ -d "${restore_dir}" ]] || continue
  for s in "${restore_dir}"/restore_*.sh; do
    [[ -f "${s}" ]] || continue
    found=1

    base="$(basename "${s}")"
    col="${base#restore_}"
    col="${col%.sh}"
    conf_file="${restore_dir}/restore_${col}.conf"
    if [[ ! -f "${conf_file}" ]]; then
      echo "skip(no conf): ${conf_file}"
      continue
    fi

    # Override connection and target db at restore time from .env, no need to re-dump.
    tmp_conf="$(mktemp /tmp/restore_${col}.XXXXXX.conf)"
    sed -E \
      -e "s@^[[:space:]]*url[[:space:]]*=.*@    url = \"${MILVUS_URL}\"@" \
      -e "s@^[[:space:]]*token[[:space:]]*=.*@    token = \"${TOKEN}\"@" \
      -e "s@^[[:space:]]*database[[:space:]]*=.*@    database = \"${TARGET_DB}\"@" \
      "${conf_file}" > "${tmp_conf}"
    echo "run: ${tmp_conf} (MILVUS_URL=${MILVUS_URL}, TARGET_DB=${TARGET_DB})"
    bash "${SEATUNNEL_HOME}/bin/seatunnel.sh" --config "${tmp_conf}" -m local
    rm -f "${tmp_conf}"

    echo "rebuild indexes + load by java: ${col}"
    java -cp "${SEATUNNEL_HOME}/config:${JAR_CP}" BuildIndexAndLoad "${col}"
  done
done

if [[ "${found}" -eq 0 ]]; then
  echo "no restore script found under ${OUT_ROOT}/*/restore"
  exit 1
fi

echo "restore finished"
