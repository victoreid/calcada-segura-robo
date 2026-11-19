package br.fiap.calcadasegura.robo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Conversa com o HC-05 pelo Bluetooth clássico (perfil serial, SPP).
 *
 * - Conectar roda numa linha de execução separada: a tela nunca trava.
 * - Uma linha separada lê o que o robô manda e entrega linha por linha.
 * - Os envios vão para uma fila com uma linha só (nunca na linha da tela).
 * - Todos os avisos para a tela chegam na linha principal (a da tela).
 *
 * Permissão: a MainActivity só chama esta classe depois de ter a permissão de
 * Bluetooth (BLUETOOTH_CONNECT no Android 12+). Por isso o aviso MissingPermission
 * do lint é suprimido aqui, e as SecurityException são tratadas como erro de conexão.
 */
@SuppressLint("MissingPermission")
class ConexaoBluetooth(
    ctx: Context,
    private val adaptador: BluetoothAdapter,
    private val ouvinte: Ouvinte,
) {

    /** Avisos da conexão. Sempre chamados na linha principal (a da tela). */
    interface Ouvinte {
        /** Conectou no robô. */
        fun aoConectar(nome: String)

        /** Não conseguiu conectar. [motivo] é um texto curto em português. */
        fun aoFalharConexao(motivo: String)

        /** Chegou uma linha completa do robô (ex.: "DIST:32"). */
        fun aoReceberLinha(linha: String)

        /** A conexão caiu sozinha. Não é chamado quando o app pede para desconectar. */
        fun aoDesconectar()
    }

    /** Tudo o que pertence a uma conexão aberta. */
    private class Canal(
        val sessao: Int,
        val socket: BluetoothSocket,
        val entrada: InputStream,
        val saida: OutputStream,
        val escritor: ExecutorService,
    )

    private val contexto: Context = ctx.applicationContext
    private val principal = Handler(Looper.getMainLooper())
    private val trava = Any()
    private val conectando = AtomicBoolean(false)

    // Protegidos pela trava. A "sessão" muda a cada conectar/desconectar:
    // avisos de uma sessão antiga são ignorados.
    private var sessao = 0
    private var canalAtual: Canal? = null
    private var tentativa: BluetoothSocket? = null

    /** Três jeitos de abrir o socket. Alguns celulares só funcionam com um deles. */
    private val formasDeAbrir: List<(BluetoothDevice) -> BluetoothSocket> =
        listOf<(BluetoothDevice) -> BluetoothSocket>(
            { d -> d.createRfcommSocketToServiceRecord(SPP_UUID) },
            { d -> d.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            // Último recurso: método escondido do Android, canal 1 (comum no HC-05).
            { d ->
                val metodo = d.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                metodo.invoke(d, 1) as BluetoothSocket
            },
        )

    fun estaConectado(): Boolean = synchronized(trava) { canalAtual != null }

    fun estaConectando(): Boolean = conectando.get()

    /**
     * Começa a conectar em segundo plano. Devolve false se já existe uma
     * tentativa em andamento (não deixa conectar duas vezes ao mesmo tempo).
     */
    fun conectar(dispositivo: BluetoothDevice, nome: String): Boolean {
        if (!conectando.compareAndSet(false, true)) return false

        val (minhaSessao, antigo) = synchronized(trava) {
            sessao++
            val anterior = canalAtual
            canalAtual = null
            Pair(sessao, anterior)
        }
        if (antigo != null) fecharCanalSemAviso(antigo)

        thread(name = "robo-bluetooth", isDaemon = true) {
            val canal = try {
                abrirCanal(dispositivo, minhaSessao)
            } finally {
                conectando.set(false)
            }
            if (canal != null) {
                noPrincipal(minhaSessao) { ouvinte.aoConectar(nome) }
                lerAteCair(canal)
            }
        }
        return true
    }

    /** Envia um comando (uma letra) sem travar a tela. */
    fun enviar(comando: Char) {
        val canal = synchronized(trava) { canalAtual } ?: return
        try {
            canal.escritor.execute {
                try {
                    canal.saida.write(comando.code)
                    canal.saida.flush()
                } catch (e: Exception) {
                    perdeuConexao(canal)
                }
            }
        } catch (e: RejectedExecutionException) {
            // A fila já foi desligada: a conexão está fechando.
        }
    }

    /**
     * Desconecta (ou cancela a tentativa em andamento). Tenta mandar '0' antes
     * de fechar. Pode ser chamado várias vezes sem problema.
     */
    fun desconectar() {
        val (canal, emTentativa) = synchronized(trava) {
            sessao++
            val par = Pair(canalAtual, tentativa)
            canalAtual = null
            tentativa = null
            par
        }
        fecharSocket(emTentativa)
        if (canal != null) fecharCanalSemAviso(canal)
    }

    /** Usado quando a tela é destruída. */
    fun liberar() {
        desconectar()
        principal.removeCallbacksAndMessages(null)
    }

    // ------------------------------------------------------------------
    // Daqui para baixo roda fora da linha da tela.

    private fun abrirCanal(dispositivo: BluetoothDevice, minhaSessao: Int): Canal? {
        cancelarBusca()
        var ultimoErro: Exception? = null

        for (abrir in formasDeAbrir) {
            var aberto: BluetoothSocket? = null
            try {
                val socket = abrir(dispositivo)
                aberto = socket

                val liberado = synchronized(trava) {
                    if (sessao == minhaSessao) {
                        tentativa = socket
                        true
                    } else {
                        false
                    }
                }
                if (!liberado) {
                    // O usuário cancelou enquanto a gente tentava.
                    fecharSocket(socket)
                    return null
                }

                socket.connect() // demora alguns segundos se o robô estiver longe ou desligado

                val novo = Canal(
                    minhaSessao,
                    socket,
                    socket.inputStream,
                    socket.outputStream,
                    Executors.newSingleThreadExecutor(),
                )
                val aceito = synchronized(trava) {
                    if (tentativa === socket) tentativa = null
                    if (sessao == minhaSessao) {
                        canalAtual = novo
                        true
                    } else {
                        false
                    }
                }
                if (!aceito) {
                    novo.escritor.shutdownNow()
                    fecharSocket(socket)
                    return null
                }
                return novo
            } catch (e: Exception) {
                ultimoErro = e
                synchronized(trava) {
                    if (tentativa === aberto) tentativa = null
                }
                fecharSocket(aberto)
                if (sessaoAtual() != minhaSessao) return null // cancelado
            }
        }

        val motivo = motivoSimples(ultimoErro)
        noPrincipal(minhaSessao) { ouvinte.aoFalharConexao(motivo) }
        return null
    }

    private fun lerAteCair(canal: Canal) {
        val leitor = LeitorDeLinhas()
        val buffer = ByteArray(512)
        try {
            while (true) {
                val lidos = canal.entrada.read(buffer)
                if (lidos < 0) break
                if (lidos == 0) continue
                // O robô manda só ASCII. ISO-8859-1 nunca falha com bytes estranhos.
                val linhas = leitor.feed(String(buffer, 0, lidos, Charsets.ISO_8859_1))
                if (linhas.isNotEmpty()) {
                    noPrincipal(canal.sessao) {
                        for (linha in linhas) ouvinte.aoReceberLinha(linha)
                    }
                }
            }
        } catch (e: Exception) {
            // A leitura falhou: a conexão caiu (ou foi fechada pelo próprio app).
        }
        perdeuConexao(canal)
    }

    /** Chamado quando a leitura ou a escrita falha. Avisa a tela uma vez só. */
    private fun perdeuConexao(canal: Canal) {
        val novaSessao = synchronized(trava) {
            if (canalAtual === canal) {
                canalAtual = null
                sessao++
                sessao
            } else {
                -1
            }
        }
        if (novaSessao < 0) return // já tratado, ou foi o app que desconectou
        canal.escritor.shutdownNow()
        fecharSocket(canal.socket)
        noPrincipal(novaSessao) { ouvinte.aoDesconectar() }
    }

    /** Manda '0' (parar) e fecha, tudo na fila de envio, sem avisar a tela. */
    private fun fecharCanalSemAviso(canal: Canal) {
        try {
            canal.escritor.execute {
                try {
                    canal.saida.write(PARAR.code)
                    canal.saida.flush()
                    Thread.sleep(60) // dá tempo do '0' sair antes de fechar
                } catch (e: Exception) {
                    // Já estava fechado: tudo bem, o robô para sozinho em 1,5 s.
                }
                fecharSocket(canal.socket)
            }
            canal.escritor.shutdown()
        } catch (e: RejectedExecutionException) {
            fecharSocket(canal.socket)
        }
    }

    private fun cancelarBusca() {
        // A busca de aparelhos deixa a conexão lenta. No Android 12+ cancelar exige
        // BLUETOOTH_SCAN, que o app não pede; então só cancela se puder.
        val pode = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            contexto.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
        if (!pode) return
        try {
            adaptador.cancelDiscovery()
        } catch (e: SecurityException) {
            // Sem permissão: segue sem cancelar.
        }
    }

    private fun motivoSimples(erro: Exception?): String =
        if (erro is SecurityException) {
            contexto.getString(R.string.erro_sem_permissao)
        } else {
            contexto.getString(R.string.erro_sem_resposta)
        }

    private fun sessaoAtual(): Int = synchronized(trava) { sessao }

    /** Roda [bloco] na linha da tela, se a sessão ainda for a mesma. */
    private fun noPrincipal(sessaoEsperada: Int, bloco: () -> Unit) {
        principal.post {
            if (sessaoAtual() == sessaoEsperada) bloco()
        }
    }

    private fun fecharSocket(socket: BluetoothSocket?) {
        if (socket == null) return
        try {
            socket.close()
        } catch (e: IOException) {
            // Já estava fechado.
        }
    }

    companion object {
        /** UUID padrão da porta serial Bluetooth (SPP). O HC-05 usa este. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
