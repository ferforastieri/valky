#!/bin/sh
set -eu

REPOSITORY="ferforastieri/valky"
REQUESTED_VERSION="${VALKY_VERSION:-latest}"
INSTALL_ROOT="${VALKYRIS_HOME:-${XDG_DATA_HOME:-${HOME}/.local/share}/valky}"
TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/valky.XXXXXX")"

cleanup() {
  rm -rf -- "$TEMP_ROOT"
}
trap cleanup EXIT HUP INT TERM

fail() {
  printf 'Valky: %s\n' "$1" >&2
  exit 1
}

# 16 caracteres hexadecimais (64 bits) que o administrador informa no primeiro
# acesso do aplicativo.
generate_setup_token() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 8
  else
    head -c 32 /dev/urandom | sha256sum | cut -c1-16
  fi
}

# Token do socket local do updater. Fica no .env (backend) e em
# /etc/valky/updater.env (serviço), com permissão 600.
generate_updater_token() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex 32
  else
    head -c 64 /dev/urandom | sha256sum | cut -d ' ' -f 1
  fi
}

command -v curl >/dev/null 2>&1 || fail "curl não foi encontrado."
command -v docker >/dev/null 2>&1 || fail "Docker não foi encontrado. Instale-o antes de continuar."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 não foi encontrado."
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum não foi encontrado."

case "$(uname -m)" in
  x86_64|amd64) UPDATER_ARCH=amd64 ;;
  aarch64|arm64) UPDATER_ARCH=arm64 ;;
  *) fail "arquitetura não suportada: $(uname -m)." ;;
esac
UPDATER_ASSET="valky-updater-linux-${UPDATER_ARCH}"

if [ "$REQUESTED_VERSION" = "latest" ]; then
  RELEASE_URL="https://github.com/${REPOSITORY}/releases/latest/download"
else
  RELEASE_URL="https://github.com/${REPOSITORY}/releases/download/${REQUESTED_VERSION}"
fi

printf 'Valky: baixando a release %s...\n' "$REQUESTED_VERSION"
for asset in compose.yaml mediamtx.yml VERSION SHA256SUMS "$UPDATER_ASSET"; do
  curl -fsSL --retry 3 --proto '=https' --proto-redir '=https' --tlsv1.2 \
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
verify_asset "$UPDATER_ASSET"

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
    printf 'VALKY_VERSION=%s\n' "$RELEASE_VERSION"
    printf 'VALKY_PORT=8443\n'
  } > "$ENV_FILE"
else
  # Também remove as variáveis da instalação antiga (VALKYRIS_*), que ficavam
  # no arquivo depois da troca de nome e confundiam a configuração.
  sed '/^VALKY_PUBLIC_URL=/d; /^VALKY_SETUP_TOKEN=/d; /^VALKY_UPDATER_URL=/d; /^VALKYRIS_/d' "$ENV_FILE" > "${TEMP_ROOT}/env-clean"
  mv "${TEMP_ROOT}/env-clean" "$ENV_FILE"
  if grep -q '^VALKY_VERSION=' "$ENV_FILE"; then
    sed "s/^VALKY_VERSION=.*/VALKY_VERSION=${RELEASE_VERSION}/" "$ENV_FILE" > "${TEMP_ROOT}/env"
    mv "${TEMP_ROOT}/env" "$ENV_FILE"
  else
    printf '\nVALKY_VERSION=%s\n' "$RELEASE_VERSION" >> "$ENV_FILE"
  fi
fi

# O código de instalação é gerado aqui e só existe no servidor: é ele que
# autoriza criar o primeiro administrador, em vez de deixar a instância aberta
# para quem alcançá-la primeiro. Uma atualização preserva o código existente.
if ! grep -Eq '^VALKY_SETUP_TOKEN=.+$' "$ENV_FILE"; then
  printf '\nVALKY_SETUP_TOKEN=%s\n' "$(generate_setup_token)" >> "$ENV_FILE"
fi
SETUP_TOKEN="$(sed -n 's/^VALKY_SETUP_TOKEN=//p' "$ENV_FILE" | head -1)"
if ! grep -Eq '^VALKY_UPDATER_TOKEN=.+$' "$ENV_FILE"; then
  printf '\nVALKY_UPDATER_TOKEN=%s\n' "$(generate_updater_token)" >> "$ENV_FILE"
fi
UPDATER_TOKEN="$(sed -n 's/^VALKY_UPDATER_TOKEN=//p' "$ENV_FILE" | head -1)"
[ -n "$UPDATER_TOKEN" ] || fail "não foi possível gerar o token do updater."
# Preserve explicitly configured public/LAN candidates across upgrades.
if ! grep -Eq '^VALKY_WEBRTC_HOSTS=.+$' "$ENV_FILE"; then
  printf '\nVALKY_WEBRTC_HOSTS=%s\n' "$LAN_ADDRESS" >> "$ENV_FILE"
fi
chmod 600 "$ENV_FILE"

