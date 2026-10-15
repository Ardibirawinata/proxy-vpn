package com.kimi.proxyvpn

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/** Tile di panel notifikasi (Quick Settings) untuk menyambung / memutus VPN. */
class ProxyTileService : TileService() {

    private val listener: () -> Unit = { updateTile() }

    override fun onCreate() {
        super.onCreate()
        VpnState.init(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        VpnState.listeners.add(listener)
        updateTile()
    }

    override fun onStopListening() {
        VpnState.listeners.remove(listener)
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (VpnState.isActive) {
            startService(Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP))
            return
        }
        // Izin VPN belum ada atau data proxy belum lengkap -> buka aplikasi.
        if (VpnService.prepare(this) != null || !ProxyConfig.load(this).isValid) {
            openApp(connect = true)
            return
        }
        val intent = Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_START)
        try {
            VpnState.set(VpnState.Status.CONNECTING, "Connecting…")
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (e: Exception) {
            // Sebagian ROM melarang memulai service dari panel; sambungkan lewat aplikasi.
            Log.w("ProxyTile", "start", e)
            VpnState.set(VpnState.Status.DISCONNECTED, "")
            openApp(connect = true)
        }
    }

    private fun openApp(connect: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.EXTRA_CONNECT, connect)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val cfg = ProxyConfig.load(this)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn)
        tile.label = "Proxy VPN"
        tile.state = if (VpnState.isActive) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = when (VpnState.status) {
                VpnState.Status.DISCONNECTED -> if (cfg.isValid) "Off" else "Not set up"
                VpnState.Status.CONNECTING -> "Connecting…"
                VpnState.Status.PAUSED -> "Paused…"
                VpnState.Status.ERROR -> "Proxy error"
                VpnState.Status.CONNECTED -> "${cfg.host}:${cfg.port}"
            }
        }
        tile.updateTile()
    }
}
