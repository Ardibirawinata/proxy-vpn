package com.kimi.proxyvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * VPN yang meneruskan semua koneksi TCP perangkat ke proxy SOCKS5 / HTTP.
 *
 * Cara kerja (tanpa library native):
 *  - Paket TCP dari aplikasi dibelokkan (NAT) ke ServerSocket lokal lewat antarmuka TUN, jadi
 *    kernel yang mengurus TCP. ServerSocket lalu membuka koneksi ke tujuan asli lewat proxy.
 *  - DNS dijawab secara lokal dengan IP palsu (198.18.0.0/15); nama domainnya dikirim ke
 *    proxy sehingga resolusi DNS terjadi di sisi proxy.
 *  - UDP selain DNS dan IPv6 dibuang (aplikasi otomatis memakai TCP / IPv4).
 */
class ProxyVpnService : VpnService() {

    private class Session(val remotePort: Int) {
        @Volatile var accepted = false
        @Volatile var closedAt = 0L
        val createdAt = System.currentTimeMillis()
    }

    private val control = Executors.newSingleThreadExecutor()
    private val pool = Executors.newCachedThreadPool()
    private val sessions = ConcurrentHashMap<Long, Session>()
    private val activeSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
    private val fakeDns = FakeDns()

    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var proxy: ResolvedProxy? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var server: ServerSocket? = null
    private var serverPort = 0
    private var lastCleanup = 0L

    override fun onCreate() {
        super.onCreate()
        VpnState.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopVpn()
            ACTION_RECONNECT -> control.execute { if (running) reconnect() }
            else -> {
                showForeground("Connecting…")
                control.execute { if (running) reconnect() else startVpn() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() = stopVpn()

    override fun onDestroy() {
        stopVpn()
        control.shutdownNow()
        pool.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- siklus hidup

    private fun startVpn() {
        val cfg = ProxyConfig.load(this)
        if (!cfg.isValid) return fail("Proxy details incomplete")
        setState(VpnState.Status.CONNECTING, "Connecting to ${cfg.host}:${cfg.port}…")

        val resolved = try {
            ResolvedProxy(cfg, resolve(cfg.host))
        } catch (e: Exception) {
            return fail("Proxy host not found: ${cfg.host}")
        }

        try {
            val srv = ServerSocket(0, 256)
            server = srv
            serverPort = srv.localPort

            val builder = Builder()
                .setSession("Proxy VPN")
                .setMtu(MTU)
                .addAddress(LOCAL_IP_TEXT, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS_IP_TEXT)
                // IPv6 ikut ditangkap lalu dibuang supaya tidak bocor di luar proxy.
                .addAddress("fd00:8::2", 128)
                .addRoute("::", 0)
                .setBlocking(true)
            if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
            tun = builder.establish() ?: return fail("VPN permission not granted")
        } catch (e: Exception) {
            Log.e(TAG, "establish", e)
            return fail("Failed to create VPN: ${e.message}")
        }

        proxy = resolved
        paused = false
        running = true
        Thread({ tunLoop() }, "tun").start()
        Thread({ acceptLoop() }, "accept").start()
        testProxy(resolved)
    }

    /** Jeda koneksi, muat ulang konfigurasi (mis. port baru), lalu sambung ulang ke proxy. */
    private fun reconnect() {
        paused = true
        setState(VpnState.Status.PAUSED, "Connection paused…")
        closeActiveSockets()

        val cfg = ProxyConfig.load(this)
        if (!cfg.isValid) {
            setState(VpnState.Status.ERROR, "Proxy details incomplete")
            return
        }
        val old = proxy
        val resolved = try {
            if (old != null && old.cfg.host == cfg.host) ResolvedProxy(cfg, old.address)
            else ResolvedProxy(cfg, resolve(cfg.host))
        } catch (e: Exception) {
            setState(VpnState.Status.ERROR, "Proxy host not found: ${cfg.host}")
            return
        }

        try {
            Thread.sleep(PAUSE_MS)
        } catch (_: InterruptedException) {
            return
        }
        if (!running) return
        closeActiveSockets()
        proxy = resolved
        paused = false
        setState(VpnState.Status.CONNECTING, "Reconnecting to ${cfg.host}:${cfg.port}…")
        testProxy(resolved)
    }

    private fun testProxy(p: ResolvedProxy) {
        val target = "${p.cfg.host}:${p.cfg.port}"
        try {
            val started = System.currentTimeMillis()
            val ip = ProxyClient.test(p, ::protect)
            val ms = System.currentTimeMillis() - started
            if (!running) return
            val ipInfo = if (ip.isEmpty()) "" else " • IP $ip"
            setState(VpnState.Status.CONNECTED, "$target$ipInfo • $ms ms")
        } catch (e: Exception) {
            if (!running) return
            setState(VpnState.Status.ERROR, "$target failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun fail(message: String) {
        stopVpn()
        VpnState.set(VpnState.Status.DISCONNECTED, message)
    }

    private fun stopVpn() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        closeActiveSockets()
        sessions.clear()
        proxy = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
        VpnState.set(VpnState.Status.DISCONNECTED, "")
        stopSelf()
    }

    private fun closeActiveSockets() {
        for (s in activeSockets.toList()) s.closeQuietly()
        activeSockets.clear()
    }

    /** Resolve host proxy lewat jaringan asli (bukan lewat DNS palsu milik VPN). */
    private fun resolve(host: String): InetAddress {
        if (host.contains(':') || host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))) {
            return InetAddress.getByName(host)
        }
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) continue
            try {
                val all = network.getAllByName(host)
                return all.firstOrNull { it is Inet4Address } ?: all.first()
            } catch (_: Exception) {
            }
        }
        val address = InetAddress.getByName(host)
        if (isFakeIp(ipToInt(address.address))) throw java.net.UnknownHostException(host)
        return address
    }

    // ---------------------------------------------------------------- TUN

    private fun tunLoop() {
        val fd = tun?.fileDescriptor ?: return
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)
        val packet = ByteArray(32767)
        try {
            while (running) {
                val n = input.read(packet)
                if (n < 0) break
                if (n > 0) handlePacket(packet, n, output)
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "tun", e)
        }
        if (running) stopVpn()
    }