# O backend roda no container e manda o magic packet de Wake-on-LAN para o
# broadcast da LAN. O kernel descarta broadcast dirigido que atravessa a bridge
# do Docker (bc_forwarding=0), então a TV nunca acorda. O helper reaplica o
# sysctl nas bridges e o timer cobre redes recriadas. Fora de root/systemd a
# instalação segue sem ele: o resto não depende disso.
install_wol_helper() {
  [ "$(id -u)" = "0" ] || return 0
  command -v systemctl >/dev/null 2>&1 || return 0
  command -v sysctl >/dev/null 2>&1 || return 0
  [ -d /sys/class/net ] || return 0
  [ -d /etc/systemd/system ] || return 0
  mkdir -p /usr/local/sbin

  cat > /usr/local/sbin/valky-wol-broadcast.sh <<'SCRIPT'
#!/bin/sh
# Habilita o broadcast dirigido nas bridges Docker. Sem isto o magic packet de
# Wake-on-LAN que sai do container para o broadcast da LAN é descartado.
for path in /sys/class/net/br-*; do
  [ -e "$path" ] || continue
  name=${path##*/}
  current=$(cat "/proc/sys/net/ipv4/conf/$name/bc_forwarding" 2>/dev/null || echo 1)
  [ "$current" = "1" ] && continue
  sysctl -w "net.ipv4.conf.$name.bc_forwarding=1" >/dev/null 2>&1 || true
done
SCRIPT
  chmod 755 /usr/local/sbin/valky-wol-broadcast.sh

  cat > /etc/systemd/system/valky-wol-broadcast.service <<'UNIT'
[Unit]
Description=Broadcast dirigido para Wake-on-LAN do Valky
After=docker.service
Wants=docker.service

[Service]
Type=oneshot
ExecStart=/usr/local/sbin/valky-wol-broadcast.sh
UNIT

  cat > /etc/systemd/system/valky-wol-broadcast.timer <<'UNIT'
[Unit]
Description=Reaplica o broadcast de WoL do Valky

[Timer]
OnBootSec=20s
OnUnitActiveSec=60s

[Install]
WantedBy=timers.target
UNIT

  systemctl daemon-reload >/dev/null 2>&1 || true
  systemctl enable --now valky-wol-broadcast.timer >/dev/null 2>&1 || true
  systemctl start valky-wol-broadcast.service >/dev/null 2>&1 || true
}

# O updater roda no host (fora do container), com acesso ao Docker, e só executa
# o instalador oficial. O backend fala com ele por um socket Unix local; o token
# é o mesmo que o Compose passa ao backend. Em instalações sem root/systemd o
# botão de atualização automática fica indisponível, mas o resto funciona.
install_updater() {
  if [ "$(id -u)" != "0" ]; then
    printf 'Valky: atualização automática exige root e systemd; seguindo sem ela.\n' >&2
    return 0
  fi
  command -v systemctl >/dev/null 2>&1 || return 0
  [ -d /etc/systemd/system ] || return 0
  install -m 0755 "${TEMP_ROOT}/${UPDATER_ASSET}" /usr/local/bin/valky-updater
  mkdir -p /etc/valky /run/valky-updater
  chmod 0755 /run/valky-updater
  cat > /etc/valky/updater.env <<EOF
VALKY_UPDATER_SOCKET=/run/valky-updater/updater.sock
VALKY_UPDATER_TOKEN=${UPDATER_TOKEN}
VALKYRIS_HOME=${INSTALL_ROOT}
EOF
  chmod 600 /etc/valky/updater.env
  cat > /etc/systemd/system/valky-updater.service <<'UNIT'
[Unit]
Description=Atualizador local do Valky
After=network-online.target docker.service
Wants=network-online.target docker.service

[Service]
Type=simple
EnvironmentFile=/etc/valky/updater.env
Environment=HOME=/root
ExecStart=/usr/local/bin/valky-updater
Restart=always
RestartSec=5
RuntimeDirectory=valky-updater
RuntimeDirectoryMode=0755

[Install]
WantedBy=multi-user.target
UNIT
  systemctl daemon-reload >/dev/null 2>&1 || true
  systemctl enable valky-updater >/dev/null 2>&1 || true
  # Não reinicie um updater já ativo: ele pode estar executando o próprio
  # instalador neste momento. O binário novo entra no próximo restart/reboot.
  if ! systemctl is-active --quiet valky-updater; then
    systemctl start valky-updater >/dev/null 2>&1 || true
  fi
  for attempt in $(seq 1 10); do
    [ -S /run/valky-updater/updater.sock ] && break
    sleep 1
  done
  if [ ! -S /run/valky-updater/updater.sock ]; then
    printf 'Valky: aviso: o updater não abriu o socket; a atualização pelo app ficará indisponível.\n' >&2
  fi
}

install_updater
printf 'Valky: iniciando os serviços em %s...\n' "$INSTALL_ROOT"
docker compose --project-directory "$INSTALL_ROOT" --env-file "$ENV_FILE" pull
docker compose --project-directory "$INSTALL_ROOT" --env-file "$ENV_FILE" up -d --remove-orphans
install_wol_helper

PORT="$(awk -F= '$1 == "VALKY_PORT" { print substr($0, index($0, "=") + 1); exit }' "$ENV_FILE")"
[ -n "$PORT" ] || PORT=8443

for attempt in $(seq 1 30); do
  if curl -kfsS --max-time 2 "https://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then
    break
  fi
  [ "$attempt" -lt 30 ] || fail "os serviços iniciaram, mas o backend não respondeu a tempo."
  sleep 1
done

printf '\nValky %s está pronto. Abra o app para criar o primeiro administrador:\n\n' "$RELEASE_VERSION"
printf 'Endereço local: https://%s:%s\n' "$LAN_ADDRESS" "$PORT"
printf 'Código de instalação: %s\n' "$SETUP_TOKEN"
printf '\nO código acima só é pedido ao criar a PRIMEIRA conta; guarde-o com o seu .env.\n'
printf 'Se você usa Caddy ou outro proxy, informe o domínio HTTPS no app no lugar do endereço local.\n'
printf 'Para receber alertas nativos, configure a conta de serviço FCM conforme https://ferforastieri.github.io/valky/pt-BR/docs/\n'
