package com.derich.estacacast.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.library.srt.SrtCamera2
import com.pedro.library.view.OpenGlView
import java.util.concurrent.Executors

class MainActivity : Activity(), ConnectChecker {

    companion object {
        private const val CAMERA_PERMISSION = 1001
        private const val STANDBY_DELAY_MS = 5_000L
    }

    private lateinit var preview: OpenGlView
    private lateinit var statusText: TextView
    private lateinit var serverText: TextView
    private lateinit var connectButton: Button
    private lateinit var discoverButton: Button
    private lateinit var standbyOverlay: View

    private val executor = Executors.newSingleThreadExecutor()
    private val standbyHandler = Handler(Looper.getMainLooper())
    private val standbyRunnable = Runnable { enterStandby() }

    private var camera: SrtCamera2? = null
    private var server: EstacaCastServer? = null
    private var streamingWanted = false
    private var prepared = false
    private var standbyActive = false
    private var originalBrightness = -1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        originalBrightness = window.attributes.screenBrightness
        buildUi()
        requestCameraIfNeeded()
        if (!handlePairIntent(intent)) discoverServer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairIntent(intent)
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        preview = OpenGlView(this)
        root.addView(
            preview,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(28, 22, 28, 22)
            setBackgroundColor(Color.argb(200, 5, 17, 35))
        }

        val title = TextView(this).apply {
            text = "ESTACA CAST CAMERA"
            setTextColor(Color.WHITE)
            textSize = 21f
        }
        panel.addView(title)

        statusText = TextView(this).apply {
            text = "Preparando câmera..."
            setTextColor(Color.rgb(120, 210, 255))
            textSize = 15f
            setPadding(0, 7, 0, 0)
        }
        panel.addView(statusText)

        serverText = TextView(this).apply {
            text = "Procurando Estaca Cast na rede..."
            setTextColor(Color.LTGRAY)
            textSize = 13f
            setPadding(0, 4, 0, 5)
        }
        panel.addView(serverText)

        val fallbackText = TextView(this).apply {
            text = "Se não encontrar: no PC use “QR de pareamento” e escaneie com a câmera/Google Lens do celular."
            setTextColor(Color.rgb(220, 185, 120))
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        panel.addView(fallbackText)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        discoverButton = Button(this).apply {
            text = "PROCURAR"
            setOnClickListener { discoverServer() }
        }
        buttons.addView(discoverButton)

        connectButton = Button(this).apply {
            text = "CONECTAR CÂMERA"
            isEnabled = false
            setOnClickListener {
                if (camera?.isStreaming == true) stopStreaming() else startStreaming()
            }
        }
        buttons.addView(connectButton)

        val switchButton = Button(this).apply {
            text = "TROCAR CÂMERA"
            setOnClickListener {
                runCatching { camera?.switchCamera() }
                    .onFailure { showStatus("Não foi possível trocar a câmera: ${it.message}", true) }
            }
        }
        buttons.addView(switchButton)

        panel.addView(buttons)

        val panelParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.START or Gravity.TOP,
        ).apply {
            leftMargin = 22
            topMargin = 22
        }
        root.addView(panel, panelParams)

        standbyOverlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            contentDescription = "Tela em standby. Toque para voltar."
            setOnClickListener { wakeFromStandby() }
        }
        root.addView(
            standbyOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        )

        setContentView(root)
    }

    private fun handlePairIntent(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "estacacast" || uri.host != "pair") return false

        val ip = uri.getQueryParameter("ip")?.trim().orEmpty()
        if (ip.isBlank()) {
            showStatus("QR inválido: IP não encontrado.", true)
            return false
        }

        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8890
        val streamId = uri.getQueryParameter("streamid")?.trim().orEmpty().ifBlank { "publish:camera" }
        val name = uri.getQueryParameter("name")?.trim().orEmpty().ifBlank { "Estaca Cast (QR)" }

        server = EstacaCastServer(
            name = name,
            ip = ip,
            srtPort = port,
            streamId = streamId,
        )
        serverText.text = "✓ Pareado por QR • $ip"
        connectButton.isEnabled = true
        showStatus("Pareamento concluído. Pronto para conectar.")
        return true
    }

    private fun requestCameraIfNeeded() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPreview()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startPreview()
        } else if (requestCode == CAMERA_PERMISSION) {
            showStatus("Permissão da câmera é necessária.", true)
        }
    }

    private fun startPreview() {
        if (camera != null) return
        try {
            camera = SrtCamera2(preview, this).also { c ->
                c.setVideoCodec(VideoCodec.H264)
                c.streamClient.setOnlyVideo(true)
                c.streamClient.setLatency(180)
                c.streamClient.setReTries(20)
            }
            preview.postDelayed({
                runCatching {
                    if (camera?.isOnPreview != true) camera?.startPreview()
                    showStatus(if (server != null) "Pronto para conectar." else "Câmera pronta.")
                }.onFailure {
                    showStatus("Erro ao abrir câmera: ${it.message}", true)
                }
            }, 350)
        } catch (e: Exception) {
            showStatus("Erro ao preparar câmera: ${e.message}", true)
        }
    }

    private fun discoverServer() {
        discoverButton.isEnabled = false
        serverText.text = "Procurando Estaca Cast na rede..."
        executor.execute {
            val found = Discovery.find(applicationContext)
            runOnUiThread {
                discoverButton.isEnabled = true
                server = found
                if (found != null) {
                    serverText.text = "✓ ${found.name} encontrado • ${found.ip}"
                    connectButton.isEnabled = true
                    showStatus("Pronto para conectar.")
                } else {
                    serverText.text = "PC não encontrado automaticamente. Use o QR de pareamento no Estaca Cast do PC."
                    connectButton.isEnabled = false
                    showStatus("Descoberta automática indisponível nesta rede.", true)
                }
            }
        }
    }

    private fun startStreaming() {
        val target = server ?: run {
            discoverServer()
            return
        }
        val c = camera ?: run {
            showStatus("A câmera ainda não está pronta.", true)
            return
        }

        try {
            if (c.isOnPreview != true) c.startPreview()

            if (!prepared) {
                prepared = c.prepareVideo(1920, 1080, 30, 4_500_000, 2, 0)
                if (!prepared) {
                    prepared = c.prepareVideo(1280, 720, 30, 3_000_000, 2, 0)
                }
            }

            if (!prepared) {
                showStatus("O celular não conseguiu preparar o encoder H.264.", true)
                return
            }

            val url = "srt://${target.ip}:${target.srtPort}" +
                "?mode=caller&latency=180&streamid=${target.streamId}&pkt_size=1316"

            streamingWanted = true
            showStatus("Conectando ao Estaca Cast...")
            c.startStream(url)
            connectButton.text = "PARAR"
        } catch (e: Exception) {
            streamingWanted = false
            disableStandby()
            showStatus("Falha ao iniciar: ${e.message}", true)
        }
    }

    private fun stopStreaming() {
        streamingWanted = false
        disableStandby()
        runCatching { camera?.stopStream() }
        connectButton.text = "CONECTAR CÂMERA"
        showStatus("Transmissão parada.")
    }

    private fun scheduleStandby() {
        standbyHandler.removeCallbacks(standbyRunnable)
        if (streamingWanted && camera?.isStreaming == true) {
            standbyHandler.postDelayed(standbyRunnable, STANDBY_DELAY_MS)
        }
    }

    private fun enterStandby() {
        if (!streamingWanted || camera?.isStreaming != true) return
        standbyActive = true
        standbyOverlay.visibility = View.VISIBLE

        val params = window.attributes
        params.screenBrightness = 0.01f
        window.attributes = params

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun wakeFromStandby() {
        standbyHandler.removeCallbacks(standbyRunnable)
        if (standbyActive) {
            standbyActive = false
            standbyOverlay.visibility = View.GONE

            val params = window.attributes
            params.screenBrightness = originalBrightness
            window.attributes = params

            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        scheduleStandby()
    }

    private fun disableStandby() {
        standbyHandler.removeCallbacks(standbyRunnable)
        standbyActive = false
        standbyOverlay.visibility = View.GONE

        val params = window.attributes
        params.screenBrightness = originalBrightness
        window.attributes = params

        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun showStatus(message: String, error: Boolean = false) {
        runOnUiThread {
            statusText.text = message
            statusText.setTextColor(
                if (error) Color.rgb(255, 130, 130) else Color.rgb(120, 230, 170)
            )
        }
    }

    override fun onConnectionStarted(url: String) {
        showStatus("Negociando conexão SRT...")
    }

    override fun onConnectionSuccess() {
        showStatus("● CÂMERA CONECTADA • standby AMOLED em 5 s")
        runOnUiThread {
            connectButton.text = "PARAR"
            scheduleStandby()
        }
    }

    override fun onConnectionFailed(reason: String) {
        showStatus("Conexão perdida. Tentando novamente...", true)
        if (streamingWanted) {
            val retried = runCatching {
                camera?.streamClient?.reTry(1200, reason, null) == true
            }.getOrDefault(false)
            if (!retried) {
                runOnUiThread {
                    disableStandby()
                    connectButton.text = "CONECTAR CÂMERA"
                    discoverServer()
                }
            }
        }
    }

    override fun onDisconnect() {
        if (streamingWanted) {
            showStatus("Reconectando...", true)
        } else {
            disableStandby()
            showStatus("Desconectado.")
        }
    }

    override fun onAuthError() {
        showStatus("Erro de autenticação.", true)
    }

    override fun onAuthSuccess() {
        showStatus("Autenticação concluída.")
    }

    override fun onDestroy() {
        streamingWanted = false
        standbyHandler.removeCallbacksAndMessages(null)
        runCatching {
            if (camera?.isStreaming == true) camera?.stopStream()
            if (camera?.isOnPreview == true) camera?.stopPreview()
        }
        executor.shutdownNow()
        super.onDestroy()
    }
}
