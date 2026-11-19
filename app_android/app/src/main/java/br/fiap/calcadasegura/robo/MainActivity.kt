package br.fiap.calcadasegura.robo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Tela única do app: conecta no HC-05, mostra os sensores e manda os comandos.
 *
 * Regras de segurança (o robô nunca pode ficar andando sozinho):
 * - A cada 300 ms o app reenvia o comando atual (batimento). Se o Bluetooth cair,
 *   o robô fica 1,5 s sem comando e para sozinho (watchdog do firmware).
 * - Quando algo cobre a tela (cortina de notificações, aviso do sistema) ou o app
 *   sai da tela, ele manda '0' (parar).
 * - Se o app ficar 15 s fora da tela, ele desconecta e solta o HC-05.
 * - Quando chega obstáculo ou buraco e o robô ia para a frente, o app para tudo.
 * - Cada dedo na tela é seguido pelo seu número (pointerId): soltou o dedo, o
 *   botão dele solta, mesmo com outro dedo ou a palma da mão encostada na tela.
 */
class MainActivity : Activity(), ConexaoBluetooth.Ouvinte {

    private enum class Estado { DESCONECTADO, CONECTANDO, CONECTADO }

    private val principal = Handler(Looper.getMainLooper())
    private val controle = ControleDeComandos()
    private val botoes = LinkedHashMap<View, Char>()
    private val mensagens = ArrayDeque<String>()

    /** Dedo na tela (pointerId) -> botão que esse dedo apertou. Igual ao Map por pointerId do app do computador. */
    private val donos = HashMap<Int, View>()
    private val posicaoNaJanela = IntArray(2)

    private var adaptador: BluetoothAdapter? = null
    private var conexao: ConexaoBluetooth? = null
    private lateinit var preferencias: SharedPreferences

    private var estado = Estado.DESCONECTADO
    private var nomeDoRobo = ""
    private var macEmTentativa: String? = null
    private var erroDeStatus: String? = null
    private var ultimoEnviado: Char? = null
    private var ultimaLinhaEm = 0L
    private var semDados = false
    private var ultimoAvisoDesconectado = 0L
    private var distanciaCm: Int? = null
    private var obstaculo: Boolean? = null // null = ainda sem dados
    private var buraco: Boolean? = null
    private var mensagensVisiveis = false

    // O que fazer depois que o usuário der a permissão ou ligar o Bluetooth.
    private var acaoPendente: (() -> Unit)? = null

    // true enquanto a janela de permissão ou a de ligar o Bluetooth está aberta.
    // Evita abrir duas janelas iguais com dois toques rápidos em "Conectar ao robô".
    private var pedidoEmAndamento = false

    // O Android já mostrava a explicação da permissão antes do pedido? (ver onRequestPermissionsResult)
    private var podiaExplicarAntes = false

    private lateinit var pontoStatus: View
    private lateinit var textoStatus: TextView
    private lateinit var botaoConectar: Button
    private lateinit var botaoTrocar: Button
    private lateinit var textoAviso: TextView
    private lateinit var textoDistancia: TextView
    private lateinit var barraDistancia: ProgressBar
    private lateinit var cartaoObstaculo: View
    private lateinit var pontoObstaculo: View
    private lateinit var textoObstaculo: TextView
    private lateinit var cartaoChao: View
    private lateinit var pontoChao: View
    private lateinit var textoChao: TextView
    private lateinit var textoMovimento: TextView
    private lateinit var textoContinuo: TextView
    private lateinit var botaoMensagens: Button
    private lateinit var textoMensagens: TextView

    /** Batimento: reenvia o comando atual a cada 300 ms enquanto estiver conectado. */
    private val batimento = object : Runnable {
        override fun run() {
            if (estado != Estado.CONECTADO) return
            enviarComandoAtual(forcar = true)
            atualizarAvisoSemDados()
            principal.postDelayed(this, BATIMENTO_MS)
        }
    }

