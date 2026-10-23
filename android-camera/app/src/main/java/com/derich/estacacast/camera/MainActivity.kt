package com.derich.estacacast.camera

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
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
import com.google.zxing.integration.android.IntentIntegrator
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.srt.SrtCamera2
import com.pedro.library.view.OpenGlView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val CAMERA_PERMISSION = 1001
        private const val NOTIFICATION_PERMISSION = 1002
        private const val DEVICE_ADMIN_REQUEST = 1003
        private const val STANDBY_DELAY_MS = 5_000L
        private const val PREFS = "estaca_cast_camera"
        private const val PREF_AUTO_SCREEN_OFF = "auto_screen_off"
    }

    private lateinit var preview: OpenGlView
    private lateinit var statusText: TextView
    private lateinit var serverText: TextView
    private lateinit var connectButton: Button
    private lateinit var discoverButton: Button
    private lateinit var qrButton: Button
    private lateinit var switchButton: Button
    private lateinit var menuButton: Button
    private lateinit var standbyOverlay: View

    private val executor = Executors.newSingleThreadExecutor()
    private val standbyHandler = Handler(Looper.getMainLooper())
    private val standbyRunnable = Runnable { enterStandby() }

    private var previewCamera: SrtCamera2? = null
    private var server: EstacaCastServer? = null
    private var streamingWanted = false
    private var standbyActive = false
    private var originalBrightness = -1f
    private var qrScannerActive = false
    private var useFrontCamera = false
    private var updateInfo: AppUpdater.UpdateInfo? = null
    private var awaitingInstallPermission = false
    private var receiversRegistered = false

    private val previewChecker = object : ConnectChecker {
        override fun onConnectionStarted(url: String) = Unit
        override fun onConnectionSuccess() = Unit
        override fun onConnectionFailed(reason: String) = Unit
        override fun onDisconnect() = Unit
        override fun onAuthError() = Unit
        override fun onAuthSuccess() = Unit
    }

    private val streamStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CameraStreamService.ACTION_STATE) return
            val state = intent.getStringExtra(CameraStreamService.EXTRA_STATE).orEmpty()
            val message = intent.getStringExtra(CameraStreamService.EXTRA_MESSAGE).orEmpty()
            applyServiceState(state, message)
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            val launched = AppUpdater.handleDownloadComplete(this@MainActivity, id)
            if (launched) {
                showStatus("Atualização baixada. Confirme a instalação do Android.")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                awaitingInstallPermission = true
                showStatus("Autorize o Estaca Cast a instalar atualizações e volte ao app.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        originalBrightness = window.attributes.screenBrightness
        buildUi()

        if (CameraStreamService.isActive) {
            syncServiceState()
        } else {
            requestCameraIfNeeded()
        }

        if (!handlePairIntent(intent) && !CameraStreamService.isActive) discoverServer()
        Handler(Looper.getMainLooper()).postDelayed({ checkForUpdates(silent = true) }, 1_500)
    }

    override fun onStart() {
        super.onStart()
        registerAppReceivers()
        if (CameraStreamService.isActive) {
            syncServiceState()
        } else if (previewCamera == null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPreview()
        }
    }

    override fun onStop() {
        unregisterAppReceivers()
        if (!CameraStreamService.isActive && !qrScannerActive) releasePreviewCamera()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (awaitingInstallPermission && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls())) {
            awaitingInstallPermission = false
            AppUpdater.installPending(this)
        }
        refreshMenuLabel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairIntent(intent)
    }

    private fun registerAppReceivers() {
        if (receiversRegistered) return
        val streamFilter = IntentFilter(CameraStreamService.ACTION_STATE)
        val downloadFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(streamStateReceiver, streamFilter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(downloadReceiver, downloadFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(streamStateReceiver, streamFilter)
            @Suppress("DEPRECATION")
            registerReceiver(downloadReceiver, downloadFilter)
        }
        receiversRegistered = true
    }

    private fun unregisterAppReceivers() {
        if (!receiversRegistered) return
        runCatching { unregisterReceiver(streamStateReceiver) }
        runCatching { unregisterReceiver(downloadReceiver) }
        receiversRegistered = false
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

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
            text = "Se não encontrar automaticamente, toque em LER QR e aponte para o QR de pareamento exibido no PC."
            setTextColor(Color.rgb(220, 185, 120))
            textSize = 12f
            setPadding(0, 0, 0, 12)
        }
        panel.addView(fallbackText)

        val mainButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        discoverButton = Button(this).apply {
            text = "PROCURAR"
            setOnClickListener { discoverServer() }
        }
        mainButtons.addView(discoverButton)

        qrButton = Button(this).apply {
            text = "LER QR"
            setOnClickListener { startQrScanner() }
        }
        mainButtons.addView(qrButton)

        connectButton = Button(this).apply {
            text = "CONECTAR CÂMERA"
            isEnabled = false
            setOnClickListener {
                if (CameraStreamService.isActive || streamingWanted) stopStreaming() else startStreaming()
            }
        }
        mainButtons.addView(connectButton)
        panel.addView(mainButtons)

        val utilityButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        switchButton = Button(this).apply {
            text = "TROCAR CÂMERA"
            setOnClickListener { switchCamera() }
        }
        utilityButtons.addView(switchButton)

        menuButton = Button(this).apply {
            text = "MENU"
            setOnClickListener { showMenu() }
        }
        utilityButtons.addView(menuButton)
        panel.addView(utilityButtons)

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
        return applyPairUri(uri)
    }

    private fun applyPairUri(uri: Uri): Boolean {
        if (uri.scheme != "estacacast" || uri.host != "pair") return false
        if (CameraStreamService.isActive) {
            showStatus("Pareamento indisponível enquanto a câmera está transmitindo.", true)
            return false
        }

        val ip = uri.getQueryParameter("ip")?.trim().orEmpty()
        if (ip.isBlank()) {
            showStatus("QR inválido: IP não encontrado.", true)
            return false
        }

        val port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8890
        val streamId = uri.getQueryParameter("streamid")?.trim().orEmpty().ifBlank { "publish:camera" }
        val name = uri.getQueryParameter("name")?.trim().orEmpty().ifBlank { "Estaca Cast (QR)" }

        server = EstacaCastServer(name = name, ip = ip, srtPort = port, streamId = streamId)
        serverText.text = "✓ Pareado por QR • $ip"
        connectButton.isEnabled = true
        showStatus("QR reconhecido. Pronto para conectar.")
        return true
    }

    private fun startQrScanner() {
        if (CameraStreamService.isActive || streamingWanted) {
            showStatus("O leitor QR fica disponível somente antes de conectar a câmera.", true)
            return
        }
        if (qrScannerActive) return

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraIfNeeded()
            showStatus("Autorize a câmera e toque novamente em LER QR.", true)
            return
        }

        standbyHandler.removeCallbacks(standbyRunnable)
        releasePreviewCamera()

        qrScannerActive = true
        qrButton.isEnabled = false
        showStatus("Aponte para o QR de pareamento do Estaca Cast.")

        IntentIntegrator(this).apply {
            setDesiredBarcodeFormats(listOf("QR_CODE"))
            setPrompt("Aponte para o QR de pareamento do Estaca Cast")
            setBeepEnabled(false)
            setOrientationLocked(false)
            initiateScan()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (scanResult != null) {
            qrScannerActive = false
            qrButton.isEnabled = !CameraStreamService.isActive

            val contents = scanResult.contents
            if (contents.isNullOrBlank()) {
                showStatus("Leitura do QR cancelada.")
            } else {
                val uri = runCatching { Uri.parse(contents.trim()) }.getOrNull()
                if (uri == null || !applyPairUri(uri)) {
                    showStatus("Este QR não é um pareamento válido do Estaca Cast.", true)
                }
            }
            resumePreviewAfterQr()
            return
        }

        if (requestCode == DEVICE_ADMIN_REQUEST) {
            val enabled = isDeviceAdminActive()
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_AUTO_SCREEN_OFF, enabled)
                .apply()
            if (enabled) {
                showStatus("Desligamento automático ativado. Ao conectar, a tela bloqueará em 5 s.")
            } else {
                showStatus("Desligamento automático não foi ativado.", true)
            }
            refreshMenuLabel()
            return
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun resumePreviewAfterQr() {
        if (CameraStreamService.isActive) return
        preview.postDelayed({ startPreview() }, 300)
    }

    private fun requestCameraIfNeeded() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startPreview()
            requestNotificationPermissionIfNeeded()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startPreview()
                requestNotificationPermissionIfNeeded()
            } else {
                showStatus("Permissão da câmera é necessária.", true)
            }
        }
    }

    private fun startPreview() {
        if (CameraStreamService.isActive || previewCamera != null) return
        try {
            previewCamera = SrtCamera2(preview, previewChecker).also { c ->
                c.setVideoCodec(VideoCodec.H264)
                c.streamClient.setOnlyVideo(true)
                c.streamClient.setLatency(180)
                c.streamClient.setReTries(20)
            }
            preview.postDelayed({
                runCatching {
                    previewCamera?.startPreview(
                        if (useFrontCamera) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK
                    )
                    showStatus(if (server != null) "Pronto para conectar." else "Câmera pronta.")
                }.onFailure {
                    showStatus("Erro ao abrir câmera: ${it.message}", true)
                }
            }, 350)
        } catch (e: Exception) {
            showStatus("Erro ao preparar câmera: ${e.message}", true)
        }
    }

    private fun releasePreviewCamera() {
        runCatching { previewCamera?.stopCamera() }
        previewCamera = null
    }

    private fun discoverServer() {
        if (CameraStreamService.isActive || streamingWanted) return
        discoverButton.isEnabled = false
        serverText.text = "Procurando Estaca Cast na rede..."
        executor.execute {
            val found = Discovery.find(applicationContext)
            runOnUiThread {
                discoverButton.isEnabled = !CameraStreamService.isActive
                server = found
                if (found != null) {
                    serverText.text = "✓ ${found.name} encontrado • ${found.ip}"
                    connectButton.isEnabled = true
                    showStatus("Pronto para conectar.")
                } else {
                    serverText.text = "PC não encontrado automaticamente. Use LER QR e o QR de pareamento no Estaca Cast do PC."
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
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraIfNeeded()
            return
        }

        try {
            releasePreviewCamera()
            disableStandby()
            streamingWanted = true
            qrButton.isEnabled = false
            discoverButton.isEnabled = false
            connectButton.text = "PARAR"
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            showStatus("Transferindo a câmera para o serviço em segundo plano…")

            CameraStreamService.start(
                this,
                target,
                frontCamera = useFrontCamera,
                autoLock = isAutoScreenOffEnabled(),
            )
        } catch (e: Exception) {
            streamingWanted = false
            qrButton.isEnabled = true
            discoverButton.isEnabled = true
            connectButton.text = "CONECTAR CÂMERA"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startPreview()
            showStatus("Falha ao iniciar serviço da câmera: ${e.message}", true)
        }
    }

    private fun stopStreaming() {
        streamingWanted = false
        disableStandby()
        connectButton.text = "PARANDO…"
        CameraStreamService.stop(this)
    }

    private fun switchCamera() {
        if (CameraStreamService.isActive) {
            CameraStreamService.switchCamera(this)
            useFrontCamera = !useFrontCamera
            showStatus(if (useFrontCamera) "Câmera frontal selecionada." else "Câmera traseira selecionada.")
        } else {
            runCatching { previewCamera?.switchCamera() }
                .onSuccess {
                    useFrontCamera = !useFrontCamera
                    showStatus(if (useFrontCamera) "Câmera frontal selecionada." else "Câmera traseira selecionada.")
                }
                .onFailure { showStatus("Não foi possível trocar a câmera: ${it.message}", true) }
        }
    }

    private fun syncServiceState() {
        if (!CameraStreamService.isActive) return
        applyServiceState(CameraStreamService.state, CameraStreamService.stateMessage)
    }

    private fun applyServiceState(state: String, message: String) {
        runOnUiThread {
            when (state) {
                "connecting", "reconnecting" -> {
                    streamingWanted = true
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    connectButton.text = "PARAR"
                    connectButton.isEnabled = true
                    qrButton.isEnabled = false
                    discoverButton.isEnabled = false
                    showStatus(message, state == "reconnecting")
                }
                "connected" -> {
                    streamingWanted = true
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    connectButton.text = "PARAR"
                    connectButton.isEnabled = true
                    qrButton.isEnabled = false
                    discoverButton.isEnabled = false
                    showStatus(message)
                    if (!isAutoScreenOffEnabled()) scheduleStandby()
                }
                "error" -> {
                    showStatus(message, true)
                }
                else -> {
                    streamingWanted = false
                    disableStandby()
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    connectButton.text = "CONECTAR CÂMERA"
                    connectButton.isEnabled = server != null
                    qrButton.isEnabled = true
                    discoverButton.isEnabled = true
                    showStatus(message)
                    preview.postDelayed({ startPreview() }, 350)
                }
            }
        }
    }

    private fun scheduleStandby() {
        standbyHandler.removeCallbacks(standbyRunnable)
        if (CameraStreamService.isActive && !isAutoScreenOffEnabled()) {
            standbyHandler.postDelayed(standbyRunnable, STANDBY_DELAY_MS)
        }
    }

    private fun enterStandby() {
        if (!CameraStreamService.isActive || isAutoScreenOffEnabled()) return
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

    private fun showMenu() {
        val screenOption = if (isAutoScreenOffEnabled()) {
            "Desativar desligamento automático da tela"
        } else {
            "Ativar desligamento automático da tela"
        }
        val options = arrayOf(
            "Verificar atualização",
            screenOption,
            "Sobre",
        )
        AlertDialog.Builder(this)
            .setTitle("Estaca Cast Camera")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkForUpdates(silent = false)
                    1 -> toggleAutoScreenOff()
                    2 -> showAbout()
                }
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

    private fun showAbout() {
        val version = AppUpdater.currentVersion(this)
        val background = if (CameraStreamService.isActive) "Ativa" else "Parada"
        val screenOff = if (isAutoScreenOffEnabled()) "Ativado" else "Desativado"
        AlertDialog.Builder(this)
            .setTitle("Estaca Cast Camera $version")
            .setMessage("Transmissão em segundo plano: $background\nDesligamento automático: $screenOff\n\nQuando conectado, você pode apertar Home ou apagar a tela sem interromper a câmera.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun toggleAutoScreenOff() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (isAutoScreenOffEnabled()) {
            prefs.edit().putBoolean(PREF_AUTO_SCREEN_OFF, false).apply()
            showStatus("Desligamento automático desativado. O modo AMOLED continua disponível.")
            refreshMenuLabel()
            return
        }

        if (isDeviceAdminActive()) {
            prefs.edit().putBoolean(PREF_AUTO_SCREEN_OFF, true).apply()
            showStatus("Desligamento automático ativado. A tela bloqueará 5 s após conectar.")
            refreshMenuLabel()
            return
        }

        val admin = ComponentName(this, ScreenOffAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "O Estaca Cast usa somente a permissão de bloquear a tela para apagá-la automaticamente 5 segundos após a câmera conectar. A transmissão continua pelo serviço em primeiro plano.",
            )
        }
        startActivityForResult(intent, DEVICE_ADMIN_REQUEST)
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, ScreenOffAdminReceiver::class.java))
    }

    private fun isAutoScreenOffEnabled(): Boolean {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(PREF_AUTO_SCREEN_OFF, false) && isDeviceAdminActive()
    }

    private fun checkForUpdates(silent: Boolean) {
        if (!silent) showStatus("Verificando atualização…")
        AppUpdater.check(this) { result ->
            result.onSuccess { info ->
                updateInfo = info
                refreshMenuLabel()
                if (info == null) {
                    if (!silent) showStatus("Você já está na versão mais recente.")
                    return@onSuccess
                }
                if (silent && CameraStreamService.isActive) return@onSuccess
                AlertDialog.Builder(this)
                    .setTitle("Atualização disponível")
                    .setMessage("Estaca Cast Camera ${info.version} está disponível. Deseja baixar agora?")
                    .setPositiveButton("Atualizar") { _, _ -> beginUpdate(info) }
                    .setNegativeButton("Depois", null)
                    .show()
            }.onFailure {
                if (!silent) showStatus("Não foi possível verificar atualização: ${it.message}", true)
            }
        }
    }

    private fun beginUpdate(info: AppUpdater.UpdateInfo) {
        runCatching { AppUpdater.download(this, info) }
            .onSuccess {
                showStatus("Baixando Estaca Cast Camera ${info.version}…")
                AlertDialog.Builder(this)
                    .setTitle("Download iniciado")
                    .setMessage("O APK será baixado automaticamente. Quando terminar, o Android abrirá a instalação. Na primeira vez, talvez seja necessário autorizar este app a instalar APKs.")
                    .setPositiveButton("OK", null)
                    .show()
            }
            .onFailure { showStatus("Falha ao iniciar atualização: ${it.message}", true) }
    }

    private fun refreshMenuLabel() {
        if (!::menuButton.isInitialized) return
        menuButton.text = if (updateInfo != null) "MENU • UPDATE" else "MENU"
    }

    private fun showStatus(message: String, error: Boolean = false) {
        runOnUiThread {
            statusText.text = message
            statusText.setTextColor(
                if (error) Color.rgb(255, 130, 130) else Color.rgb(120, 230, 170)
            )
        }
    }

    override fun onDestroy() {
        standbyHandler.removeCallbacksAndMessages(null)
        releasePreviewCamera()
        executor.shutdownNow()
        super.onDestroy()
    }
}
