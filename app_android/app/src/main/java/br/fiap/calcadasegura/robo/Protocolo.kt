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

    /**
     * Linha JSON a cada 250 ms com os números do MPU-6050 e dos sonares:
     * {"t":12345,"m":"A","df":32,"dc":0.4,"inc":11.2,"vib":0.42,"ev":"rampa"}
     * modo = 'M' (manual) ou 'A' (auto); df = cm à frente; dc = desvio do chão em cm;
     * inc = inclinação em %; vib = trepidação em g (RMS); ev = evento mais importante.
     */
    data class Telemetria(
        val modo: Char,
        val df: Int,
        val dc: Float,
        val inc: Float,
        val vib: Float,
        val ev: String,
    ) : EventoRobo() {
        /** O firmware avisa "falha_mpu" quando o MPU-6050 parou de responder (vib e inc viram 0). */
        val mpuFalhou: Boolean get() = ev == "falha_mpu"
    }

    /** Qualquer outra linha (por exemplo, JSON de outro programa do robô). */
    data class Outra(val texto: String) : EventoRobo()
}

/** Trepidação (g RMS) acima disso = piso irregular. Igual ao VIB_LIM do firmware. */
const val TREPIDACAO_IRREGULAR = 0.12f

/** Trepidação (g RMS) acima disso = piso muito ruim (pedra solta, raiz, piso quebrado). */
const val TREPIDACAO_FORTE = 0.30f

/** Escala da barra de trepidação na tela: 0,5 g ou mais = barra cheia. */
const val TREPIDACAO_ESCALA = 0.5f

/** Inclinação máxima de rampa pela NBR 9050, em %. */
const val RAMPA_LIMITE = 8.33f

enum class NivelTrepidacao { LISO, IRREGULAR, FORTE }

fun nivelDaTrepidacao(vib: Float): NivelTrepidacao = when {
    vib > TREPIDACAO_FORTE -> NivelTrepidacao.FORTE
    vib > TREPIDACAO_IRREGULAR -> NivelTrepidacao.IRREGULAR
    else -> NivelTrepidacao.LISO
}

/** Trepidação em % da barra (0 a 100). */
fun barraDaTrepidacao(vib: Float): Int =
    ((vib / TREPIDACAO_ESCALA) * 100).toInt().coerceIn(0, 100)

private val REGEX_DISTANCIA = Regex("""DIST:\s*(\d+)""")
private val REGEX_COLISAO = Regex("""COLLISION:\s*([01])""")
private val REGEX_BURACO = Regex("""HOLE:\s*([01])""")

// Um campo do JSON do robô: "chave":valor (número ou texto entre aspas).
// Sem biblioteca de JSON: o org.json do Android não funciona nos testes do computador.
private fun campoJson(json: String, chave: String): String? =
    Regex("\"" + chave + "\"\\s*:\\s*(\"[^\"]*\"|-?[0-9.]+)").find(json)
        ?.groupValues?.get(1)?.trim('"')

/** Lê a linha JSON de telemetria. Precisa ter pelo menos "vib" e "inc" válidos. */
private fun interpretarTelemetria(texto: String): EventoRobo.Telemetria? {
    if (!texto.startsWith("{") || !texto.endsWith("}")) return null
    val vib = campoJson(texto, "vib")?.toFloatOrNull() ?: return null
    val inc = campoJson(texto, "inc")?.toFloatOrNull() ?: return null
    if (vib.isNaN() || inc.isNaN()) return null
    return EventoRobo.Telemetria(
        modo = campoJson(texto, "m")?.firstOrNull()?.uppercaseChar() ?: 'M',
        df = (campoJson(texto, "df")?.toFloatOrNull()?.toInt() ?: DISTANCIA_MAXIMA)
            .coerceIn(0, DISTANCIA_MAXIMA),
        dc = campoJson(texto, "dc")?.toFloatOrNull() ?: 0f,
        inc = inc.coerceIn(-999f, 999f),
        vib = vib.coerceIn(0f, 9.99f),
        ev = campoJson(texto, "ev") ?: "ok",
    )
}

/**
 * Traduz uma linha do robô. Devolve null para linha em branco.
 * É tolerante: aceita espaços e lixo em volta, como o app do computador.
 */
fun interpretarLinha(linha: String): EventoRobo? {
    val texto = linha.trim()
    if (texto.isEmpty()) return null

    interpretarTelemetria(texto)?.let { return it }
    REGEX_BURACO.find(texto)?.let { return EventoRobo.Buraco(it.groupValues[1] == "1") }
    REGEX_COLISAO.find(texto)?.let { return EventoRobo.Colisao(it.groupValues[1] == "1") }
    REGEX_DISTANCIA.find(texto)?.let {
        // Número grande demais para caber em Int: trata como "nada à frente".
        val cm = it.groupValues[1].toIntOrNull() ?: DISTANCIA_MAXIMA
        return EventoRobo.Distancia(cm.coerceIn(0, DISTANCIA_MAXIMA))
    }
    return EventoRobo.Outra(texto)
}
