package com.kimi.proxyvpn

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Konfigurasi proxy dengan host yang sudah di-resolve ke alamat IP. */
class ResolvedProxy(val cfg: ProxyConfig, val address: InetAddress)

/** Klien SOCKS5 / HTTP CONNECT minimal. */
object ProxyClient {
    private const val CONNECT_TIMEOUT = 10_000
    private const val HANDSHAKE_TIMEOUT = 15_000
    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /**
     * Buka koneksi ke [destHost]:[destPort] lewat proxy. [protect] wajib dipanggil sebelum
     * connect supaya socket tidak ikut masuk ke tunnel VPN.
     */
    fun open(proxy: ResolvedProxy, destHost: String, destPort: Int, protect: (Socket) -> Boolean): Socket {
        val socket = connectRaw(proxy, protect)
        try {
            if (proxy.cfg.type == ProxyConfig.TYPE_HTTP) httpConnect(socket, proxy.cfg, destHost, destPort)
            else socks5Connect(socket, proxy.cfg, destHost, destPort)
            socket.soTimeout = 0
            return socket
        } catch (e: Exception) {
            socket.closeQuietly()
            throw e
        }
    }

    /**
     * Uji proxy: kembalikan IP publik yang terlihat dari proxy, atau string kosong kalau proxy
     * bisa dipakai tapi IP-nya tidak terbaca. Lempar exception kalau proxy tidak bisa dipakai.
     */
    fun test(proxy: ResolvedProxy, protect: (Socket) -> Boolean): String {
        try {
            val ip = fetchExitIp(proxy, protect)
            if (ip.isNotEmpty()) return ip
        } catch (_: Exception) {
            // lanjut ke uji handshake saja
        }
        open(proxy, "www.google.com", 443, protect).closeQuietly()
        return ""
    }

    private fun fetchExitIp(proxy: ResolvedProxy, protect: (Socket) -> Boolean): String {
        val host = "api.ipify.org"
        val socket: Socket
        val request: String
        if (proxy.cfg.type == ProxyConfig.TYPE_HTTP) {
            socket = connectRaw(proxy, protect)
            request = "GET http://$host/ HTTP/1.1\r\nHost: $host\r\n${authHeader(proxy.cfg)}" +
                "User-Agent: curl/8.0\r\nProxy-Connection: close\r\nConnection: close\r\n\r\n"
        } else {
            socket = open(proxy, host, 80, protect)
            request = "GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: curl/8.0\r\nConnection: close\r\n\r\n"
        }
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT
            socket.getOutputStream().apply { write(request.toByteArray()); flush() }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(2048)
            val input = socket.getInputStream()
            while (out.size() < 16_384) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            val text = out.toString("ISO-8859-1")
            if (!text.startsWith("HTTP/") || text.split(' ').getOrNull(1) != "200") return ""
            val body = text.substringAfter("\r\n\r\n", "")
            return body.lines().map { it.trim() }
                .lastOrNull { it.matches(IPV4) || (it.contains(':') && it.matches(Regex("[0-9a-fA-F:]+"))) }
                ?: ""
        } finally {
            socket.closeQuietly()
        }
    }

    private fun connectRaw(proxy: ResolvedProxy, protect: (Socket) -> Boolean): Socket {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            if (!protect(socket)) throw IOException("Failed to protect socket from VPN")
            socket.connect(InetSocketAddress(proxy.address, proxy.cfg.port), CONNECT_TIMEOUT)
            socket.soTimeout = HANDSHAKE_TIMEOUT
            return socket
        } catch (e: Exception) {
            socket.closeQuietly()
            throw e
        }
    }

    private fun socks5Connect(socket: Socket, cfg: ProxyConfig, host: String, port: Int) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()

        out.write(if (cfg.useAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        out.flush()
        val hello = input.readExact(2)
        if (hello[0].toInt() != 5) throw IOException("Not a SOCKS5 proxy")
        when (hello[1].toInt() and 0xFF) {
            0 -> Unit
            2 -> {
                if (!cfg.useAuth) throw IOException("Proxy requires username/password")
                val user = cfg.username.toByteArray()
                val pass = cfg.password.toByteArray()
                if (user.size > 255 || pass.size > 255) throw IOException("Username/password too long")
                out.write(byteArrayOf(1, user.size.toByte()) + user + byteArrayOf(pass.size.toByte()) + pass)
                out.flush()
                if (input.readExact(2)[1].toInt() != 0) throw IOException("Username/password rejected")
            }
            else -> throw IOException(
                if (cfg.useAuth) "Authentication method rejected by proxy" else "Proxy requires username/password"
            )
        }

        val req = ByteArrayOutputStream()
        req.write(byteArrayOf(5, 1, 0))
        if (host.matches(IPV4)) {
            req.write(1)
            req.write(InetAddress.getByName(host).address)
        } else {
            val name = host.toByteArray()
            if (name.size > 255) throw IOException("Host name too long")
            req.write(3)
            req.write(name.size)
            req.write(name)
        }
        req.write(port shr 8)
        req.write(port and 0xFF)
        out.write(req.toByteArray())
        out.flush()

        val rep = input.readExact(4)
        if (rep[1].toInt() != 0) throw IOException("SOCKS5 refused connection (code ${rep[1].toInt() and 0xFF})")
        val addrLen = when (rep[3].toInt() and 0xFF) {
            1 -> 4
            4 -> 16
            3 -> input.readExact(1)[0].toInt() and 0xFF
            else -> throw IOException("Invalid SOCKS5 reply")
        }
        input.readExact(addrLen + 2)
    }

    private fun httpConnect(socket: Socket, cfg: ProxyConfig, host: String, port: Int) {
        val request = "CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n${authHeader(cfg)}" +
            "Proxy-Connection: keep-alive\r\n\r\n"
        socket.getOutputStream().apply { write(request.toByteArray()); flush() }

        // Baca per byte sampai akhir header supaya data tunnel tidak ikut termakan.
        val input = socket.getInputStream()
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw IOException("Proxy closed the connection")
            head.write(b)
            matched = if (b == (if (matched % 2 == 0) '\r'.code else '\n'.code)) matched + 1
            else if (b == '\r'.code) 1 else 0
            if (head.size() > 8192) throw IOException("Proxy reply too long")
        }
        val status = head.toString("ISO-8859-1").lineSequence().first().trim()
        val code = status.split(' ').getOrNull(1)
        if (code != "200") {
            throw IOException(if (code == "407") "Username/password rejected (407)" else "Proxy refused: $status")
        }
    }

    private fun authHeader(cfg: ProxyConfig): String {
        if (!cfg.useAuth) return ""
        val token = Base64.encodeToString("${cfg.username}:${cfg.password}".toByteArray(), Base64.NO_WRAP)
        return "Proxy-Authorization: Basic $token\r\n"
    }

    private fun InputStream.readExact(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) throw IOException("Proxy closed the connection")
            off += r
        }
        return buf
    }
}

fun Socket.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
    }
}
