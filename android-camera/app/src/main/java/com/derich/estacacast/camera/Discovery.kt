package com.derich.estacacast.camera

import android.content.Context
import android.net.wifi.WifiManager
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
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

    fun find(context: Context, timeoutMs: Int = 3000): EstacaCastServer? {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val multicastLock = runCatching {
            wifi?.createMulticastLock("EstacaCastDiscovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.soTimeout = timeoutMs
            val data = MESSAGE.toByteArray(Charsets.UTF_8)

            // 1) Broadcast tradicional: rápido em redes Wi-Fi comuns.
            val broadcastDestinations = linkedSetOf<InetAddress>()
            broadcastDestinations += InetAddress.getByName("255.255.255.255")
            getWifiBroadcast(context)?.let { broadcastDestinations += it }
            getInterfaceBroadcasts().forEach { broadcastDestinations += it }

            broadcastDestinations.forEach { address ->
                runCatching {
                    socket.send(DatagramPacket(data, data.size, address, PORT))
                }
            }

            // 2) Fallback unicast: tenta cada host da sub-rede local.
            // Isso contorna hotspots/APs que não encaminham broadcast entre clientes.
            getUnicastCandidates().forEach { address ->
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
            runCatching { socket.close() }
            runCatching { multicastLock?.release() }
        }
    }

    private fun getInterfaceBroadcasts(): Set<InetAddress> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses.orEmpty() }
                .mapNotNull { it.broadcast }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun getUnicastCandidates(): Set<InetAddress> {
        return runCatching {
            val result = linkedSetOf<InetAddress>()

            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses.orEmpty() }
                .forEach { iface ->
                    val address = iface.address as? Inet4Address ?: return@forEach
                    val bytes = address.address
                    val prefix = iface.networkPrefixLength.toInt()

                    // Para redes muito grandes, limita a busca ao /24 do aparelho.
                    val effectivePrefix = if (prefix in 24..30) prefix else 24
                    val ipInt = ((bytes[0].toInt() and 0xff) shl 24) or
                        ((bytes[1].toInt() and 0xff) shl 16) or
                        ((bytes[2].toInt() and 0xff) shl 8) or
                        (bytes[3].toInt() and 0xff)
                    val mask = if (effectivePrefix == 0) 0 else (-1 shl (32 - effectivePrefix))
                    val network = ipInt and mask
                    val broadcast = network or mask.inv()

                    for (candidate in (network + 1) until broadcast) {
                        if (candidate == ipInt) continue
                        val candidateBytes = byteArrayOf(
                            ((candidate ushr 24) and 0xff).toByte(),
                            ((candidate ushr 16) and 0xff).toByte(),
                            ((candidate ushr 8) and 0xff).toByte(),
                            (candidate and 0xff).toByte(),
                        )
                        result += InetAddress.getByAddress(candidateBytes)
                        if (result.size >= 512) return@runCatching result
                    }
                }

            result
        }.getOrDefault(emptySet())
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
