package com.example.adbproxy

import android.util.Base64
import android.util.Log
import java.io.InputStream
import java.net.*
import kotlin.concurrent.thread

class HttpToSocks(
    private val host: String, private val port: Int,
    user: String, pass: String,
    private val protectSocket: (Socket) -> Unit
) {
    private val tag = "AdbProxy"
    private val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    val localPort: Int get() = server.localPort
    private val auth = Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)

    fun start() = thread(isDaemon = true) {
        Log.d(tag, "relay: listening on 127.0.0.1:$localPort -> $host:$port")
        while (!server.isClosed) {
            try {
                val c = server.accept()
                thread(isDaemon = true) { handle(c) }
            } catch (e: Exception) { break }
        }
    }

    fun stop() { try { server.close() } catch (_: Exception) {} }

    private fun readFully(i: InputStream, b: ByteArray) {
        var n = 0
        while (n < b.size) {
            val r = i.read(b, n, b.size - n)
            if (r < 0) throw java.io.EOFException()
            n += r
        }
    }

    private fun handle(c: Socket) {
        var up: Socket? = null
        try {
            c.tcpNoDelay = true
            val ci = c.getInputStream(); val co = c.getOutputStream()

            if (ci.read() != 5) return
            readFully(ci, ByteArray(ci.read()))
            co.write(byteArrayOf(5, 0))

            val h = ByteArray(4); readFully(ci, h)
            if (h[1].toInt() != 1) {
                // UDP khong ho tro qua HTTP proxy
                co.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0)); return
            }
            val dest = when (h[3].toInt()) {
                1 -> ByteArray(4).also { readFully(ci, it) }
                    .let { InetAddress.getByAddress(it).hostAddress }
                3 -> ByteArray(ci.read()).also { readFully(ci, it) }.let { String(it) }
                4 -> ByteArray(16).also { readFully(ci, it) }
                    .let { "[" + InetAddress.getByAddress(it).hostAddress + "]" }
                else -> return
            }
            val pb = ByteArray(2); readFully(ci, pb)
            val dport = ((pb[0].toInt() and 0xff) shl 8) or (pb[1].toInt() and 0xff)
            Log.d(tag, "relay: CONNECT $dest:$dport")

            // Tao socket, gan vao mang that TRUOC khi connect
            val s = Socket()
            up = s
            protectSocket(s)
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 10000)
            Log.d(tag, "relay: connected to proxy")

            val uo = s.getOutputStream(); val ui = s.getInputStream()
            uo.write(("CONNECT $dest:$dport HTTP/1.1\r\n" +
                    "Host: $dest:$dport\r\n" +
                    "Proxy-Authorization: Basic $auth\r\n" +
                    "Proxy-Connection: keep-alive\r\n\r\n").toByteArray())
            uo.flush()

            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val b = ui.read(); if (b < 0 || sb.length > 8192) throw java.io.EOFException()
                sb.append(b.toChar())
            }
            val statusLine = sb.lineSequence().first()
            Log.d(tag, "relay: proxy replied: $statusLine")
            val code = statusLine.split(" ").getOrNull(1)
            if (code != "200") {
                co.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0)); return
            }
            co.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))

            thread(isDaemon = true) {
                try { ui.copyTo(co) } catch (_: Exception) {}
                try { c.close(); s.close() } catch (_: Exception) {}
            }
            try { ci.copyTo(uo) } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(tag, "relay error: $e")
        } finally {
            try { c.close() } catch (_: Exception) {}
            try { up?.close() } catch (_: Exception) {}
        }
    }
}