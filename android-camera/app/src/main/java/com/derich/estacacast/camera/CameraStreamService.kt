package com.derich.estacacast.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.srt.SrtCamera2

class CameraStreamService : Service(), ConnectChecker {

    companion object {
        const val ACTION_STATE = "com.derich.estacacast.camera.STREAM_STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"

        private const val ACTION_START = "com.derich.estacacast.camera.START_STREAM"
        private const val ACTION_STOP = "com.derich.estacacast.camera.STOP_STREAM"
        private const val ACTION_SWITCH = "com.derich.estacacast.camera.SWITCH_CAMERA"
        private const val EXTRA_IP = "ip"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_STREAM_ID = "stream_id"
        private const val EXTRA_FRONT_CAMERA = "front_camera"
        private const val EXTRA_AUTO_LOCK = "auto_lock"

        private const val CHANNEL_ID = "estaca_cast_camera_stream"
        private const val NOTIFICATION_ID = 4307
        private const val AUTO_LOCK_DELAY_MS = 5_000L

        // Perfil padrão voltado para Wi-Fi real de capela/hotspot.
        // 180 ms + 1080p/4,5 Mbps se mostrou agressivo e pode deixar pacotes
        // chegarem tarde, causando macroblocos/quadros H.264 corrompidos.
        private const val SRT_LATENCY_MS = 450
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
        private const val VIDEO_BITRATE = 3_000_000
        private const val VIDEO_KEYFRAME_SECONDS = 1

        @Volatile var isActive: Boolean = false
            private set
        @Volatile var state: String = "idle"
            private set
        @Volatile var stateMessage: String = "Pronto."
            private set

        internal fun start(context: Context, target: EstacaCastServer, frontCamera: Boolean, autoLock: Boolean) {
            val intent = Intent(context, CameraStreamService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_IP, target.ip)
                putExtra(EXTRA_PORT, target.srtPort)
                putExtra(EXTRA_STREAM_ID, target.streamId)
                putExtra(EXTRA_FRONT_CAMERA, frontCamera)
                putExtra(EXTRA_AUTO_LOCK, autoLock)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CameraStreamService::class.java).setAction(ACTION_STOP))
        }

        fun switchCamera(context: Context) {
            context.startService(Intent(context, CameraStreamService::class.java).setAction(ACTION_SWITCH))
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var camera: SrtCamera2? = null
    private var streamingWanted = false
    private var autoLockRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        isActive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSession("Transmissão encerrada.")
                stopSelf()
            }
            ACTION_SWITCH -> {
                runCatching { camera?.switchCamera() }
                    .onFailure { publishState("error", "Não foi possível trocar a câmera: ${it.message}") }
            }
            ACTION_START -> startSession(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSession(intent: Intent) {
        val ip = intent.getStringExtra(EXTRA_IP).orEmpty().trim()
        val port = intent.getIntExtra(EXTRA_PORT, 8890)
        val streamId = intent.getStringExtra(EXTRA_STREAM_ID).orEmpty().ifBlank { "publish:camera" }
        val frontCamera = intent.getBooleanExtra(EXTRA_FRONT_CAMERA, false)
        autoLockRequested = intent.getBooleanExtra(EXTRA_AUTO_LOCK, false)

        if (ip.isBlank()) {
            publishState("error", "Destino SRT inválido: IP vazio.")
            stopSelf()
            return
        }

        if (streamingWanted || camera?.isStreaming == true) {
            stopSession("Reiniciando transmissão…", stopForeground = false)
        }

        streamingWanted = true
        startAsForeground("Preparando câmera…")
        acquirePerformanceLocks()
        publishState("connecting", "Preparando câmera em segundo plano…")

        try {
            val c = SrtCamera2(applicationContext, this).also {
                it.setVideoCodec(VideoCodec.H264)
                it.streamClient.setOnlyVideo(true)
                it.streamClient.setLatency(SRT_LATENCY_MS)
                it.streamClient.setReTries(20)
                it.startPreview(if (frontCamera) CameraHelper.Facing.FRONT else CameraHelper.Facing.BACK)
            }
            camera = c

            var prepared = c.prepareVideo(
                VIDEO_WIDTH,
                VIDEO_HEIGHT,
                VIDEO_FPS,
                VIDEO_BITRATE,
                VIDEO_KEYFRAME_SECONDS,
                0,
            )
            if (!prepared) {
                prepared = c.prepareVideo(960, 540, 30, 2_000_000, VIDEO_KEYFRAME_SECONDS, 0)
            }
            if (!prepared) throw IllegalStateException("O encoder H.264 não pôde ser preparado.")

            val url = "srt://$ip:$port?mode=caller&latency=$SRT_LATENCY_MS&streamid=$streamId&pkt_size=1316"
            publishState("connecting", "Conectando ao Estaca Cast…")
            c.startStream(url)
        } catch (e: Exception) {
            publishState("error", "Falha ao iniciar câmera: ${e.message}")
            stopSession("Falha ao iniciar câmera.")
            stopSelf()
        }
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopPending = PendingIntent.getService(
            this,
            2,
            Intent(this, CameraStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_icon)
            .setContentTitle("Estaca Cast Camera")
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "PARAR", stopPending)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Transmissão da câmera",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Mantém a câmera do Estaca Cast transmitindo em segundo plano."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquirePerformanceLocks() {
        runCatching {
            if (wakeLock?.isHeld != true) {
                wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EstacaCastCamera:Stream")
                    .apply { setReferenceCounted(false); acquire() }
            }
        }
        runCatching {
            if (wifiLock?.isHeld != true) {
                @Suppress("DEPRECATION")
                wifiLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EstacaCastCamera:Wifi")
                    .apply { setReferenceCounted(false); acquire() }
            }
        }
    }

    private fun releasePerformanceLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun scheduleScreenLock() {
        mainHandler.removeCallbacksAndMessages(null)
        if (!autoLockRequested) return
        mainHandler.postDelayed({
            if (!streamingWanted || camera?.isStreaming != true) return@postDelayed
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(this, ScreenOffAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                runCatching { dpm.lockNow() }
            }
        }, AUTO_LOCK_DELAY_MS)
    }

    private fun stopSession(message: String, stopForeground: Boolean = true) {
        streamingWanted = false
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { if (camera?.isStreaming == true) camera?.stopStream() }
        runCatching { camera?.stopCamera() }
        camera = null
        releasePerformanceLocks()
        if (stopForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        publishState("idle", message)
    }

    private fun publishState(newState: String, message: String) {
        state = newState
        stateMessage = message
        sendBroadcast(Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, newState)
            putExtra(EXTRA_MESSAGE, message)
        })
    }

    override fun onConnectionStarted(url: String) {
        publishState("connecting", "Negociando conexão SRT…")
        updateNotification("Conectando…")
    }

    override fun onConnectionSuccess() {
        publishState("connected", "● CÂMERA CONECTADA • 720p30 estável • pode apagar a tela")
        updateNotification("Transmitindo em 720p30 • tela pode ficar desligada")
        scheduleScreenLock()
    }

    override fun onConnectionFailed(reason: String) {
        if (!streamingWanted) return
        publishState("reconnecting", "Conexão perdida. Reconectando…")
        updateNotification("Reconectando…")
        val retried = runCatching {
            camera?.streamClient?.reTry(1200, reason, null) == true
        }.getOrDefault(false)
        if (!retried) {
            publishState("error", "Não foi possível reconectar: $reason")
            stopSession("Transmissão interrompida.")
            stopSelf()
        }
    }

    override fun onDisconnect() {
        if (streamingWanted) {
            publishState("reconnecting", "Reconectando…")
            updateNotification("Reconectando…")
        } else {
            publishState("idle", "Desconectado.")
        }
    }

    override fun onAuthError() {
        publishState("error", "Erro de autenticação SRT.")
    }

    override fun onAuthSuccess() {
        publishState("connected", "Autenticação concluída.")
    }

    override fun onDestroy() {
        stopSession("Transmissão encerrada.")
        isActive = false
        super.onDestroy()
    }
}
