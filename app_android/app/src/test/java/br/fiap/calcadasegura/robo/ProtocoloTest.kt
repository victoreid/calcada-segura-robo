package br.fiap.calcadasegura.robo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Testes do LeitorDeLinhas e do interpretarLinha (rodam no computador, sem celular). */
class ProtocoloTest {

    // ---------------- LeitorDeLinhas ----------------

    @Test
    fun juntaLinhaQueChegouEmPedacos() {
        val leitor = LeitorDeLinhas()
        assertEquals(emptyList<String>(), leitor.feed("DI"))
        assertEquals(emptyList<String>(), leitor.feed("ST:3"))
        assertEquals(listOf("DIST:32"), leitor.feed("2\r\n"))
    }

    @Test
    fun aceitaCrLfELfEIgnoraLinhasVazias() {
        val leitor = LeitorDeLinhas()
        val linhas = leitor.feed("DIST:10\r\nCOLLISION:0\n\r\n\nHOLE:1\r\n")
        assertEquals(listOf("DIST:10", "COLLISION:0", "HOLE:1"), linhas)
    }

    @Test
    fun crLfQuebradoEntreDoisPedacos() {
        val leitor = LeitorDeLinhas()
        assertEquals(listOf("DIST:5"), leitor.feed("DIST:5\r"))
        assertEquals(listOf("HOLE:0"), leitor.feed("\nHOLE:0\r\n"))
    }

    @Test
    fun guardaALinhaIncompletaParaODepois() {
        val leitor = LeitorDeLinhas()
        assertEquals(listOf("HOLE:0"), leitor.feed("HOLE:0\r\nDIST:1"))
        assertEquals(listOf("DIST:15"), leitor.feed("5\r\n"))
    }

    @Test
    fun tiraEspacosDasPontas() {
        val leitor = LeitorDeLinhas()
        assertEquals(listOf("DIST:9"), leitor.feed("   DIST:9  \r\n"))
    }

    @Test
    fun descartaLinhaGrandeDemais() {
        val leitor = LeitorDeLinhas()
        assertEquals(emptyList<String>(), leitor.feed("x".repeat(300)))
        assertEquals(emptyList<String>(), leitor.feed("y".repeat(100)))
        // O resto da linha comprida some; a próxima linha volta ao normal.
        assertEquals(listOf("DIST:7"), leitor.feed("\nDIST:7\n"))
    }

    @Test
    fun linhaNoLimiteAindaVale() {
        val leitor = LeitorDeLinhas()
        val linha = "a".repeat(LIMITE_DA_LINHA)
        assertEquals(listOf(linha), leitor.feed(linha + "\n"))
    }

    @Test
    fun ignoraCaracteresDeControle() {
        val leitor = LeitorDeLinhas()
        assertEquals(listOf("DIST:12"), leitor.feed("\u0000DIST:\u000112\r\n"))
    }

    @Test
    fun limparEsqueceOPedaco() {
        val leitor = LeitorDeLinhas()
        leitor.feed("DIST:9")
        leitor.limpar()
        assertEquals(listOf("HOLE:1"), leitor.feed("HOLE:1\n"))
    }

    // ---------------- interpretarLinha ----------------

    @Test
    fun interpretaDistancia() {
        assertEquals(EventoRobo.Distancia(32), interpretarLinha("DIST:32"))
        assertEquals(EventoRobo.Distancia(0), interpretarLinha("DIST:0"))
    }

    @Test
    fun distancia400QuerDizerLivre() {
        assertEquals(EventoRobo.Distancia(400), interpretarLinha("DIST:400"))
        // Número maior que 400 (ou gigante) vira 400.
        assertEquals(EventoRobo.Distancia(400), interpretarLinha("DIST:999"))
        assertEquals(EventoRobo.Distancia(400), interpretarLinha("DIST:99999999999999"))
    }

    @Test
    fun interpretaColisao() {
        assertEquals(EventoRobo.Colisao(true), interpretarLinha("COLLISION:1"))
        assertEquals(EventoRobo.Colisao(false), interpretarLinha("COLLISION:0"))
    }

    @Test
    fun interpretaBuraco() {
        assertEquals(EventoRobo.Buraco(true), interpretarLinha("HOLE:1"))
        assertEquals(EventoRobo.Buraco(false), interpretarLinha("HOLE:0"))
    }

    @Test
    fun toleraEspacos() {
        assertEquals(EventoRobo.Distancia(25), interpretarLinha("  DIST: 25 "))
        assertEquals(EventoRobo.Buraco(true), interpretarLinha("HOLE: 1"))
    }

    @Test
    fun linhaEmBrancoDevolveNull() {
        assertNull(interpretarLinha(""))
        assertNull(interpretarLinha("    "))
    }

    @Test
    fun lixoViraOutra() {
        assertEquals(EventoRobo.Outra("%%##"), interpretarLinha("%%##"))
        assertEquals(EventoRobo.Outra("DIST:abc"), interpretarLinha("DIST:abc"))
        assertEquals(EventoRobo.Outra("COLLISION:7"), interpretarLinha("COLLISION:7"))
        val json = "{\"tipo\":\"buraco\",\"confianca\":0.87}"
        assertEquals(EventoRobo.Outra(json), interpretarLinha(json))
    }
}