    /** O app ficou muito tempo fora da tela: solta o HC-05 (para o PC poder usar o robô). */
    private val desligarEmSegundoPlano = Runnable {
        when (estado) {
            Estado.CONECTANDO -> cancelarConexao()
            Estado.CONECTADO -> desconectarPeloUsuario()
            Estado.DESCONECTADO -> Unit
        }
    }

    // ================================================================
    // Ciclo de vida da tela

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preferencias = getSharedPreferences(PREFERENCIAS, Context.MODE_PRIVATE)

        ligarViews()
        configurarBotoesDeDirecao()

        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        adaptador = adapter
        if (adapter == null) {
            erroDeStatus = getString(R.string.sem_bluetooth)
            botaoConectar.isEnabled = false
            botaoTrocar.isEnabled = false
        } else {
            conexao = ConexaoBluetooth(this, adapter, this)
        }
        atualizarTudo()
    }

    override fun onStart() {
        super.onStart()
        // Voltou para a tela a tempo: não precisa mais desconectar.
        principal.removeCallbacks(desligarEmSegundoPlano)
    }

    override fun onPause() {
        super.onPause()
        // Algo cobriu a tela (Assistente, janela do sistema): o robô para.
        pararPorSeguranca()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Cortina de notificações ou aviso do sistema por cima: o ■ fica fora de alcance.
        // Conectado, o app nunca abre janela própria, então isso não para o robô sem motivo.
        if (!hasFocus) pararPorSeguranca()
    }

    override fun onStop() {
        super.onStop()
        // O app saiu da tela: o robô não pode continuar andando.
        pararPorSeguranca()
        // Só sair do app (Home) não fecha a conexão. Depois de 15 s fora, o app solta o HC-05.
        if (estado != Estado.DESCONECTADO) {
            principal.removeCallbacks(desligarEmSegundoPlano)
            principal.postDelayed(desligarEmSegundoPlano, SEGUNDO_PLANO_MS)
        }
    }

    /** Voltar fecha o app de verdade (e solta o robô) em todas as versões do Android. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        pararBatimento()
        conexao?.liberar()
        conexao = null
        principal.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun ligarViews() {
        pontoStatus = findViewById(R.id.ponto_status)
        textoStatus = findViewById(R.id.texto_status)
        botaoConectar = findViewById(R.id.botao_conectar)
        botaoTrocar = findViewById(R.id.botao_trocar)
        textoAviso = findViewById(R.id.texto_aviso)
        textoDistancia = findViewById(R.id.texto_distancia)
        barraDistancia = findViewById(R.id.barra_distancia)
        cartaoObstaculo = findViewById(R.id.cartao_obstaculo)
        pontoObstaculo = findViewById(R.id.ponto_obstaculo)
        textoObstaculo = findViewById(R.id.texto_obstaculo)
        cartaoChao = findViewById(R.id.cartao_chao)
        pontoChao = findViewById(R.id.ponto_chao)
        textoChao = findViewById(R.id.texto_chao)
        textoMovimento = findViewById(R.id.texto_movimento)
        textoContinuo = findViewById(R.id.texto_continuo)
        botaoMensagens = findViewById(R.id.botao_mensagens)
        textoMensagens = findViewById(R.id.texto_mensagens)

        botaoConectar.setOnClickListener { aoTocarConectar() }
        botaoTrocar.setOnClickListener { trocarRobo() }
        botaoMensagens.setOnClickListener { alternarMensagens() }
    }

    // ================================================================
    // Botões de direção (grade 3x3)

    @SuppressLint("ClickableViewAccessibility")
    private fun configurarBotoesDeDirecao() {
        val ligacoes = listOf(
            R.id.botao_q to 'Q', R.id.botao_w to 'W', R.id.botao_e to 'E',
            R.id.botao_a to 'A', R.id.botao_parar to PARAR, R.id.botao_d to 'D',
            R.id.botao_z to 'Z', R.id.botao_s to 'S', R.id.botao_c to 'C',
        )
        for ((id, comando) in ligacoes) {
            val botao = findViewById<View>(id)
            botoes[botao] = comando
            // Quem decide o comando é o dispatchTouchEvent (abaixo), dedo por dedo.
            // Aqui o botão só fica com o toque, para a tela não rolar e o desenho
            // do botão (apertado ou não) ser controlado só pelo app.
            botao.setOnTouchListener { v, evento ->
                when (evento.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP -> v.performClick()
                }
                true
            }
        }
    }

    /**
     * Recebe TODOS os dedos da tela antes dos botões. Cada dedo é seguido pelo
     * seu número (pointerId), igual ao app do computador. Assim nenhum dedo
     * "some" no caminho: com a palma encostada na tela, com a tela rolando ou com
     * dois dedos no mesmo botão, soltar o dedo sempre solta o botão dele, e o ■
     * sempre funciona.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                esquecerDedosAntigos()
                dedoDesceu(ev, ev.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> dedoDesceu(ev, ev.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                dedoSubiu(ev.getPointerId(ev.actionIndex), ev.eventTime)
            // O Android tirou o toque do app (ex.: gesto do sistema): para tudo.
            MotionEvent.ACTION_CANCEL -> pararPorSeguranca()
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Um dedo encostou na tela. Se foi em um botão da grade, aperta esse botão. */
    private fun dedoDesceu(ev: MotionEvent, indice: Int) {
        val botao = botaoEm(ev.getX(indice), ev.getY(indice)) ?: return
        val comando = botoes[botao] ?: return
        val dedo = ev.getPointerId(indice)
        botao.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        avisarSeDesconectado()
        val resultado = controle.pressionar(dedo, comando, ev.eventTime)
        if (resultado != ResultadoToque.SEGURANDO) {
            // ■ ou toque duplo: os outros dedos deixam de valer (o controle já soltou todos).
            donos.clear()
        }
        donos[dedo] = botao
        atualizarBotoesApertados()
        enviarComandoAtual(forcar = resultado == ResultadoToque.PAROU)
    }

    /** Um dedo saiu da tela. Se ele segurava um botão, solta esse botão. */
    private fun dedoSubiu(dedo: Int, agoraMs: Long) {
        val botao = donos.remove(dedo)
        // Se o dedo já não valia (■, perigo ou toque duplo), o controle ignora.
        controle.soltar(dedo, agoraMs)
        if (botao != null) atualizarBotoesApertados()
        enviarComandoAtual()
    }

    /** Começo de um gesto novo: um dedo antigo que tenha ficado guardado não vale mais. */
    private fun esquecerDedosAntigos() {
        if (donos.isEmpty()) return
        for (dedo in donos.keys) controle.cancelar(dedo)
        donos.clear()
        atualizarBotoesApertados()
        enviarComandoAtual()
    }

    /** Qual botão da grade está no ponto (x, y) da janela, ou null. */
    private fun botaoEm(x: Float, y: Float): View? {
        for (botao in botoes.keys) {
            if (!botao.isShown || botao.width == 0 || botao.height == 0) continue
            botao.getLocationInWindow(posicaoNaJanela)
            val esquerda = posicaoNaJanela[0].toFloat()
            val topo = posicaoNaJanela[1].toFloat()
            if (x >= esquerda && x < esquerda + botao.width &&
                y >= topo && y < topo + botao.height
            ) {
                return botao
            }
        }
        return null
    }

    /** O botão aparece apertado enquanto um dedo que ainda vale estiver nele. */
    private fun atualizarBotoesApertados() {
        for (botao in botoes.keys) botao.isPressed = donos.containsValue(botao)
    }

    private fun soltarBotoesNaTela() {
        donos.clear()
        for (v in botoes.keys) v.isPressed = false
    }

    /** Para o robô e solta todos os botões (tela coberta, app fora da tela, toque cancelado). */
    private fun pararPorSeguranca() {
        controle.pararTudo()
        soltarBotoesNaTela()
        enviarComandoAtual(forcar = true)
    }

    private fun avisarSeDesconectado() {
        if (estado == Estado.CONECTADO) return
        val agora = SystemClock.elapsedRealtime()
        if (agora - ultimoAvisoDesconectado < 2000) return
        ultimoAvisoDesconectado = agora
        Toast.makeText(this, R.string.conecte_primeiro, Toast.LENGTH_SHORT).show()
    }

    /** Envia o comando atual se ele mudou (ou sempre, se forcar = true). */
    private fun enviarComandoAtual(forcar: Boolean = false) {
        val comando = controle.comandoAtual()
        if (estado == Estado.CONECTADO && (forcar || comando != ultimoEnviado)) {
            conexao?.enviar(comando)
            ultimoEnviado = comando
        }
        atualizarComandoNaTela()
    }

    private fun iniciarBatimento() {
        principal.removeCallbacks(batimento)
        principal.postDelayed(batimento, BATIMENTO_MS)
    }

    private fun pararBatimento() {
        principal.removeCallbacks(batimento)
    }

    // ================================================================
    // Conectar e desconectar

    private fun aoTocarConectar() {
        when (estado) {
            Estado.DESCONECTADO -> prepararEConectar(escolherOutro = false)
            Estado.CONECTANDO -> cancelarConexao()
            Estado.CONECTADO -> desconectarPeloUsuario()
        }
    }

    private fun trocarRobo() {
        when (estado) {
            Estado.CONECTANDO -> cancelarConexao()
            Estado.CONECTADO -> desconectarPeloUsuario()
            Estado.DESCONECTADO -> Unit
        }
        prepararEConectar(escolherOutro = true)
    }

    private fun temPermissaoBluetooth(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Confere permissão, Bluetooth ligado e aparelho pareado, e então conecta. */
    @SuppressLint("MissingPermission")
    private fun prepararEConectar(escolherOutro: Boolean) {
        if (estado != Estado.DESCONECTADO) return
        // A janela de permissão ou a de ligar o Bluetooth já está aberta: espera a resposta.
        if (pedidoEmAndamento) return
        val adapter = adaptador
        if (adapter == null || conexao == null) {
            Toast.makeText(this, R.string.sem_bluetooth, Toast.LENGTH_LONG).show()
            return
        }
        if (!temPermissaoBluetooth()) {
            acaoPendente = { prepararEConectar(escolherOutro) }
            pedirPermissao()
            return
        }
        if (!adapter.isEnabled) {
            acaoPendente = { prepararEConectar(escolherOutro) }
            pedirParaLigarBluetooth()
            return
        }

        val pareados = listarPareados(adapter)
        if (pareados.isEmpty()) {
            mostrarAjudaParaParear(semPareados = true)
            return
        }

        if (!escolherOutro) {
            // 1) O último robô usado, se ainda estiver pareado.
            val macSalvo = preferencias.getString(CHAVE_MAC, null)
            val lembrado = pareados.firstOrNull { it.address.equals(macSalvo, ignoreCase = true) }
            if (lembrado != null) {
                conectarEm(lembrado)
                return
            }
            // 2) Se só um aparelho parece o robô, usa ele.
            val parecidos = pareados.filter { pareceRobo(it) }
            if (parecidos.size == 1) {
                conectarEm(parecidos[0])
                return
            }
        }
        // 3) Senão, o usuário escolhe na lista.
        mostrarListaDeAparelhos(pareados)
    }

    private fun conectarEm(dispositivo: BluetoothDevice) {
        val c = conexao ?: return
        if (estado != Estado.DESCONECTADO) return
        val nome = nomeDe(dispositivo)
        if (!c.conectar(dispositivo, nome)) {
            // A tentativa anterior ainda está terminando.
            Toast.makeText(this, R.string.aguarde, Toast.LENGTH_SHORT).show()
            return
        }
        estado = Estado.CONECTANDO
        nomeDoRobo = nome
        macEmTentativa = dispositivo.address
        erroDeStatus = null
        atualizarTudo()
    }

    private fun cancelarConexao() {
        conexao?.desconectar()
        ficarDesconectado(null)
    }

    private fun desconectarPeloUsuario() {
        controle.pararTudo()
        soltarBotoesNaTela()
        if (estado == Estado.CONECTADO) conexao?.enviar(PARAR)
        conexao?.desconectar() // também tenta mandar '0' antes de fechar
        ficarDesconectado(null)
    }

    private fun ficarDesconectado(motivo: String?) {
        pararBatimento()
        estado = Estado.DESCONECTADO
        erroDeStatus = motivo
        ultimoEnviado = null
        distanciaCm = null
        obstaculo = null
        buraco = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        atualizarTudo()
    }

    // ---------------- Avisos da ConexaoBluetooth (linha da tela) ----------------

    override fun aoConectar(nome: String) {
        estado = Estado.CONECTADO
        nomeDoRobo = nome
        erroDeStatus = null
        macEmTentativa?.let { preferencias.edit().putString(CHAVE_MAC, it).apply() }
        controle.pararTudo()
        soltarBotoesNaTela()
        ultimoEnviado = null
        distanciaCm = null
        obstaculo = null
        buraco = null
        mensagens.clear()
        if (mensagensVisiveis) mostrarMensagens()
        // Dá 2 s para chegar a primeira linha antes de mostrar o aviso de "sem dados".
        ultimaLinhaEm = SystemClock.elapsedRealtime()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enviarComandoAtual(forcar = true)
        iniciarBatimento()
        atualizarTudo()
    }

    override fun aoFalharConexao(motivo: String) {
        ficarDesconectado(getString(R.string.status_erro, motivo))
        Toast.makeText(this, R.string.dica_falha, Toast.LENGTH_LONG).show()
    }

    override fun aoDesconectar() {
        controle.pararTudo()
        soltarBotoesNaTela()
        ficarDesconectado(getString(R.string.status_conexao_perdida))
        vibrar(VIBRACAO_MS)
        Toast.makeText(this, R.string.conexao_caiu, Toast.LENGTH_LONG).show()
    }

    override fun aoReceberLinha(linha: String) {
        if (estado != Estado.CONECTADO) return
        ultimaLinhaEm = SystemClock.elapsedRealtime()
        atualizarAvisoSemDados() // chegou dado: os indicadores voltam a valer
        guardarMensagem(linha)
        when (val evento = interpretarLinha(linha)) {
            is EventoRobo.Distancia -> {
                distanciaCm = evento.cm
                atualizarDistancia()
            }
            is EventoRobo.Colisao -> tratarPerigo(evento.ativo, buraco)
            is EventoRobo.Buraco -> tratarPerigo(obstaculo, evento.ativo)
            is EventoRobo.Outra -> Unit
            null -> Unit
        }
    }

    /** Atualiza obstáculo e buraco. Se o robô ia para a frente, para tudo. */
    private fun tratarPerigo(novoObstaculo: Boolean?, novoBuraco: Boolean?) {
        val surgiu = (novoObstaculo == true && obstaculo != true) ||
            (novoBuraco == true && buraco != true)
        obstaculo = novoObstaculo
        buraco = novoBuraco

        val temObstaculo = novoObstaculo == true
        val temBuraco = novoBuraco == true
        val parou = controle.aplicarPerigo(temObstaculo, temBuraco)
        if (parou) {
            // O dedo pode continuar no botão, mas ele não vale mais: mostra o botão solto.
            soltarBotoesNaTela()
            enviarComandoAtual(forcar = true) // manda '0'
        }

        if (surgiu) vibrar(VIBRACAO_MS)
        if (parou) {
            val motivo = when {
                temObstaculo && temBuraco -> R.string.motivo_os_dois
                temObstaculo -> R.string.motivo_obstaculo
                else -> R.string.motivo_buraco
            }
            val texto = getString(R.string.robo_parou, getString(motivo))
            Toast.makeText(this, texto, Toast.LENGTH_SHORT).show()
        }
        atualizarPerigo()
        atualizarComandoNaTela()
    }

    // ---------------- Permissão e Bluetooth ligado ----------------

    private fun pedirPermissao() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        // Guarda se o Android já mostrava a explicação ANTES do pedido.
        podiaExplicarAntes = shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)
        pedidoEmAndamento = true
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), PEDIDO_PERMISSAO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PEDIDO_PERMISSAO) return
        pedidoEmAndamento = false

        // Resposta vazia = o pedido foi interrompido (não é um "não"). O usuário toca de novo.
        if (grantResults.isEmpty()) return

        val acao = acaoPendente
        acaoPendente = null
        val jaTinhaPedido = preferencias.getBoolean(CHAVE_JA_PEDIU, false)
        preferencias.edit().putBoolean(CHAVE_JA_PEDIU, true).apply()

        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            acao?.invoke()
            return
        }

        erroDeStatus = getString(R.string.status_erro, getString(R.string.erro_sem_permissao))
        atualizarStatus()
        // "Negou para sempre" = o Android não mostra mais o pedido. No Android 11+,
        // fechar a janela (Voltar ou tocar fora) não conta como negar e também não
        // mostra a explicação. Por isso só conta como "para sempre" se o app já tinha
        // pedido antes, ou se o Android mostrava a explicação antes e agora não mostra.
        val negouDeVez = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT) &&
            (podiaExplicarAntes || jaTinhaPedido)
        if (negouDeVez) {
            // O Android não mostra mais o pedido: só dá para liberar nas configurações.
            AlertDialog.Builder(this)
                .setTitle(R.string.permissao_titulo)
                .setMessage(R.string.permissao_negada_msg)
                .setPositiveButton(R.string.abrir_configuracoes) { _, _ -> abrirDetalhesDoApp() }
                .setNegativeButton(R.string.agora_nao, null)
                .show()
        } else {
            Toast.makeText(this, R.string.permissao_negada_curta, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun pedirParaLigarBluetooth() {
        try {
            pedidoEmAndamento = true
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), PEDIDO_LIGAR_BT)
        } catch (e: Exception) {
            // Alguns celulares não têm essa tela, ou negam sem permissão.
            pedidoEmAndamento = false
            acaoPendente = null
            Toast.makeText(this, R.string.bt_ligue, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PEDIDO_LIGAR_BT) return
        pedidoEmAndamento = false
        val acao = acaoPendente
        acaoPendente = null
        if (resultCode == RESULT_OK && adaptador?.isEnabled == true) {
            acao?.invoke()
        } else {
            Toast.makeText(this, R.string.bt_desligado, Toast.LENGTH_LONG).show()
        }
    }

    private fun abrirDetalhesDoApp() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        abrirTela(intent, R.string.sem_tela_app)
    }

    private fun abrirConfigBluetooth() {
        abrirTela(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), R.string.sem_tela_bluetooth)
    }

    private fun abrirTela(intent: Intent, mensagemDeErro: Int) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, mensagemDeErro, Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- Escolha do robô (só aparelhos pareados) ----------------

    @SuppressLint("MissingPermission")
    private fun listarPareados(adapter: BluetoothAdapter): List<BluetoothDevice> =
        try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }

    @SuppressLint("MissingPermission")
    private fun nomeOuNull(dispositivo: BluetoothDevice): String? =
        try {
            dispositivo.name?.takeIf { it.isNotBlank() }
        } catch (e: SecurityException) {
            null
        }

    private fun nomeDe(dispositivo: BluetoothDevice): String =
        nomeOuNull(dispositivo) ?: dispositivo.address

    /** HC-05 costuma ter nome HC-05/HC-06 (o nosso se chama FB21_083) e MAC começando com 98:D3. */
    private fun pareceRobo(dispositivo: BluetoothDevice): Boolean {
        val nome = nomeOuNull(dispositivo)?.uppercase() ?: ""
        val mac = dispositivo.address?.uppercase() ?: ""
        return nome.contains("HC-05") || nome.contains("HC-06") ||
            nome.contains("FB21") || mac.startsWith("98:D3")
    }

    private fun mostrarListaDeAparelhos(pareados: List<BluetoothDevice>) {
        // Os que parecem o robô aparecem primeiro.
        val ordenados = pareados.sortedByDescending { pareceRobo(it) }
        val rotulos = Array<CharSequence>(ordenados.size) { i ->
            val d = ordenados[i]
            val dica = if (pareceRobo(d)) "  (" + getString(R.string.provavel_robo) + ")" else ""
            nomeDe(d) + dica + "\n" + d.address
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.escolha_o_robo)
            .setItems(rotulos) { _, qual -> conectarEm(ordenados[qual]) }
            .setNeutralButton(R.string.parear_outro) { _, _ -> mostrarAjudaParaParear(semPareados = false) }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    private fun mostrarAjudaParaParear(semPareados: Boolean) {
        AlertDialog.Builder(this)
            .setTitle(R.string.parear_titulo)
            .setMessage(if (semPareados) R.string.sem_pareados_msg else R.string.parear_msg)
            .setPositiveButton(R.string.abrir_bluetooth) { _, _ -> abrirConfigBluetooth() }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    // ================================================================
    // Atualizar a tela

    private fun atualizarTudo() {
        atualizarStatus()
        atualizarAvisoSemDados()
        atualizarDistancia()
        atualizarPerigo()
        atualizarComandoNaTela()
    }

    private fun atualizarStatus() {
        // Texto do status e cor da bolinha.
        val (texto, cor) = when (estado) {
            Estado.CONECTANDO -> Pair(getString(R.string.status_conectando, nomeDoRobo), R.color.ambar)
            Estado.CONECTADO -> Pair(getString(R.string.status_conectado, nomeDoRobo), R.color.verde)
            Estado.DESCONECTADO -> {
                val erro = erroDeStatus
                if (erro == null) {
                    Pair(getString(R.string.status_desconectado), R.color.cinza)
                } else {
                    Pair(erro, R.color.perigo)
                }
            }
        }
        textoStatus.text = texto
        pintar(pontoStatus, cor)

        botaoConectar.setText(
            when (estado) {
                Estado.DESCONECTADO -> R.string.botao_conectar
                Estado.CONECTANDO -> R.string.botao_cancelar
                Estado.CONECTADO -> R.string.botao_desconectar
            },
        )
        botaoConectar.setBackgroundResource(
            if (estado == Estado.DESCONECTADO) R.drawable.bg_botao_conectar else R.drawable.bg_botao_desconectar,
        )
    }

    /** Os valores dos sensores só valem conectado e com linhas chegando. */
    private fun dadosValidos(): Boolean = estado == Estado.CONECTADO && !semDados

    private fun atualizarDistancia() {
        val cm = if (dadosValidos()) distanciaCm else null
        val cor: Int
        if (cm == null) {
            textoDistancia.setText(R.string.distancia_desconhecida)
            barraDistancia.progress = 0
            cor = R.color.cinza
        } else if (cm >= DISTANCIA_MAXIMA) {
            textoDistancia.setText(R.string.distancia_livre)
            barraDistancia.progress = 100
            cor = R.color.destaque
        } else {
            textoDistancia.text = getString(R.string.distancia_cm, cm)
            barraDistancia.progress = cm.coerceIn(0, 100)
            cor = if (cm < 20) R.color.perigo else if (cm < 40) R.color.ambar else R.color.destaque
        }
        barraDistancia.progressTintList = ColorStateList.valueOf(getColor(cor))
    }

    private fun atualizarPerigo() {
        val valido = dadosValidos()
        mostrarIndicador(
            cartaoObstaculo, pontoObstaculo, textoObstaculo,
            if (valido) obstaculo else null,
            R.string.obstaculo_livre, R.string.obstaculo_ativo,
        )
        mostrarIndicador(
            cartaoChao, pontoChao, textoChao,
            if (valido) buraco else null,
            R.string.chao_ok, R.string.chao_buraco,
        )
    }

    private fun mostrarIndicador(
        cartao: View,
        ponto: View,
        texto: TextView,
        ativo: Boolean?,
        textoOk: Int,
        textoPerigo: Int,
    ) {
        when (ativo) {
            null -> {
                texto.setText(R.string.sem_dados)
                texto.setTextColor(getColor(R.color.tinta_suave))
                pintar(ponto, R.color.cinza)
                cartao.setBackgroundResource(R.drawable.cartao_indicador)
            }
            false -> {
                texto.setText(textoOk)
                texto.setTextColor(getColor(R.color.tinta))
                pintar(ponto, R.color.verde)
                cartao.setBackgroundResource(R.drawable.cartao_indicador)
            }
            true -> {
                texto.setText(textoPerigo)
                texto.setTextColor(getColor(R.color.perigo_escuro))
                pintar(ponto, R.color.perigo)
                cartao.setBackgroundResource(R.drawable.cartao_indicador_perigo)
            }
        }
    }

    private fun atualizarComandoNaTela() {
        val atual = controle.comandoAtual()
        val continuo = controle.modoContinuo()
        textoMovimento.text = getString(R.string.movimento, rotuloDe(atual))
        val nomeContinuo = if (continuo == null) getString(R.string.nenhum) else rotuloDe(continuo)
        textoContinuo.text = getString(R.string.modo_continuo, nomeContinuo)
        for ((botao, comando) in botoes) {
            // activated = é o comando atual; selected = está no modo contínuo.
            botao.isActivated = comando != PARAR && comando == atual
            botao.isSelected = comando != PARAR && comando == continuo
        }
    }

    /**
     * Mostra o aviso quando o robô fica mais de 2 s sem mandar linha. Enquanto
     * isso, os indicadores mostram "Sem dados" e a distância "--" (os valores
     * antigos não valem mais).
     */
    private fun atualizarAvisoSemDados() {
        val parouDeMandar = estado == Estado.CONECTADO &&
            SystemClock.elapsedRealtime() - ultimaLinhaEm > SEM_DADOS_MS
        textoAviso.visibility = if (parouDeMandar) View.VISIBLE else View.GONE
        if (parouDeMandar != semDados) {
            semDados = parouDeMandar
            atualizarDistancia()
            atualizarPerigo()
        }
    }

    private fun pintar(view: View, corRes: Int) {
        view.backgroundTintList = ColorStateList.valueOf(getColor(corRes))
    }

    // ---------------- Painel "Mensagens do robô" ----------------

    private fun guardarMensagem(linha: String) {
        mensagens.addLast(linha)
        while (mensagens.size > MAX_MENSAGENS) mensagens.removeFirst()
        if (mensagensVisiveis) mostrarMensagens()
    }

    private fun mostrarMensagens() {
        textoMensagens.text = if (mensagens.isEmpty()) {
            getString(R.string.mensagens_vazio)
        } else {
            mensagens.joinToString("\n")
        }
    }

    private fun alternarMensagens() {
        mensagensVisiveis = !mensagensVisiveis
        textoMensagens.visibility = if (mensagensVisiveis) View.VISIBLE else View.GONE
        botaoMensagens.setText(
            if (mensagensVisiveis) R.string.mensagens_esconder else R.string.mensagens_mostrar,
        )
        if (mensagensVisiveis) mostrarMensagens()
    }

    // ---------------- Vibração ----------------

    private fun vibrar(duracaoMs: Long) {
        val vibrador: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        if (vibrador == null || !vibrador.hasVibrator()) return
        try {
            vibrador.vibrate(VibrationEffect.createOneShot(duracaoMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            // Sem vibração: tudo bem, o aviso aparece na tela.
        }
    }

    companion object {
        private const val BATIMENTO_MS = 300L
        private const val SEM_DADOS_MS = 2000L
        private const val VIBRACAO_MS = 200L
        private const val SEGUNDO_PLANO_MS = 15_000L
        private const val MAX_MENSAGENS = 30
        private const val PEDIDO_PERMISSAO = 1
        private const val PEDIDO_LIGAR_BT = 2
        private const val PREFERENCIAS = "robo"
        private const val CHAVE_MAC = "ultimo_mac"
        private const val CHAVE_JA_PEDIU = "ja_pediu_permissao"
    }
}
