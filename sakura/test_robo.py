# -*- coding: utf-8 -*-
"""
Testes da ponte Sakura <-> robo (rodam sem microfone, sem robo e sem internet):

    python -m unittest test_robo -v
"""

import json
import tempfile
import threading
import unittest
import urllib.request

import robo


def leitura(ev="ok", inc=0.0, vib=0.03, df=400, m="A"):
    return {"t": 1, "m": m, "df": df, "dc": 0.0, "inc": inc, "vib": vib, "ev": ev}


class Relogio:
    """Relogio falso: o teste decide quanto tempo passou."""

    def __init__(self):
        self.agora = 100.0

    def __call__(self):
        return self.agora


class TestClassificar(unittest.TestCase):

    def test_piso_liso_segue(self):
        self.assertEqual(robo.classificar(leitura()), "ok")

    def test_perigos_param(self):
        for ev in ("buraco", "obstaculo", "falha_mpu", "falha_sonar", "bt_perdido"):
            self.assertEqual(robo.classificar(leitura(ev=ev)), ev)
            self.assertEqual(robo.MOTIVOS[ev][0], "P")

    def test_rampa_acima_da_nbr_reduz(self):
        self.assertEqual(robo.classificar(leitura(inc=8.0)), "ok")
        self.assertEqual(robo.classificar(leitura(inc=9.0)), "rampa")
        self.assertEqual(robo.classificar(leitura(inc=-12.0)), "rampa")  # descida

    def test_trepidacao(self):
        self.assertEqual(robo.classificar(leitura(vib=0.12)), "ok")
        self.assertEqual(robo.classificar(leitura(vib=0.20)), "irregular")
        self.assertEqual(robo.classificar(leitura(vib=0.45)), "trepidacao")

    def test_perigo_vence_rampa(self):
        self.assertEqual(robo.classificar(leitura(ev="buraco", inc=15, vib=0.5)), "buraco")

    def test_valores_ruins_nao_quebram(self):
        self.assertEqual(robo.classificar({"inc": "abc", "vib": None}), "ok")


class TestInspecao(unittest.TestCase):

    def setUp(self):
        self.relogio = Relogio()
        self.falas = []
        self.pasta = tempfile.mkdtemp()
        self.insp = robo.Inspecao(falar=self.falas.append, pasta=self.pasta,
                                  relogio=self.relogio)

    def test_decisoes(self):
        self.assertEqual(self.insp.receber(leitura())["decisao"], "G")
        self.assertEqual(self.insp.receber(leitura(vib=0.2))["decisao"], "R")
        self.assertEqual(self.insp.receber(leitura(ev="buraco"))["decisao"], "P")

    def test_segura_parado_depois_do_buraco(self):
        self.insp.receber(leitura(ev="buraco"))
        self.relogio.agora += 0.5
        r = self.insp.receber(leitura())
        self.assertEqual(r["decisao"], "P")          # ainda confirmando
        self.relogio.agora += robo.SEGURA_PARADA_S
        self.assertEqual(self.insp.receber(leitura())["decisao"], "G")

    def test_fala_so_quando_muda_e_sem_repetir(self):
        r1 = self.insp.receber(leitura(ev="buraco"))
        r2 = self.insp.receber(leitura(ev="buraco"))
        self.assertIn("buraco", r1["fala"])
        self.assertEqual(r2["fala"], "")             # mesmo buraco: fala 1 vez
        self.relogio.agora += 2
        self.insp.receber(leitura())
        self.relogio.agora += 1
        self.assertEqual(self.insp.receber(leitura(ev="buraco"))["fala"], "")  # < 6 s

    def test_relatorio_conta_ocorrencias(self):
        self.insp.receber(leitura(ev="buraco"))
        self.relogio.agora += 5
        self.insp.receber(leitura(inc=11.0))
        self.relogio.agora += 1
        self.insp.receber(leitura())
        texto = self.insp.relatorio()
        self.assertIn("1 buraco ou degrau", texto)
        self.assertIn("1 rampa fora da norma", texto)
        self.assertIn("11,0 por cento", texto)

    def test_registra_ocorrencias_em_arquivo(self):
        import os
        self.insp.receber(leitura(ev="obstaculo", df=12))
        arquivos = os.listdir(self.pasta)
        self.assertEqual(len(arquivos), 1)
        with open(os.path.join(self.pasta, arquivos[0]), encoding="utf-8") as f:
            linha = json.loads(f.readline())
        self.assertEqual(linha["ocorrencia"], "obstaculo")
        self.assertEqual(linha["df"], 12)

    def test_comandos_vao_na_proxima_resposta(self):
        self.insp.pedir("I")
        self.insp.pedir("B")
        self.assertEqual(self.insp.receber(leitura())["comandos"], "IB")
        self.assertEqual(self.insp.receber(leitura())["comandos"], "")

    def test_parar_cancela_a_fila(self):
        self.insp.pedir("I")
        self.insp.pedir("0")
        self.assertEqual(self.insp.receber(leitura())["comandos"], "0")

    def test_comando_velho_e_descartado(self):
        self.insp.pedir("I")
        self.relogio.agora += robo.COMANDO_VALIDADE_S + 1
        self.assertEqual(self.insp.receber(leitura())["comandos"], "")

    def test_app_conectado(self):
        self.assertFalse(self.insp.app_conectado())
        self.insp.receber(leitura())
        self.assertTrue(self.insp.app_conectado())
        self.relogio.agora += robo.APP_OFFLINE_S + 0.1
        self.assertFalse(self.insp.app_conectado())


