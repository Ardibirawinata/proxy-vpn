package com.kimi.proxyvpn

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import java.util.concurrent.CopyOnWriteArraySet

/** Status VPN yang dibagi antara service, activity, dan tile panel (satu proses). */
object VpnState {
    enum class Status { DISCONNECTED, CONNECTING, CONNECTED, PAUSED, ERROR }

    @Volatile
    var status: Status = Status.DISCONNECTED
        private set

    @Volatile
    var message: String = ""
        private set

    /** true selama tunnel VPN aktif (termasuk saat jeda / proxy error). */
    val isActive: Boolean
        get() = status != Status.DISCONNECTED

    /** Dipanggil di main thread setiap status berubah. */
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    private var appContext: Context? = null

    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    fun set(status: Status, message: String = "") {
        this.status = status
        this.message = message
        main.post { listeners.forEach { it() } }
        // Minta panel memperbarui tile walaupun sedang tidak terbuka.
        appContext?.let {
            try {
                TileService.requestListeningState(it, ComponentName(it, ProxyTileService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
