#!/usr/bin/env python3
"""
Servidor de ativação local do Valkyris.

Por que ele existe: uma lâmpada Tuya não aceita controle local antes de ser
ativada uma vez — e a chave local dela é entregue pelo servidor do fabricante
nesse momento (campo `localKey` da resposta `s.gw.dev.pk.active`). Este serviço
faz o papel desse servidor dentro da sua rede: responde a ativação com a chave
que o Valkyris está usando na janela de pareamento. Nada sai da sua casa e
nenhuma conta é necessária.

Como a lâmpada fala com ele: depois de receber a rede e a senha, a lâmpada
procura os endereços do fabricante. Com os redirecionamentos de DNS descritos em
docs/lighting.md, esses nomes apontam para este servidor, que responde:

  - HTTP  (:80)  as rotas de ativação em texto (gw.json / d.json);
  - TLS-PSK (:443 e :8886) os mesmos dados no canal protegido que as lâmpadas
    usam, com a chave derivada da identidade do próprio aparelho.

A chave entregue é sempre a que o backend tem na janela de pareamento
(settings.light_pairing_token no SQLite), então lâmpada e Valkyris combinam
sozinhos — inclusive quando a chave roda para a próxima lâmpada.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import os
import signal
import socket
import sqlite3
import ssl
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse

try:  # a ponte TLS-PSK só existe quando a biblioteca está instalada
    import sslpsk
except ImportError:  # pragma: no cover
    sslpsk = None

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

# Constantes do protocolo de ativação (documentadas pela comunidade a partir do
# tráfego do aplicativo; ver tuya-convert, MIT).
IDENTITY_PREFIX = b"BAohbmd6aG91IFR1"
PSK_HINT = b"1dHRsc2NjbHltbGx3eWh5" + b"0000000000000000"
DEFAULT_SEC_KEY = "0000000000000000"
DEFAULT_TOKEN = "0000000000000000"

ARGS: argparse.Namespace
LOG_LOCK = threading.Lock()
STATS = {"activations": 0, "requests": 0}


def log(*parts: Any) -> None:
    with LOG_LOCK:
        print(time.strftime("%H:%M:%S"), *parts, flush=True)


# ---------------------------------------------------------------------------
# Criptografia do protocolo de ativação
# ---------------------------------------------------------------------------

def pad(raw: bytes) -> bytes:
    padding = 16 - len(raw) % 16
    return raw + bytes([padding]) * padding


def unpad(raw: bytes) -> bytes:
    if raw and 1 <= raw[-1] <= 16 and raw[-1] <= len(raw):
        return raw[: -raw[-1]]
    return raw


def encrypt_ecb(raw: bytes, key: bytes) -> bytes:
    encryptor = Cipher(algorithms.AES(key), modes.ECB()).encryptor()
    return encryptor.update(pad(raw)) + encryptor.finalize()


def decrypt_ecb(raw: bytes, key: bytes) -> bytes:
    decryptor = Cipher(algorithms.AES(key), modes.ECB()).decryptor()
    return unpad(decryptor.update(raw) + decryptor.finalize())


def encrypt_cbc(raw: bytes, key: bytes, iv: bytes) -> bytes:
    encryptor = Cipher(algorithms.AES(key), modes.CBC(iv)).encryptor()
    return encryptor.update(raw) + encryptor.finalize()


def json_bytes(value: Any) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode()


def psk_for(identity: bytes) -> bytes:
    """Deriva a chave PSK do aparelho, como o aplicativo do fabricante faz."""
    identity = identity[1:] if identity[:1] == b"\x00" else identity
    if identity[:16] != IDENTITY_PREFIX:
        log("identidade inesperada:", bytes(identity[:24]))
    key = hashlib.md5(PSK_HINT[-16:]).digest()
    iv = hashlib.md5(identity).digest()
    return encrypt_cbc(identity[:32], key, iv)


# ---------------------------------------------------------------------------
# A chave que a lâmpada vai receber
# ---------------------------------------------------------------------------

def pairing_values() -> tuple[str, str]:
    """Lê chave e segredo da janela de pareamento; usa os padrões se não achar."""
    try:
        connection = sqlite3.connect(f"file:{ARGS.database}?mode=ro", uri=True, timeout=3)
        try:
            rows = dict(connection.execute(
                "SELECT key, value FROM settings WHERE key IN ('light_pairing_token','light_pairing_secret')"
            ).fetchall())
            token = rows.get("light_pairing_token", "")
            secret = rows.get("light_pairing_secret", "")
            if len(token) == 16 and len(secret) == 16:
                return token, secret
        finally:
            connection.close()
    except Exception as exc:  # banco ausente ou bloqueado: segue com os padrões
        log("aviso: não li a janela de pareamento do Valkyris:", exc)
    return ARGS.local_key, ARGS.sec_key


def pairing_key() -> str:
    """Só a chave, para quem precisa apenas dela."""
    try:
        connection = sqlite3.connect(f"file:{ARGS.database}?mode=ro", uri=True, timeout=3)
        try:
            row = connection.execute("SELECT value FROM settings WHERE key='light_pairing_token'").fetchone()
            if row and len(row[0]) == 16:
                return row[0]
        finally:
            connection.close()
    except Exception as exc:  # banco ausente ou bloqueado: segue com o padrão
        log("aviso: não li a chave do Valkyris:", exc)
    return ARGS.local_key


def token_answer(host: str, timezone: str, encrypted: bool) -> dict:
    """Resposta de `s.gw.token.get`: para onde a lâmpada deve falar depois."""
    answer = {
        "gwApiUrl": "http://%s/gw.json" % host,
        "stdTimeZone": timezone,
        "mqttRanges": "",
        "timeZone": timezone,
        "httpsPSKUrl": "https://%s/gw.json" % host,
        "mediaMqttUrl": host,
        "gwMqttUrl": host,
        "dstIntervals": [],
    }
    if encrypted:
        answer.update({
            "mqttsUrl": host,
            "mqttsPSKUrl": host,
            "mediaMqttsUrl": host,
            "aispeech": host,
        })
    return answer


def activation_answer(key: str, sec_key: str) -> dict:
    """Resposta de `s.gw.dev.pk.active`: é aqui que a chave local é entregue."""
    return {
        "schema": json.dumps([{"mode": "rw", "property": {"type": "bool"}, "id": 1, "type": "obj"}] * 20),
        "uid": "00000000000000000000",
        "devEtag": "0000000000",
        "secKey": sec_key,
        "schemaId": "0000000000",
        "localKey": key,
    }


# ---------------------------------------------------------------------------
# Servidor HTTP (texto) e a ponte TLS-PSK que as lâmpadas usam
# ---------------------------------------------------------------------------

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args: Any) -> None:  # silencia o log padrão
        pass

    def reply(self, payload: dict | None, encrypted: bool) -> None:
        timestamp = int(time.time())
        if encrypted:
            sealed = base64.b64encode(encrypt_ecb(json_bytes(payload or {}), ARGS.sec_key.encode())).decode()
            signature = "result=%s||t=%d||%s" % (sealed, timestamp, ARGS.sec_key)
            answer = {
                "result": sealed,
                "t": timestamp,
                "sign": hashlib.md5(signature.encode()).hexdigest()[8:24],
            }
        else:
            answer = {"t": timestamp, "e": False, "success": True}
            if payload is not None:
                answer["result"] = payload
        body = json_bytes(answer)
        self.send_response(200)
        self.send_header("Content-Type", "application/json;charset=UTF-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Content-Language", "zh-CN")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self) -> None:
        self.do_POST()

    def do_POST(self) -> None:
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        action = (query.get("a") or ["?"])[0]
        device = (query.get("gwId") or query.get("devId") or ["?"])[0]
        encrypted = (query.get("et") or ["0"])[0] == "1"
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        body = raw[5:] if len(raw) > 5 else b""
        STATS["requests"] += 1
        if body:
            try:
                log("pedido", action, device, decrypt_ecb(binascii.unhexlify(body), ARGS.sec_key.encode())[:200])
            except Exception:
                log("pedido", action, device, body[:200])

        if action == "s.gw.token.get":
            # A lâmpada aprende aqui com quem falar daqui para a frente. Usamos o
            # mesmo nome que ela usou para chegar (o redirecionamento de DNS
            # continua valendo), então não é preciso fixar nenhum IP.
            host = (self.headers.get("Host") or "").split(":")[0] or ARGS.addr
            self.reply(token_answer(host, ARGS.timezone, encrypted), encrypted=False)
            return

        if ".active" in action:
            # Ponto central: aqui a chave local e a chave de sessão vão para a
            # lâmpada — as mesmas que o Valkyris enviou no pareamento.
            key, secret = pairing_values()
            self.reply(activation_answer(key, secret), encrypted=encrypted)
            STATS["activations"] += 1
            log("### ATIVAÇÃO: chave entregue à lâmpada", device, "=", key)
            return

        if ".updatestatus" in action:
            self.reply(None, encrypted=encrypted)
            return

        if ".upgrade" in action or ".device.upgrade" in action:
            self.reply({"auto": 0, "size": 0, "type": 0, "version": "1.0.0"}, encrypted=encrypted)
            return

        if ".log" in action:
            self.reply(True, encrypted=encrypted)
            return

        if ".timer" in action:
            self.reply({"devId": device, "count": 0, "lastFetchTime": 0}, encrypted=encrypted)
            return

        if ".config.get" in action:
            self.reply({"validTime": 1800, "time": int(time.time()), "config": {}}, encrypted=encrypted)
            return

        self.reply(None, encrypted=encrypted)


def serve_psk(port: int, backend_port: int) -> None:
    if sslpsk is None:
        log("aviso: sslpsk ausente; o canal TLS-PSK da porta", port, "não será aberto")
        return
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("0.0.0.0", port))
    listener.listen(8)
    log("TLS-PSK ouvindo na porta", port)
    while True:
        try:
            client, address = listener.accept()
        except OSError:
            return
        threading.Thread(target=psk_bridge, args=(client, address, backend_port), daemon=True).start()


def psk_bridge(client: socket.socket, address: Any, backend_port: int) -> None:
    try:
        secure = sslpsk.wrap_socket(
            client,
            server_side=True,
            ssl_version=ssl.PROTOCOL_TLSv1_2,
            ciphers="PSK-AES128-CBC-SHA256",
            psk=psk_for,
            hint=PSK_HINT,
        )
    except Exception as exc:
        log("TLS-PSK recusou", address[0], ":", repr(exc)[:120])
        client.close()
        return
    log("TLS-PSK conectado de", address[0])
    try:
        backend = socket.create_connection(("127.0.0.1", backend_port), timeout=10)
    except Exception as exc:
        log("sem servidor HTTP interno:", exc)
        secure.close()
        return
    stop = threading.Event()

    def pipe(source: socket.socket, target: socket.socket) -> None:
        try:
            while not stop.is_set():
                data = source.recv(4096)
                if not data:
                    break
                target.sendall(data)
        except Exception:
            pass
        finally:
            stop.set()

    threading.Thread(target=pipe, args=(secure, backend), daemon=True).start()
    pipe(backend, secure)
    for closing in (secure, backend):
        try:
            closing.close()
        except Exception:
            pass


def main() -> None:
    global ARGS
    parser = argparse.ArgumentParser(description="Servidor de ativação local do Valkyris")
    parser.add_argument("--addr", default="", help="endereço usado quando a lâmpada não informa um nome")
    parser.add_argument("--database", default="/data/valkyris.db", help="banco do Valkyris, para ler a chave da janela")
    parser.add_argument("--local-key", default=DEFAULT_TOKEN, help="chave neutra usada apenas quando o banco não pode ser lido; o valor real vem sempre da janela de pareamento")
    parser.add_argument("--sec-key", default=DEFAULT_SEC_KEY, help="chave de sessão do protocolo de ativação")
    parser.add_argument("--timezone", default="-03:00")
    parser.add_argument("--http-port", type=int, default=80)
    parser.add_argument("--psk-ports", default="443,8886")
    ARGS = parser.parse_args()

    server = ThreadingHTTPServer(("0.0.0.0", ARGS.http_port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    log("HTTP ouvindo na porta", ARGS.http_port)
    for port in [int(value) for value in ARGS.psk_ports.split(",") if value]:
        threading.Thread(target=serve_psk, args=(port, ARGS.http_port), daemon=True).start()

    def shutdown(*_: Any) -> None:
        log("encerrando", STATS)
        os._exit(0)

    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)
    log("pronto. Lâmpadas que pedirem ativação recebem a chave do Valkyris.")
    while True:
        time.sleep(5)


if __name__ == "__main__":
    main()