class TestComandoDeVoz(unittest.TestCase):

    def setUp(self):
        self.insp = robo.Inspecao(pasta=None)

    def test_ignora_o_que_nao_e_do_robo(self):
        self.assertIsNone(robo.comando_de_voz("que horas sao", self.insp))

    def test_sem_app_nao_enfileira(self):
        r = robo.comando_de_voz("robo modo automatico", self.insp)
        self.assertIn("nao esta conectado", r)
        self.insp.receber(leitura())
        self.assertEqual(self.insp.receber(leitura())["comandos"], "")

    def test_comandos(self):
        casos = {"robo modo automatico": "I", "robo parar": "0",
                 "desligar o robo": "0", "robo modo manual": "M",
                 "robo buzina": "B", "recalibrar o robo": "K",
                 "robo inspecionar a calcada": "I"}
        for frase, letra in casos.items():
            self.insp.receber(leitura())
            robo.comando_de_voz(frase, self.insp)
            self.assertEqual(self.insp.receber(leitura())["comandos"], letra, frase)

    def test_relatorio_e_nova_inspecao(self):
        self.insp.receber(leitura(ev="buraco"))
        self.assertIn("buraco", robo.comando_de_voz("como esta a calcada", self.insp))
        robo.comando_de_voz("robo nova inspecao", self.insp)
        self.assertEqual(self.insp.leituras, 0)


class TestServidorHttp(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        robo.inspecao = robo.Inspecao(pasta=None)
        cls.srv = robo.ThreadingHTTPServer(("127.0.0.1", 0), robo._Ponte)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.srv.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def post(self, corpo):
        req = urllib.request.Request(self.base + "/robo/telemetria", data=corpo,
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=3) as r:
            return json.loads(r.read().decode("utf-8"))

    def test_status(self):
        with urllib.request.urlopen(self.base + "/status", timeout=3) as r:
            self.assertTrue(json.loads(r.read())["ok"])

    def test_telemetria_do_firmware(self):
        linha = b'{"t":12345,"m":"A","df":32,"dc":0.4,"inc":11.2,"vib":0.42,"ev":"rampa"}'
        r = self.post(linha)
        self.assertEqual(r["decisao"], "R")
        self.assertIn("11 %", r["motivo"])
        self.assertNotIn('"', r["motivo"])

    def test_json_invalido_da_400(self):
        with self.assertRaises(urllib.error.HTTPError) as erro:
            self.post(b"{quebrado")
        self.assertEqual(erro.exception.code, 400)


if __name__ == "__main__":
    unittest.main()
