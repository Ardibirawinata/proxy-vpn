package com.kimi.proxyvpn

import android.content.Context

data class ProxyConfig(
    val type: String = TYPE_SOCKS5,
    val host: String = "",
    val port: Int = 0,
    val useAuth: Boolean = false,
    val username: String = "",
    val password: String = "",
) {
    val isValid: Boolean
        get() = host.isNotBlank() && port in 1..65535 && (!useAuth || username.isNotEmpty())

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("type", type)
            .putString("host", host)
            .putInt("port", port)
            .putBoolean("useAuth", useAuth)
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    companion object {
        const val TYPE_SOCKS5 = "SOCKS5"
        const val TYPE_HTTP = "HTTP"
        private const val PREFS = "proxy"

        fun load(ctx: Context): ProxyConfig {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ProxyConfig(
                type = p.getString("type", TYPE_SOCKS5) ?: TYPE_SOCKS5,
                host = p.getString("host", "") ?: "",
                port = p.getInt("port", 0),
                useAuth = p.getBoolean("useAuth", false),
                username = p.getString("username", "") ?: "",
                password = p.getString("password", "") ?: "",
            )
        }
    }
}
