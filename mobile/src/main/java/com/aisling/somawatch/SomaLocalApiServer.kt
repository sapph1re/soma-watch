package com.aisling.somawatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Lightweight local-LAN read-only HTTP server on port 8787 so Claude Code
 * (or any local AI health coach) can fetch live JSON / Markdown health exports:
 *   GET /api/health     -> full JSON telemetry & sleep/HRV/HRR/workout history
 *   GET /api/summary.md -> LLM-friendly Markdown health digest
 */
object SomaLocalApiServer {
    private const val TAG = "SomaLocalApi"
    const val PORT = 8787

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _runningUrl = MutableStateFlow<String?>(null)
    val runningUrl: StateFlow<String?> = _runningUrl

    fun start(context: Context) {
        if (acceptJob?.isActive == true) return
        val appCtx = context.applicationContext
        acceptJob = scope.launch {
            try {
                val ss = ServerSocket(PORT)
                serverSocket = ss
                val ip = getLocalWifiIp() ?: "127.0.0.1"
                _runningUrl.value = "http://$ip:$PORT/api/health"
                Log.i(TAG, "Local Health API started at http://$ip:$PORT")

                while (isActive && !ss.isClosed) {
                    val client = runCatching { ss.accept() }.getOrNull() ?: break
                    launch { handleClient(appCtx, client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Local API server error", e)
                _runningUrl.value = null
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob?.cancel()
        acceptJob = null
        _runningUrl.value = null
    }

    private fun handleClient(context: Context, socket: Socket) {
        socket.use { s ->
            runCatching {
                s.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val path = requestLine.split(" ").getOrNull(1) ?: "/"

                val db = SomaHealthDb.get(context)
                val (contentType, body) = when {
                    path.startsWith("/api/summary") -> {
                        "text/markdown; charset=utf-8" to db.buildClaudeMarkdownDigest(context)
                    }
                    path.startsWith("/api/health") || path == "/" -> {
                        "application/json; charset=utf-8" to db.buildFullExportJson(context).toString(2)
                    }
                    else -> {
                        "application/json; charset=utf-8" to """{"endpoints":["/api/health","/api/summary.md"]}"""
                    }
                }

                val bodyBytes = body.toByteArray(Charsets.UTF_8)
                val headers = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: $contentType\r\n")
                    append("Content-Length: ${bodyBytes.size}\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Connection: close\r\n\r\n")
                }
                val out = s.getOutputStream()
                out.write(headers.toByteArray(Charsets.UTF_8))
                out.write(bodyBytes)
                out.flush()
            }
        }
    }

    private fun getLocalWifiIp(): String? {
        return runCatching {
            val ifaces = NetworkInterface.getNetworkInterfaces()
            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }
}
