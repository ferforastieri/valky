#!/bin/sh
set -eu

REPOSITORY="ferforastieri/valkyris"
REQUESTED_VERSION="${VALKYRIS_VERSION:-latest}"
INSTALL_ROOT="${VALKYRIS_HOME:-${XDG_DATA_HOME:-${HOME}/.local/share}/valkyris}"
TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/valkyris.XXXXXX")"

cleanup() {
  rm -rf -- "$TEMP_ROOT"
}
trap cleanup EXIT HUP INT TERM

fail() {
  printf 'Valkyris: %s\n' "$1" >&2
  exit 1
}

generate_secret() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
  fi
}

command -v curl >/dev/null 2>&1 || fail "curl não foi encontrado."
command -v docker >/dev/null 2>&1 || fail "Docker não foi encontrado. Instale-o antes de continuar."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 não foi encontrado."
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum não foi encontrado."

if [ "$REQUESTED_VERSION" = "latest" ]; then
  RELEASE_URL="https://github.com/${REPOSITORY}/releases/latest/download"
else
  RELEASE_URL="https://github.com/${REPOSITORY}/releases/download/${REQUESTED_VERSION}"
fi

printf 'Valkyris: baixando a release %s...\n' "$REQUESTED_VERSION"
for asset in compose.yaml mediamtx.yml VERSION SHA256SUMS; do
  curl -fsSL --retry 3 --proto '=https' --tlsv1.2 \
    "${RELEASE_URL}/${asset}" -o "${TEMP_ROOT}/${asset}" || fail "não foi possível baixar ${asset}."
done

verify_asset() {
  asset="$1"
  expected="$(awk -v file="$asset" '$2 == file || $2 == "*" file { print $1; exit }' "${TEMP_ROOT}/SHA256SUMS")"
  [ -n "$expected" ] || fail "checksum ausente para ${asset}."
  actual="$(sha256sum "${TEMP_ROOT}/${asset}" | awk '{ print $1 }')"
  [ "$actual" = "$expected" ] || fail "checksum inválido para ${asset}."
}

verify_asset compose.yaml
verify_asset mediamtx.yml
verify_asset VERSION

RELEASE_VERSION="$(tr -d '\r\n' < "${TEMP_ROOT}/VERSION")"
[ -n "$RELEASE_VERSION" ] || fail "a release não informou uma versão."
case "$RELEASE_VERSION" in
  *[!A-Za-z0-9._-]*) fail "versão de release inválida." ;;
esac

mkdir -p "$INSTALL_ROOT"
for asset in compose.yaml mediamtx.yml; do
  target="${INSTALL_ROOT}/${asset}"
  if [ -d "$target" ]; then
    fail "${target} é um diretório; corrija-o manualmente antes de executar o instalador."
  elif [ -f "$target" ]; then
    cp -p "$target" "${target}.previous"
  fi
  mv "${TEMP_ROOT}/${asset}" "$target"
done

LAN_ADDRESS="$(hostname -I 2>/dev/null | awk '{ print $1 }')"
[ -n "$LAN_ADDRESS" ] || LAN_ADDRESS="localhost"
ENV_FILE="${INSTALL_ROOT}/.env"
if [ ! -f "$ENV_FILE" ]; then
  {
    printf 'VALKYRIS_VERSION=%s\n' "$RELEASE_VERSION"
    printf 'VALKYRIS_PORT=8443\n'
    printf 'VALKYRIS_UPDATER_TOKEN=%s\n' "$(generate_secret)"
  } > "$ENV_FILE"
else
  sed '/^VALKYRIS_PUBLIC_URL=/d; /^VALKYRIS_SETUP_TOKEN=/d' "$ENV_FILE" > "${TEMP_ROOT}/env-clean"
  mv "${TEMP_ROOT}/env-clean" "$ENV_FILE"
  if grep -q '^VALKYRIS_VERSION=' "$ENV_FILE"; then
    sed "s/^VALKYRIS_VERSION=.*/VALKYRIS_VERSION=${RELEASE_VERSION}/" "$ENV_FILE" > "${TEMP_ROOT}/env"
    mv "${TEMP_ROOT}/env" "$ENV_FILE"
  else
    printf '\nVALKYRIS_VERSION=%s\n' "$RELEASE_VERSION" >> "$ENV_FILE"
  fi
  if ! grep -Eq '^VALKYRIS_UPDATER_TOKEN=[^[:space:]]+$' "$ENV_FILE"; then
    sed '/^VALKYRIS_UPDATER_TOKEN=/d' "$ENV_FILE" > "${TEMP_ROOT}/env-token"
    mv "${TEMP_ROOT}/env-token" "$ENV_FILE"
    printf 'VALKYRIS_UPDATER_TOKEN=%s\n' "$(generate_secret)" >> "$ENV_FILE"
  fi
fi
chmod 600 "$ENV_FILE"

printf 'Valkyris: iniciando os serviços em %s...\n' "$INSTALL_ROOT"
docker compose --project-directory "$INSTALL_ROOT" --env-file "$ENV_FILE" pull
docker compose --project-directory "$INSTALL_ROOT" --env-file "$ENV_FILE" up -d --remove-orphans

PORT="$(awk -F= '$1 == "VALKYRIS_PORT" { print substr($0, index($0, "=") + 1); exit }' "$ENV_FILE")"
[ -n "$PORT" ] || PORT=8443

for attempt in $(seq 1 30); do
  if curl -kfsS --max-time 2 "https://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then
    break
  fi
  [ "$attempt" -lt 30 ] || fail "os serviços iniciaram, mas o backend não respondeu a tempo."
  sleep 1
done

printf '\nValkyris %s está pronto. Abra o app para criar o primeiro administrador:\n\n' "$RELEASE_VERSION"
printf 'Endereço local: https://%s:%s\n' "$LAN_ADDRESS" "$PORT"
printf '\nSe você usa Caddy ou outro proxy, informe o domínio HTTPS no app no lugar do endereço local.\n'
printf 'Para receber alertas nativos, configure a conta de serviço FCM conforme https://valkyris.vercel.app/pt-BR/docs\n'
