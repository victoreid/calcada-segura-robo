package br.fiap.calcadasegura.robo

// Kotlin puro (nada de Android): dá para testar no computador (veja SakuraTest).
//
// A Sakura é a assistente de voz do grupo (pasta sakura/ do repositório). Ela roda
// no PC e abre uma "ponte" HTTP na rede local (robo.py). O app manda para ela cada
// linha JSON de telemetria do robô e recebe:
//   {"decisao":"R","motivo":"Rampa de 11 %","fala":"...","comandos":"IB"}

/** Porta padrão da ponte da Sakura (PORTA_ROBO no sakura/config.py). */
const val PORTA_SAKURA = 8765

/** Decisões da IA que o firmware entende no modo AUTO. */
const val IA_SEGUIR = 'G'
const val IA_REDUZIR = 'R'
const val IA_PARAR = 'P'

/** Letras que a Sakura pode pedir por voz. Movimento (W, S, A...) nunca vem por voz. */
const val COMANDOS_DE_VOZ = "0IMBK"

data class RespostaSakura(
    val decisao: Char,
    val motivo: String,
    val fala: String,
    val comandos: String,
)

private fun textoJson(json: String, chave: String): String? =
    Regex("\"" + chave + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)
        ?.groupValues?.get(1)
        ?.replace("\\n", " ")
        ?.replace("\\\"", "\"")
        ?.replace("\\\\", "\\")

/**
 * Lê a resposta da ponte. Devolve null se ela não tiver uma decisão válida.
 * Comandos desconhecidos são descartados (só passam os de COMANDOS_DE_VOZ).
 */
fun interpretarRespostaSakura(json: String): RespostaSakura? {
    val decisao = textoJson(json, "decisao")?.trim()?.firstOrNull()?.uppercaseChar() ?: return null
    if (decisao != IA_SEGUIR && decisao != IA_REDUZIR && decisao != IA_PARAR) return null
    val comandos = (textoJson(json, "comandos") ?: "")
        .uppercase()
        .filter { it in COMANDOS_DE_VOZ }
    return RespostaSakura(
        decisao = decisao,
        motivo = (textoJson(json, "motivo") ?: "").take(120),
        fala = (textoJson(json, "fala") ?: "").take(300),
        comandos = comandos,
    )
}

/**
 * Transforma o que a pessoa digitou no endereço da ponte.
 * "192.168.0.10" -> "http://192.168.0.10:8765"; aceita também ":porta" e "http://".
 * Devolve null se não parecer um endereço.
 */
fun normalizarEnderecoSakura(digitado: String): String? {
    var texto = digitado.trim().removeSuffix("/")
    if (texto.startsWith("http://", ignoreCase = true)) texto = texto.substring(7)
    if (texto.isEmpty() || texto.contains('/') || texto.contains(' ')) return null
    val partes = texto.split(':')
    if (partes.size > 2) return null
    val host = partes[0]
    if (!Regex("[A-Za-z0-9.-]+").matches(host) || host.startsWith('.') || host.endsWith('.')) return null
    val porta = if (partes.size == 2) partes[1].toIntOrNull() ?: return null else PORTA_SAKURA
    if (porta !in 1..65535) return null
    return "http://$host:$porta"
}

/** "http://192.168.0.10:8765" -> "192.168.0.10:8765" (para mostrar na tela). */
fun enderecoParaMostrar(endereco: String): String = endereco.removePrefix("http://")
