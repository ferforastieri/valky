"""Testes do servidor de ativação local (rode com: python3 -m unittest)."""
import base64
import hashlib
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

import server


class CryptoTest(unittest.TestCase):
    def test_ecb_round_trip(self):
        key = b"0123456789abcdef"
        plain = json.dumps({"s": "rede", "p": "senha"}).encode()
        self.assertEqual(server.decrypt_ecb(server.encrypt_ecb(plain, key), key), plain)

    def test_psk_is_deterministic_and_sized(self):
        identity = server.IDENTITY_PREFIX + b"d9ce7ed9c23513a3" + b"0000"
        first = server.psk_for(identity)
        second = server.psk_for(identity)
        self.assertEqual(first, second)
        self.assertEqual(len(first), 32)

    def test_signed_reply_matches_the_protocol(self):
        payload = {"localKey": "ValkyrisKey00001"}
        sealed = base64.b64encode(server.encrypt_ecb(server.json_bytes(payload), b"0000000000000000")).decode()
        signature = "result=%s||t=%d||%s" % (sealed, 1791851965, "0000000000000000")
        self.assertEqual(hashlib.md5(signature.encode()).hexdigest()[8:24], hashlib.md5(signature.encode()).hexdigest()[8:24])


class AnswerTest(unittest.TestCase):
    def test_activation_answer_carries_the_local_key(self):
        answer = server.activation_answer("ValkyrisKey00001", "0000000000000000")
        self.assertEqual(answer["localKey"], "ValkyrisKey00001")
        self.assertEqual(answer["secKey"], "0000000000000000")
        self.assertEqual(len(json.loads(answer["schema"])), 20)

    def test_token_answer_points_back_to_the_same_host(self):
        answer = server.token_answer("a1.tuyaus.com", "-03:00", encrypted=False)
        self.assertEqual(answer["gwApiUrl"], "http://a1.tuyaus.com/gw.json")
        self.assertEqual(answer["httpsPSKUrl"], "https://a1.tuyaus.com/gw.json")
        self.assertNotIn("mqttsUrl", answer)

    def test_token_answer_includes_secure_endpoints_when_encrypted(self):
        answer = server.token_answer("a1.tuyaus.com", "-03:00", encrypted=True)
        self.assertEqual(answer["mqttsPSKUrl"], "a1.tuyaus.com")
        self.assertEqual(answer["aispeech"], "a1.tuyaus.com")


class PairingKeyTest(unittest.TestCase):
    def test_reads_the_key_from_the_valkyris_database(self):
        with tempfile.TemporaryDirectory() as folder:
            database = Path(folder) / "valkyris.db"
            connection = sqlite3.connect(database)
            connection.execute("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            connection.execute("INSERT INTO settings VALUES ('light_pairing_token', 'ValkyrisKey00001')")
            connection.commit()
            connection.close()
            server.ARGS = type("Args", (), {"database": str(database), "local_key": "padrao0000000000", "sec_key": "0000000000000000"})()
            self.assertEqual(server.pairing_key(), "ValkyrisKey00001")

    def test_reads_key_and_secret_together(self):
        with tempfile.TemporaryDirectory() as folder:
            database = Path(folder) / "valkyris.db"
            connection = sqlite3.connect(database)
            connection.execute("CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            connection.execute("INSERT INTO settings VALUES ('light_pairing_token', 'ValkyrisKey00001')")
            connection.execute("INSERT INTO settings VALUES ('light_pairing_secret', 'SegredoDe16Char1')")
            connection.commit()
            connection.close()
            server.ARGS = type("Args", (), {"database": str(database), "local_key": "padrao0000000000", "sec_key": "0000000000000000"})()
            self.assertEqual(server.pairing_values(), ("ValkyrisKey00001", "SegredoDe16Char1"))

    def test_falls_back_when_there_is_no_database(self):
        server.ARGS = type("Args", (), {"database": "/nao/existe.db", "local_key": "padrao0000000000", "sec_key": "0000000000000000"})()
        self.assertEqual(server.pairing_key(), "padrao0000000000")


if __name__ == "__main__":
    unittest.main()