    private fun handlePacket(p: ByteArray, n: Int, out: FileOutputStream) {
        if (n < 20 || (p[0].toInt() and 0xF0) != 0x40) return
        val ihl = (p[0].toInt() and 0x0F) * 4
        val total = u16(p, 2)
        if (ihl < 20 || total > n || total < ihl) return
        if (u16(p, 6) and 0x3FFF != 0) return // fragmen tidak didukung
        when (p[9].toInt() and 0xFF) {
            6 -> handleTcp(p, ihl, total, out)
            17 -> handleUdp(p, ihl, total, out)
        }
    }

    private fun handleTcp(p: ByteArray, ihl: Int, total: Int, out: FileOutputStream) {
        if (total < ihl + 20) return
        val src = i32(p, 12)
        val dst = i32(p, 16)
        if (src != LOCAL_IP) return
        val srcPort = u16(p, ihl)
        val dstPort = u16(p, ihl + 2)

        val reply = if (srcPort == serverPort) sessions[key(dst, dstPort)] else null
        if (reply != null) {
            // Balasan ServerSocket lokal -> tampil seolah datang dari tujuan asli.
            put32(p, 12, dst)
            put32(p, 16, LOCAL_IP)
            put16(p, ihl, reply.remotePort)
        } else {
            // Koneksi keluar dari aplikasi -> belokkan ke ServerSocket lokal.
            val k = key(dst, srcPort)
            val existing = sessions[k]
            if (existing == null || existing.remotePort != dstPort) sessions[k] = Session(dstPort)
            put32(p, 12, dst)
            put32(p, 16, LOCAL_IP)
            put16(p, ihl + 2, serverPort)
        }

        put16(p, 10, 0)
        put16(p, 10, fold(sum(p, 0, ihl, 0)))
        val tcpLen = total - ihl
        put16(p, ihl + 16, 0)
        put16(p, ihl + 16, fold(sum(p, ihl, tcpLen, sum(p, 12, 8, 0) + 6 + tcpLen)))
        out.write(p, 0, total)

        cleanupSessions()
    }

    private fun cleanupSessions() {
        val now = System.currentTimeMillis()
        if (now - lastCleanup < 10_000) return
        lastCleanup = now
        val it = sessions.values.iterator()
        while (it.hasNext()) {
            val s = it.next()
            val closed = s.closedAt != 0L && now - s.closedAt > 30_000
            val neverAccepted = !s.accepted && now - s.createdAt > 120_000
            if (closed || neverAccepted) it.remove()
        }
    }

