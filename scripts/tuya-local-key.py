#!/usr/bin/env python3
"""Busca o Device ID e a chave local (local key) das lâmpadas Tuya.

Este utilitário é de uso único: serve para descobrir a chave que cada lâmpada
recebeu quando foi configurada no aplicativo do fabricante. Depois que a
lâmpada está cadastrada no Valky, nada aqui é necessário de novo.

Ele usa o SDK oficial da Tuya (`tuya-device-sharing-sdk`), o mesmo que o Home
Assistant usa: o aplicativo Tuya Smart autoriza um token por QR code e a API da
nuvem devolve a lista de dispositivos da conta, incluindo a `local_key`. Não
precisa do portal `iot.tuya.com`.

Pré-requisitos (uma vez, em qualquer máquina com internet):

    python3 -m venv /tmp/tuya && /tmp/tuya/bin/pip install tuya-device-sharing-sdk cryptography qrcode

Como obter o "User Code" no aplicativo (Tuya Smart ou Smart Life):

    1. Na barra de baixo, toque em "Meu" (Me).
    2. Toque no ícone de engrenagem (⚙️) no canto superior direito.
    3. Toque em "Conta e Segurança" (Account and Security).
    4. Role até o fim: o "User Code" aparece lá.

Uso:

    /tmp/tuya/bin/python scripts/tuya-local-key.py

    # ou, sem interação (o código no argumento):
    /tmp/tuya/bin/python scripts/tuya-local-key.py --user-code SEUCODIGO

    # saída em JSON, para copiar:
    /tmp/tuya/bin/python scripts/tuya-local-key.py --user-code SEUCODIGO --json

A chave local é um segredo da sua instalação: ela não aparece na API do
Valky em momento nenhum, e é guardada cifrada no banco.
"""

from __future__ import annotations

import argparse
import json
import sys
import time

# Valores oficiais do fluxo de autorização por QR code (os mesmos do Home
# Assistant). O SDK espera exatamente estes.
CLIENT_ID = "HA_3y9q4ak7g4ephrvke"
SCHEMA = "haauthorize"
QR_CONTENT_PREFIX = "tuyaSmart--qrLogin?token="
CODE_INSTRUCTIONS = "User Code (app Tuya Smart > Meu > engrenagem > Conta e Segurança): "


def render_qr(content: str) -> None:
    """Desenha o QR code no terminal; se a biblioteca faltar, mostra o conteúdo."""
    try:
        import qrcode
    except ImportError:
        print("Instale o pacote 'qrcode' para ver o QR no terminal.")
        print("Conteúdo do QR:", content)
        return
    code = qrcode.QRCode(border=1)
    code.add_data(content)
    code.make(fit=True)
    code.print_ascii(invert=True)


def device_row(device) -> dict:
    return {
        "name": getattr(device, "name", "") or "",
        "deviceId": getattr(device, "id", "") or "",
        "localKey": getattr(device, "local_key", "") or "",
        "ip": getattr(device, "ip", "") or "",
        "category": getattr(device, "category", "") or "",
        "productId": getattr(device, "product_id", "") or "",
        "online": getattr(device, "online", None),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Busca Device ID e chave local das lâmpadas Tuya.")
    parser.add_argument("--user-code", default="", help="User Code da conta do aplicativo")
    parser.add_argument("--timeout", type=int, default=180, help="Segundos esperando a autorização no app")
    parser.add_argument("--json", action="store_true", help="Imprime a lista em JSON")
    args = parser.parse_args()

    try:
        from tuya_sharing import LoginControl, Manager
    except ImportError:
        sys.stderr.write("SDK ausente. Instale: pip install tuya-device-sharing-sdk cryptography qrcode\n")
        return 2

    user_code = args.user_code.strip() or input(CODE_INSTRUCTIONS).strip()
    if not user_code:
        sys.stderr.write("User Code vazio.\n")
        return 2

    control = LoginControl()
    response = control.qr_code(CLIENT_ID, SCHEMA, user_code)
    if not response.get("success"):
        sys.stderr.write(f"A Tuya recusou o acesso: {response.get('msg') or response}\n")
        return 1

    token = response["result"]["qrcode"]
    print("Abra o aplicativo Tuya Smart e escaneie este QR code:\n")
    render_qr(QR_CONTENT_PREFIX + token)
    print("\nNo aplicativo: + (adicionar) > Escanear > aponte para o QR acima.")
    print("Aguardando a autorização… (Ctrl+C para desistir)")

    deadline = time.time() + max(30, args.timeout)
    info = None
    try:
        while time.time() < deadline:
            authorized, result = control.login_result(token, CLIENT_ID, user_code)
            if authorized:
                info = result
                break
            time.sleep(3)
    except KeyboardInterrupt:
        sys.stderr.write("\nCancelado.\n")
        return 130
    if not info:
        sys.stderr.write("Tempo esgotado esperando a autorização no aplicativo.\n")
        return 1

    manager = Manager(CLIENT_ID, user_code, info["terminal_id"], info["endpoint"], info)
    manager.update_device_cache()
    rows = [device_row(device) for device in manager.device_map.values()]

    if args.json:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
        return 0

    print(f"\n{len(rows)} dispositivo(s) na conta:\n")
    for row in rows:
        key = row["localKey"] or "(vazio — costuma ser sub-dispositivo Zigbee, que não é controlado localmente)"
        print(f"- {row['name'] or '(sem nome)'}")
        print(f"    Device ID : {row['deviceId']}")
        print(f"    Local key : {key}")
        print(f"    IP        : {row['ip'] or '-'}")
        print(f"    Categoria : {row['category']}   produto: {row['productId']}")
        print(f"    Online    : {row['online']}")
        print()
    print("No Valky: Dispositivos > Iluminação > + , preencha o Device ID e a chave local.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
