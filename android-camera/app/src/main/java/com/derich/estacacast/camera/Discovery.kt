package com.derich.estacacast.camera

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

internal data class EstacaCastServer(
    val name: String,
    val ip: String,
    val srtPort: Int,
    val streamId: String,
)

internal object Discovery {
    private const val PORT = 37020
    private const val MESSAGE = "ESTACACAST_DISCOVER_V1"

    fun find(context: Context, timeoutMs: Int = 1800): EstacaCastServer? {
        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.soTimeout = timeoutMs
            val data = MESSAGE.toByteArray(Charsets.UTF_8)

            val destinations = linkedSetOf<InetAddress>()
            destinations += InetAddress.getByName("255.255.255.255")
            getWifiBroadcast(context)?.let { destinations += it }

            destinations.forEach { address ->
                runCatching {
                    socket.send(DatagramPacket(data, data.size, address, PORT))
                }
            }

            val buffer = ByteArray(2048)
            val response = DatagramPacket(buffer, buffer.size)

            while (true) {
                try {
                    socket.receive(response)
                } catch (_: SocketTimeoutException) {
                    return null
                }

                val text = String(
                    response.data,
                    response.offset,
                    response.length,
                    Charsets.UTF_8,
                )
                val json = runCatching { JSONObject(text) }.getOrNull() ?: continue
                if (json.optString("type") != "estacacast") continue

                val responseIp = response.address?.hostAddress.orEmpty()
                val advertisedIp = json.optString("ip").trim()

                return EstacaCastServer(
                    name = json.optString("name", "Estaca Cast"),
                    ip = advertisedIp.ifBlank { responseIp },
                    srtPort = json.optInt("srtPort", 8890),
                    streamId = json.optString("streamId", "publish:camera"),
                )
            }
        } finally {
            socket.close()
        }
    }

    private fun getWifiBroadcast(context: Context): InetAddress? {
        return runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val dhcp = wifi.dhcpInfo ?: return@runCatching null
            val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
            val bytes = byteArrayOf(
                (broadcast and 0xff).toByte(),
                (broadcast shr 8 and 0xff).toByte(),
                (broadcast shr 16 and 0xff).toByte(),
                (broadcast shr 24 and 0xff).toByte(),
            )
            InetAddress.getByAddress(bytes)
        }.getOrNull()
    }
}