    private fun handleUdp(p: ByteArray, ihl: Int, total: Int, out: FileOutputStream) {
        if (total < ihl + 8) return
        if (u16(p, ihl + 2) != 53) return // hanya DNS
        val udpLen = u16(p, ihl + 4)
        if (udpLen < 8 || ihl + udpLen > total) return
        val answer = fakeDns.answer(p, ihl + 8, udpLen - 8) ?: return

        val r = ByteArray(28 + answer.size)
        r[0] = 0x45
        put16(r, 2, r.size)
        put16(r, 6, 0x4000)
        r[8] = 64
        r[9] = 17
        System.arraycopy(p, 16, r, 12, 4)
        System.arraycopy(p, 12, r, 16, 4)
        put16(r, 10, fold(sum(r, 0, 20, 0)))
        put16(r, 20, 53)
        put16(r, 22, u16(p, ihl))
        put16(r, 24, 8 + answer.size)
        System.arraycopy(answer, 0, r, 28, answer.size)
        out.write(r)
    }

    // ---------------------------------------------------------------- relay TCP

    private fun acceptLoop() {
        val srv = server ?: return
        try {
            while (running) {
                val client = srv.accept()
                pool.execute { handleClient(client) }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "accept", e)
        }
    }

    private fun handleClient(client: Socket) {
        var session: Session? = null
        var remote: Socket? = null
        try {
            val ip = ipToInt(client.inetAddress.address)
            session = sessions[key(ip, client.port)] ?: return
            session.accepted = true
            val p = proxy
            if (paused || p == null) return
            if (ip ushr 8 == LOCAL_IP ushr 8) return // alamat internal VPN (mis. DNS-over-TLS ke DNS palsu)
            val host = if (isFakeIp(ip)) fakeDns.lookup(ip) ?: return else intToIp(ip)

            client.tcpNoDelay = true
            activeSockets.add(client)
            val upstream = ProxyClient.open(p, host, session.remotePort, ::protect)
            remote = upstream
            activeSockets.add(upstream)
            if (paused || !running) return

            val upload = pool.submit {
                try {
                    pump(client.getInputStream(), upstream.getOutputStream())
                    upstream.shutdownOutput()
                } catch (_: Exception) {
                    client.closeQuietly()
                    upstream.closeQuietly()
                }
            }
            pump(upstream.getInputStream(), client.getOutputStream())
            client.shutdownOutput()
            // Tunggu sisi aplikasi selesai mengirim (half-close) sebelum menutup.
            upload.get(30, TimeUnit.SECONDS)
        } catch (_: Exception) {
        } finally {
            client.closeQuietly()
            remote?.closeQuietly()
            activeSockets.remove(client)
            remote?.let { activeSockets.remove(it) }
            session?.closedAt = System.currentTimeMillis()
        }
    }

    private fun pump(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            output.write(buf, 0, n)
        }
    }

    // ---------------------------------------------------------------- notifikasi

    private fun setState(status: VpnState.Status, message: String) {
        VpnState.set(status, message)
        if (!running) return
        val title = when (status) {
            VpnState.Status.CONNECTED -> "Connected"
            VpnState.Status.PAUSED -> "Paused"
            VpnState.Status.ERROR -> "Proxy error"
            else -> "Connecting…"
        }
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification("$title — $message"))
        } catch (_: Exception) {
        }
    }

    private fun showForeground(text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(NOTIF_ID, buildNotification(text))
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground", e)
        }
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "VPN status", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ProxyVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle("Proxy VPN")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.kimi.proxyvpn.START"
        const val ACTION_STOP = "com.kimi.proxyvpn.STOP"
        const val ACTION_RECONNECT = "com.kimi.proxyvpn.RECONNECT"

        private const val TAG = "ProxyVpn"
        private const val CHANNEL = "vpn"
        private const val NOTIF_ID = 1
        private const val MTU = 1500
        private const val PAUSE_MS = 800L
        private const val LOCAL_IP_TEXT = "10.8.0.2"
        private const val DNS_IP_TEXT = "10.8.0.1"
        private const val LOCAL_IP = (10 shl 24) or (8 shl 16) or 2

        private fun key(ip: Int, port: Int): Long = ((ip.toLong() and 0xFFFFFFFFL) shl 16) or port.toLong()

        fun isFakeIp(ip: Int): Boolean = (ip ushr 17) == (FakeDns.BASE ushr 17)

        fun u16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        fun i32(b: ByteArray, o: Int): Int = (u16(b, o) shl 16) or u16(b, o + 2)

        fun put16(b: ByteArray, o: Int, v: Int) {
            b[o] = (v shr 8).toByte()
            b[o + 1] = v.toByte()
        }

        fun put32(b: ByteArray, o: Int, v: Int) {
            put16(b, o, v ushr 16)
            put16(b, o + 2, v and 0xFFFF)
        }

        /** 4 byte terakhir alamat (IPv4 atau IPv4-mapped IPv6) sebagai Int. */
        fun ipToInt(a: ByteArray): Int = i32(a, a.size - 4)

        fun intToIp(ip: Int): String =
            "${ip ushr 24}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

        private fun sum(b: ByteArray, off: Int, len: Int, initial: Long): Long {
            var s = initial
            var i = off
            val end = off + len
            while (i + 1 < end) {
                s += u16(b, i)
                i += 2
            }
            if (i < end) s += (b[i].toInt() and 0xFF) shl 8
            return s
        }

        private fun fold(sum: Long): Int {
            var s = sum
            while (s shr 16 != 0L) s = (s and 0xFFFF) + (s shr 16)
            return (s.inv() and 0xFFFF).toInt()
        }
    }
}

