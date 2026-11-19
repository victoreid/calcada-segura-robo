package br.fiap.calcadasegura.robo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testes das regras dos botões (iguais às do app do computador). */
class ControleDeComandosTest {

    private val dedo1 = "dedo 1"
    private val dedo2 = "dedo 2"
    private val dedo3 = "dedo 3"

    @Test
    fun comecaParado() {
        val c = ControleDeComandos()
        assertEquals(PARAR, c.comandoAtual())
        assertNull(c.modoContinuo())
    }

    @Test
    fun segurarAndaESoltarPara() {
        val c = ControleDeComandos()
        assertEquals(ResultadoToque.SEGURANDO, c.pressionar(dedo1, 'W', 1000))
        assertEquals('W', c.comandoAtual())
        assertTrue(c.estaPressionado(dedo1))
        c.soltar(dedo1, 1500)
        assertEquals(PARAR, c.comandoAtual())
        assertFalse(c.estaPressionado(dedo1))
    }

    @Test
    fun aceitaLetraMinuscula() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 's', 0)
        assertEquals('S', c.comandoAtual())
    }

    @Test
    fun ultimoBotaoApertadoVence() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'W', 0)
        c.pressionar(dedo2, 'D', 100)
        assertEquals('D', c.comandoAtual())
        c.soltar(dedo2, 200)
        assertEquals('W', c.comandoAtual())
        c.soltar(dedo1, 300)
        assertEquals(PARAR, c.comandoAtual())
    }

    @Test
    fun doisDedosNoMesmoBotaoSoParaQuandoOsDoisSaem() {
        // No app, a chave é o número do dedo na tela (pointerId).
        val c = ControleDeComandos()
        c.pressionar(0, 'S', 0)
        c.pressionar(1, 'S', 50)
        c.soltar(0, 100)
        assertEquals('S', c.comandoAtual())
        c.soltar(1, 200)
        assertEquals(PARAR, c.comandoAtual())
    }

    @Test
    fun soltarDedoQueNaoApertouBotaoNaoMudaNada() {
        val c = ControleDeComandos()
        c.pressionar(0, 'W', 0)
        c.soltar(1, 100) // o dedo 1 encostou fora dos botões
        assertEquals('W', c.comandoAtual())
        c.soltar(0, 200)
        assertEquals(PARAR, c.comandoAtual())
    }

    @Test
    fun cancelarTiraOBotao() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'A', 0)
        c.cancelar(dedo1)
        assertEquals(PARAR, c.comandoAtual())
    }

    @Test
    fun toqueDuploAtivaModoContinuo() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'W', 1000)
        c.soltar(dedo1, 1100)
        assertEquals(ResultadoToque.CONTINUO_ATIVADO, c.pressionar(dedo1, 'W', 1300))
        c.soltar(dedo1, 1400)
        // Soltou o dedo, mas continua andando.
        assertEquals('W', c.comandoAtual())
        assertEquals('W', c.modoContinuo())
    }

    @Test
    fun toqueDuploNoLimiteDe350ms() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'S', 0)
        c.soltar(dedo1, 100)
        assertEquals(ResultadoToque.CONTINUO_ATIVADO, c.pressionar(dedo1, 'S', 100 + DUPLO_TOQUE_MS))
    }

    @Test
    fun toqueLentoNaoAtivaModoContinuo() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'W', 1000)
        c.soltar(dedo1, 1100)
        assertEquals(ResultadoToque.SEGURANDO, c.pressionar(dedo1, 'W', 1500))
        assertNull(c.modoContinuo())
        c.soltar(dedo1, 1600)
        assertEquals(PARAR, c.comandoAtual())
    }

    @Test
    fun toqueDuploEmBotaoDiferenteNaoConta() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'W', 1000)
        c.soltar(dedo1, 1100)
        assertEquals(ResultadoToque.SEGURANDO, c.pressionar(dedo1, 'S', 1200))
        assertNull(c.modoContinuo())
    }

    @Test
    fun outroBotaoCancelaModoContinuo() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'W', 1000)
        c.soltar(dedo1, 1100)
        c.pressionar(dedo1, 'W', 1200)
        c.soltar(dedo1, 1300)
        assertEquals('W', c.modoContinuo())

        c.pressionar(dedo2, 'S', 3000)
        assertNull(c.modoContinuo())
        assertEquals('S', c.comandoAtual())
        c.soltar(dedo2, 3500)
        assertEquals(PARAR, c.comandoAtual())
    }

    @Test
    fun pararLimpaTudo() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'W', 0)
        c.pressionar(dedo2, 'D', 10)
        assertEquals(ResultadoToque.PAROU, c.pressionar(dedo3, PARAR, 20))
        assertEquals(PARAR, c.comandoAtual())
        // Os dedos saem depois: nada volta a andar.
        c.soltar(dedo1, 30)
        c.soltar(dedo2, 40)
        c.soltar(dedo3, 50)
        assertEquals(PARAR, c.comandoAtual())
    }

    @Test
    fun pararDesligaModoContinuo() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'Q', 0)
        c.soltar(dedo1, 100)
        c.pressionar(dedo1, 'Q', 200)
        assertEquals('Q', c.modoContinuo())
        c.pressionar(dedo2, PARAR, 1000)
        assertNull(c.modoContinuo())
        assertEquals(PARAR, c.comandoAtual())
    }

    @Test
    fun pararZeraOToqueDuplo() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'W', 0)
        c.soltar(dedo1, 100)
        assertEquals(ResultadoToque.PAROU, c.pressionar(dedo2, PARAR, 200))
        c.soltar(dedo2, 250)
        // Só 300 ms depois de soltar o W, mas o ■ veio no meio: é toque normal.
        assertEquals(ResultadoToque.SEGURANDO, c.pressionar(dedo1, 'W', 400))
        assertNull(c.modoContinuo())
        c.soltar(dedo1, 500)
        assertEquals(PARAR, c.comandoAtual())
    }

    @Test
    fun perigoZeraOToqueDuplo() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'W', 0)
        c.soltar(dedo1, 100)
        c.pressionar(dedo1, 'W', 200) // toque duplo: modo contínuo
        assertTrue(c.aplicarPerigo(obstaculo = true, buraco = false))
        // Depois da parada por perigo, um novo toque no W não religa o modo contínuo.
        assertEquals(ResultadoToque.SEGURANDO, c.pressionar(dedo1, 'W', 300))
        assertNull(c.modoContinuo())
    }

    @Test
    fun soltarDepoisDeParadoNaoViraToqueDuplo() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'W', 0)
        c.pararTudo()
        c.soltar(dedo1, 100)
        // O W não estava mais valendo, então este toque é normal.
        assertEquals(ResultadoToque.SEGURANDO, c.pressionar(dedo1, 'W', 200))
    }

    @Test
    fun perigoParaComandoDeFrente() {
        for (frente in listOf('W', 'Q', 'E')) {
            val c = ControleDeComandos()
            c.pressionar(dedo1, frente, 0)
            assertTrue(c.aplicarPerigo(obstaculo = true, buraco = false))
            assertEquals(PARAR, c.comandoAtual())
        }
    }

    @Test
    fun buracoParaModoContinuoDeFrente() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'E', 0)
        c.soltar(dedo1, 100)
        c.pressionar(dedo1, 'E', 200)
        assertEquals('E', c.modoContinuo())
        assertTrue(c.aplicarPerigo(obstaculo = false, buraco = true))
        assertNull(c.modoContinuo())
        assertEquals(PARAR, c.comandoAtual())
    }

    @Test
    fun perigoDuranteReNaoFazNada() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'S', 0)
        assertFalse(c.aplicarPerigo(obstaculo = true, buraco = true))
        assertEquals('S', c.comandoAtual())
    }

    @Test
    fun perigoDuranteGiroNaoFazNada() {
        for (outro in listOf('A', 'D', 'Z', 'C')) {
            val c = ControleDeComandos()
            c.pressionar(dedo1, outro, 0)
            assertFalse(c.aplicarPerigo(obstaculo = true, buraco = false))
            assertEquals(outro, c.comandoAtual())
        }
    }

    @Test
    fun semPerigoNaoFazNada() {
        val c = ControleDeComandos()
        c.pressionar(dedo1, 'W', 0)
        assertFalse(c.aplicarPerigo(obstaculo = false, buraco = false))
        assertEquals('W', c.comandoAtual())
    }

    @Test
    fun tabelaTemOsNoveComandosComAcentos() {
        assertEquals(9, COMANDOS.size)
        assertEquals("Ré", rotuloDe('S'))
        assertEquals("Girar à esquerda", rotuloDe('A'))
        assertEquals("Parado", rotuloDe(PARAR))
    }
}
