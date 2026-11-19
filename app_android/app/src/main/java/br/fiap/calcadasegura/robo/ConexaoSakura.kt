package br.fiap.calcadasegura.robo

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Conversa com a ponte da Sakura (sakura/robo.py) pelo Wi-Fi.
 *
 * - Tudo de rede roda em UMA thread separada: a tela e o batimento do robô
 *   nunca esperam pela rede.
 * - Só a leitura MAIS NOVA importa: se chegam várias enquanto um pedido está
 *   em andamento, as velhas são descartadas e vai só a última.
 * - As respostas chegam na linha principal (a da tela), pelo Ouvinte.
 */
class ConexaoSakura(private val ouvinte: Ouvinte) {

    interface Ouvinte {
        fun aoResponderSakura(resposta: RespostaSakura)
        fun aoFalharSakura(motivo: String)
    }

    private val principal = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pendente = AtomicReference<String?>(null)
    private val ocupado = AtomicBoolean(false)

    /** Endereço da ponte ("http://ip:porta"), ou null = Sakura desligada. */
    @Volatile
    var endereco: String? = null
        set(valor) {
            field = valor
            if (valor == null) pendente.set(null)
        }

    /** Manda uma linha JSON de telemetria (se a Sakura estiver ligada). */
    fun enviarTelemetria(linhaJson: String) {
        if (endereco == null) return
        pendente.set(linhaJson)
        agendar()
    }

    private fun agendar() {
        if (pendente.get() != null && ocupado.compareAndSet(false, true)) {
            try {
                executor.execute { trabalhar() }
            } catch (e: Exception) {
                ocupado.set(false) // executor já desligado (app fechando)
            }
        }
    }

    private fun trabalhar() {
        try {
            while (true) {
                val linha = pendente.getAndSet(null) ?: break
                val base = endereco ?: break
                try {
                    val texto = pedir("$base/robo/telemetria", linha)
                    val resposta = interpretarRespostaSakura(texto)
                    if (endereco != base) continue // desligaram a Sakura no meio
                    principal.post {
                        if (resposta != null) {
                            ouvinte.aoResponderSakura(resposta)
                        } else {
                            ouvinte.aoFalharSakura("resposta inesperada")
                        }
                    }
                } catch (e: IOException) {
                    val motivo = e.message ?: e.javaClass.simpleName
                    if (endereco == base) principal.post { ouvinte.aoFalharSakura(motivo) }
                }
            }
        } finally {
            ocupado.set(false)
            agendar() // chegou leitura enquanto terminava
        }
    }

    /**
     * Testa o endereço (GET /status) antes de ligar a Sakura.
     * O resultado chega na linha principal: null = deu certo, ou o motivo do erro.
     */
    fun testar(base: String, resultado: (String?) -> Unit) {
        try {
            executor.execute {
                val erro = try {
                    val texto = pedir("$base/status", null)
                    if (texto.contains("\"ok\"") && texto.contains("true")) null else "não é a Sakura"
                } catch (e: IOException) {
                    e.message ?: e.javaClass.simpleName
                }
                principal.post { resultado(erro) }
            }
        } catch (e: Exception) {
            resultado("app fechando")
        }
    }

    fun liberar() {
        endereco = null
        executor.shutdownNow()
    }

    /** GET (corpo = null) ou POST de JSON. Devolve o texto da resposta. */
    private fun pedir(url: String, corpo: String?): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TEMPO_MS
            c.readTimeout = TEMPO_MS
            c.useCaches = false
            if (corpo != null) {
                val bytes = corpo.toByteArray(Charsets.UTF_8)
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val codigo = c.responseCode
            if (codigo != HttpURLConnection.HTTP_OK) throw IOException("HTTP $codigo")
            return c.inputStream.use { entrada ->
                // A resposta é pequena; mais que 4 KB é lixo.
                val buffer = ByteArray(LIMITE_RESPOSTA)
                var total = 0
                while (total < buffer.size) {
                    val n = entrada.read(buffer, total, buffer.size - total)
                    if (n < 0) break
                    total += n
                }
                String(buffer, 0, total, Charsets.UTF_8)
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private const val TEMPO_MS = 1500
        private const val LIMITE_RESPOSTA = 4096
    }
}