/** Menjawab query DNS dengan IP palsu dan mengingat pasangan IP <-> domain. */
class FakeDns {
    private val domainToIp = HashMap<String, Int>()
    private val ipToDomain = HashMap<Int, String>()
    private var next = 0

    @Synchronized
    fun lookup(ip: Int): String? = ipToDomain[ip]

    @Synchronized
    private fun allocate(domain: String): Int {
        domainToIp[domain]?.let { return it }
        val ip = BASE + 2 + next
        next = (next + 1) % POOL_SIZE
        ipToDomain.remove(ip)?.let { domainToIp.remove(it) }
        ipToDomain[ip] = domain
        domainToIp[domain] = ip
        return ip
    }

    /** Bangun jawaban DNS untuk query di [q] (offset [off], panjang [len]); null jika tidak valid. */
    fun answer(q: ByteArray, off: Int, len: Int): ByteArray? {
        if (len < 17) return null
        val flags = ProxyVpnService.u16(q, off + 2)
        if (flags and 0x8000 != 0 || ProxyVpnService.u16(q, off + 4) < 1) return null

        val name = StringBuilder()
        var pos = off + 12
        val end = off + len
        while (true) {
            if (pos >= end) return null
            val l = q[pos].toInt() and 0xFF
            pos++
            if (l == 0) break
            if (l > 63 || pos + l > end) return null
            if (name.isNotEmpty()) name.append('.')
            name.append(String(q, pos, l, Charsets.ISO_8859_1))
            pos += l
        }
        if (pos + 4 > end) return null
        val qType = ProxyVpnService.u16(q, pos)
        val qClass = ProxyVpnService.u16(q, pos + 2)
        val questionLen = pos + 4 - (off + 12)
        val withAnswer = qType == 1 && qClass == 1 && name.isNotEmpty()

        val r = ByteArray(12 + questionLen + if (withAnswer) 16 else 0)
        r[0] = q[off]
        r[1] = q[off + 1]
        ProxyVpnService.put16(r, 2, 0x8080 or (flags and 0x0100))
        ProxyVpnService.put16(r, 4, 1)
        ProxyVpnService.put16(r, 6, if (withAnswer) 1 else 0)
        System.arraycopy(q, off + 12, r, 12, questionLen)
        if (withAnswer) {
            val a = 12 + questionLen
            ProxyVpnService.put16(r, a, 0xC00C)
            ProxyVpnService.put16(r, a + 2, 1)
            ProxyVpnService.put16(r, a + 4, 1)
            ProxyVpnService.put32(r, a + 6, 5) // TTL pendek: pemetaan hilang saat VPN dimatikan
            ProxyVpnService.put16(r, a + 10, 4)
            ProxyVpnService.put32(r, a + 12, allocate(name.toString().lowercase()))
        }
        return r
    }

    companion object {
        const val BASE = (198 shl 24) or (18 shl 16) // 198.18.0.0/15
        private const val POOL_SIZE = 130_000
    }
}
