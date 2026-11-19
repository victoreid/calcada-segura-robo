package br.fiap.calcadasegura.robo

// Kotlin puro (nada de Android): decide QUAL comando mandar para o robô.
// Testado em ControleDeComandosTest.

/** Comando de parar. */
const val PARAR = '0'

/** Tempo máximo entre soltar e apertar de novo o mesmo botão para virar "toque duplo". */
const val DUPLO_TOQUE_MS = 350L

/** Um comando que o robô entende (uma letra) e o nome dele em português. */
data class InfoComando(val codigo: Char, val rotulo: String)

/** Tabela dos comandos do firmware robo_app. */
val COMANDOS: Map<Char, InfoComando> = listOf(
    InfoComando('W', "Frente"),
    InfoComando('S', "Ré"),
    InfoComando('A', "Girar à esquerda"),
    InfoComando('D', "Girar à direita"),
    InfoComando('Q', "Frente à esquerda"),
    InfoComando('E', "Frente à direita"),
    InfoComando('Z', "Ré à esquerda"),
    InfoComando('C', "Ré à direita"),
    InfoComando(PARAR, "Parado"),
).associateBy { it.codigo }

/** Comandos que vão para a frente. O robô bloqueia estes quando vê obstáculo ou buraco. */
val COMANDOS_DE_FRENTE: Set<Char> = setOf('W', 'Q', 'E')

/** Nome em português de um comando (ex.: 'S' vira "Ré"). */
fun rotuloDe(comando: Char): String = COMANDOS[comando]?.rotulo ?: comando.toString()

/** O que aconteceu quando o botão foi apertado. */
enum class ResultadoToque {
    /** Toque normal: o robô anda enquanto o dedo estiver no botão. */
    SEGURANDO,

    /** Toque duplo: o robô continua andando sem segurar (modo contínuo). */
    CONTINUO_ATIVADO,

    /** Botão de parar: tudo foi parado. */
    PAROU,
}

/**
 * Guarda os botões apertados e o modo contínuo, igual ao app do computador:
 *
 * - Segurar um botão: anda. Soltar: para.
 * - Vários dedos: vale o último botão apertado.
 * - Toque duplo (soltar e apertar de novo em até 350 ms): modo contínuo.
 * - Apertar outro botão cancela o modo contínuo.
 * - Parar ('0') limpa tudo.
 *
 * O tempo (agoraMs) vem de fora, para dar para testar sem relógio de verdade.
 * A "chave" identifica o dedo (no app é o número do dedo na tela, o pointerId,
 * igual ao app do computador).
 */
class ControleDeComandos {
    // LinkedHashMap mantém a ordem: o último da lista é o último apertado.
    private val pressionados = LinkedHashMap<Any, Char>()
    private val ultimaSoltura = HashMap<Char, Long>()
    private var continuo: Char? = null

    fun pressionar(chave: Any, comando: Char, agoraMs: Long): ResultadoToque {
        val cmd = comando.uppercaseChar()
        require(COMANDOS.containsKey(cmd)) { "Comando desconhecido: $comando" }

        if (cmd == PARAR) {
            pararTudo()
            return ResultadoToque.PAROU
        }

        val soltouEm = ultimaSoltura[cmd]
        if (soltouEm != null) {
            val intervalo = agoraMs - soltouEm
            if (intervalo in 0..DUPLO_TOQUE_MS) {
                pressionados.clear()
                continuo = cmd
                ultimaSoltura[cmd] = agoraMs
                return ResultadoToque.CONTINUO_ATIVADO
            }
        }

        // Toque normal: cancela o modo contínuo e passa a valer este botão.
        continuo = null
        pressionados.remove(chave)
        pressionados[chave] = cmd
        return ResultadoToque.SEGURANDO
    }

    /** O dedo saiu do botão. Guarda a hora para detectar o toque duplo. */
    fun soltar(chave: Any, agoraMs: Long) {
        val cmd = pressionados.remove(chave) ?: return
        ultimaSoltura[cmd] = agoraMs
    }

    /** O Android cancelou o toque (ex.: a tela foi coberta). Para sem contar como toque. */
    fun cancelar(chave: Any) {
        pressionados.remove(chave)
    }

    /**
     * Para tudo: solta os botões, desliga o modo contínuo e esquece os toques
     * anteriores (assim o próximo toque depois do ■ nunca vira toque duplo).
     */
    fun pararTudo() {
        pressionados.clear()
        ultimaSoltura.clear()
        continuo = null
    }

    /**
     * Chegou aviso de obstáculo ou buraco. Se o robô estava indo para a frente,
     * para tudo e devolve true. Ré e giros continuam liberados (devolve false).
     */
    fun aplicarPerigo(obstaculo: Boolean, buraco: Boolean): Boolean {
        if (!obstaculo && !buraco) return false
        if (comandoAtual() !in COMANDOS_DE_FRENTE) return false
        pararTudo()
        return true
    }

    /** O comando que deve ser enviado agora. */
    fun comandoAtual(): Char = pressionados.values.lastOrNull() ?: continuo ?: PARAR

    /** O comando do modo contínuo, ou null se ele estiver desligado. */
    fun modoContinuo(): Char? = continuo

    fun estaPressionado(chave: Any): Boolean = pressionados.containsKey(chave)
}
