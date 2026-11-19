package br.fiap.calcadasegura.robo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Testes da conversa com a Sakura (rodam no computador, sem celular e sem rede). */
class SakuraTest {

    @Test
    fun interpretaRespostaDaPonte() {
        val json = "{\"decisao\": \"R\", \"motivo\": \"Rampa de 11 % (limite 8,33 %)\", " +
            "\"fala\": \"Rampa com 11 por cento de inclinacao.\", \"comandos\": \"IB\"}"
        assertEquals(
            RespostaSakura('R', "Rampa de 11 % (limite 8,33 %)", "Rampa com 11 por cento de inclinacao.", "IB"),
            interpretarRespostaSakura(json),
        )
    }

    @Test
    fun respostaSemFalaNemComandos() {
        val r = interpretarRespostaSakura("{\"decisao\":\"G\",\"motivo\":\"Caminho livre\",\"fala\":\"\",\"comandos\":\"\"}")
        assertEquals(RespostaSakura('G', "Caminho livre", "", ""), r)
    }

    @Test
    fun acentosEAspasEscapadas() {
        val r = interpretarRespostaSakura("{\"decisao\":\"P\",\"motivo\":\"Obstáculo \\\"grande\\\"\"}")
        assertEquals("Obstáculo \"grande\"", r?.motivo)
        assertEquals("", r?.comandos)
    }

    @Test
    fun comandoDeMovimentoNaoPassa() {
        // Por segurança, a Sakura nunca move o robô: W, S, A, D... são descartados.
        val r = interpretarRespostaSakura("{\"decisao\":\"G\",\"comandos\":\"WiXb0\"}")
        assertEquals("IB0", r?.comandos)
    }

    @Test
    fun decisaoInvalidaDevolveNull() {
        assertNull(interpretarRespostaSakura("{\"decisao\":\"W\"}"))
        assertNull(interpretarRespostaSakura("{\"erro\":\"caminho desconhecido\"}"))
        assertNull(interpretarRespostaSakura("<html>404</html>"))
        assertNull(interpretarRespostaSakura(""))
    }

    @Test
    fun normalizaEndereco() {
        assertEquals("http://192.168.0.10:8765", normalizarEnderecoSakura("192.168.0.10"))
        assertEquals("http://192.168.0.10:9000", normalizarEnderecoSakura(" 192.168.0.10:9000 "))
        assertEquals("http://192.168.0.10:8765", normalizarEnderecoSakura("http://192.168.0.10:8765/"))
        assertEquals("http://meu-pc.local:8765", normalizarEnderecoSakura("meu-pc.local"))
    }

    @Test
    fun enderecoInvalido() {
        assertNull(normalizarEnderecoSakura(""))
        assertNull(normalizarEnderecoSakura("192.168.0.10:abc"))
        assertNull(normalizarEnderecoSakura("192.168.0.10:70000"))
        assertNull(normalizarEnderecoSakura("192.168.0.10/robo"))
        assertNull(normalizarEnderecoSakura("192 168 0 10"))
        assertNull(normalizarEnderecoSakura("https://x:1:2"))
    }

    @Test
    fun mostraEnderecoSemHttp() {
        assertEquals("192.168.0.10:8765", enderecoParaMostrar("http://192.168.0.10:8765"))
    }
}
