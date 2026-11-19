package br.fiap.calcadasegura.robo

// Tudo neste arquivo é Kotlin puro (nada de Android).
// Assim dá para testar no computador, sem celular (veja ProtocoloTest).

/** Tamanho máximo de uma linha. Passou disso, é lixo e a gente joga fora. */
const val LIMITE_DA_LINHA = 256

/** O robô manda 400 quando o sonar da frente não vê nada. */
const val DISTANCIA_MAXIMA = 400

/**
 * Junta os pedaços de texto que chegam do robô e devolve as linhas completas.
 *
 * O Bluetooth entrega o texto picado, por exemplo "DI", depois "ST:3", depois "2\r\n".
 * Esta classe guarda o pedaço incompleto e só devolve a linha quando ela termina
 * (com \n ou \r\n). Linhas vazias são ignoradas.
 */
class LeitorDeLinhas {
    private val pedaco = StringBuilder()

    // true = a linha atual ficou grande demais; ignora tudo até o próximo fim de linha.
    private var descartando = false

    fun feed(texto: String): List<String> {
        val prontas = ArrayList<String>()
        for (c in texto) {
            when {
                c == '\n' || c == '\r' -> {
                    if (!descartando) {
                        val linha = pedaco.toString().trim()
                        if (linha.isNotEmpty()) prontas.add(linha)
                    }
                    pedaco.setLength(0)
                    descartando = false
                }
                descartando -> Unit
                pedaco.length >= LIMITE_DA_LINHA -> {
                    // Linha sem fim e comprida demais: é lixo. Joga fora para não gastar memória.
                    pedaco.setLength(0)
                    descartando = true
                }
                c >= ' ' || c == '\t' -> pedaco.append(c)
                // Outros caracteres de controle (ruído do Bluetooth) são ignorados.
            }
        }
        return prontas
    }

    /** Esquece o pedaço guardado (usado ao reconectar). */
    fun limpar() {
        pedaco.setLength(0)
        descartando = false
    }
}

/** O que uma linha do robô quer dizer. */
sealed class EventoRobo {
    /** "DIST:32": distância à frente, em cm (400 = nada à frente). */
    data class Distancia(val cm: Int) : EventoRobo()

    /** "COLLISION:1": obstáculo à frente (ou degrau alto). "COLLISION:0": caminho livre. */
    data class Colisao(val ativo: Boolean) : EventoRobo()

    /** "HOLE:1": buraco ou degrau para baixo. "HOLE:0": chão normal. */
    data class Buraco(val ativo: Boolean) : EventoRobo()

    /** Qualquer outra linha (por exemplo, JSON de outro programa do robô). */
    data class Outra(val texto: String) : EventoRobo()
}

private val REGEX_DISTANCIA = Regex("""DIST:\s*(\d+)""")
private val REGEX_COLISAO = Regex("""COLLISION:\s*([01])""")
private val REGEX_BURACO = Regex("""HOLE:\s*([01])""")

/**
 * Traduz uma linha do robô. Devolve null para linha em branco.
 * É tolerante: aceita espaços e lixo em volta, como o app do computador.
 */
fun interpretarLinha(linha: String): EventoRobo? {
    val texto = linha.trim()
    if (texto.isEmpty()) return null

    REGEX_BURACO.find(texto)?.let { return EventoRobo.Buraco(it.groupValues[1] == "1") }
    REGEX_COLISAO.find(texto)?.let { return EventoRobo.Colisao(it.groupValues[1] == "1") }
    REGEX_DISTANCIA.find(texto)?.let {
        // Número grande demais para caber em Int: trata como "nada à frente".
        val cm = it.groupValues[1].toIntOrNull() ?: DISTANCIA_MAXIMA
        return EventoRobo.Distancia(cm.coerceIn(0, DISTANCIA_MAXIMA))
    }
    return EventoRobo.Outra(texto)
}
