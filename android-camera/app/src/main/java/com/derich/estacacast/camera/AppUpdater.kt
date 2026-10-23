package com.derich.estacacast.camera

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object AppUpdater {

    data class UpdateInfo(
        val version: String,
        val apkUrl: String,
    )

    private const val RELEASE_API = "https://api.github.com/repos/gderich/estaca-cast/releases/latest"
    private const val APK_NAME = "EstacaCast-Camera.apk"
    private const val PREFS = "estaca_cast_updates"
    private const val PREF_DOWNLOAD_ID = "download_id"
    private const val PREF_APK_PATH = "apk_path"
    private const val PREF_VERSION = "download_version"

    private val executor = Executors.newSingleThreadExecutor()

    fun currentVersion(context: Context): String {
        return runCatching {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName.orEmpty()
        }.getOrDefault("")
    }

    fun check(context: Context, callback: (Result<UpdateInfo?>) -> Unit) {
        executor.execute {
            val result = runCatching {
                val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("User-Agent", "EstacaCast-Camera/${currentVersion(context)}")
                }
                try {
                    if (connection.responseCode !in 200..299) {
                        throw IllegalStateException("GitHub respondeu HTTP ${connection.responseCode}")
                    }
                    val json = connection.inputStream.bufferedReader().use { it.readText() }
                    val release = JSONObject(json)
                    val latest = release.optString("tag_name").removePrefix("v")
                    val assets = release.optJSONArray("assets")
                    var apkUrl = ""
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val item = assets.optJSONObject(i) ?: continue
                            if (item.optString("name") == APK_NAME) {
                                apkUrl = item.optString("browser_download_url")
                                break
                            }
                        }
                    }
                    if (latest.isBlank() || apkUrl.isBlank()) {
                        throw IllegalStateException("A Release mais recente não contém $APK_NAME.")
                    }
                    if (isNewer(latest, currentVersion(context))) UpdateInfo(latest, apkUrl) else null
                } finally {
                    connection.disconnect()
                }
            }
            (context as? Activity)?.runOnUiThread { callback(result) } ?: callback(result)
        }
    }

    fun download(activity: Activity, info: UpdateInfo): Long {
        val downloadsDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("Pasta de downloads indisponível.")
        downloadsDir.mkdirs()
        val file = File(downloadsDir, "EstacaCast-Camera-${info.version}.apk")
        if (file.exists()) file.delete()

        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("Estaca Cast Camera ${info.version}")
            setDescription("Baixando atualização…")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(file))
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }

        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = manager.enqueue(request)
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(PREF_DOWNLOAD_ID, id)
            .putString(PREF_APK_PATH, file.absolutePath)
            .putString(PREF_VERSION, info.version)
            .apply()
        return id
    }

    fun handleDownloadComplete(activity: Activity, downloadId: Long): Boolean {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(PREF_DOWNLOAD_ID, -1L) != downloadId) return false
        return installPending(activity)
    }

    fun installPending(activity: Activity): Boolean {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val downloadId = prefs.getLong(PREF_DOWNLOAD_ID, -1L)
        val path = prefs.getString(PREF_APK_PATH, null) ?: return false
        if (downloadId <= 0L) return false

        val manager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        val successful = cursor.use {
            if (!it.moveToFirst()) false
            else {
                val statusIndex = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
                statusIndex >= 0 && it.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
            }
        }
        if (!successful) return false

        val file = File(path)
        if (!file.exists()) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                )
            )
            return false
        }

        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.files",
            file,
        )
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(install)
        return true
    }

    fun downloadedVersion(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PREF_VERSION, "")
            .orEmpty()
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val b = current.split('.', '-', '+').mapNotNull { it.toIntOrNull() }
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return av > bv
        }
        return false
    }
}
