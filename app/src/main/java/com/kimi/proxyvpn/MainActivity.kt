package com.kimi.proxyvpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var txtStatus: TextView
    private lateinit var txtDetail: TextView
    private lateinit var btnConnect: Button
    private lateinit var radHttp: RadioButton
    private lateinit var radSocks5: RadioButton
    private lateinit var edtHost: EditText
    private lateinit var edtPort: EditText
    private lateinit var swAuth: Switch
    private lateinit var boxAuth: View
    private lateinit var edtUser: EditText
    private lateinit var edtPass: EditText
    private val statusListener: () -> Unit = { renderStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VpnState.init(this)
        setContentView(R.layout.activity_main)

        txtStatus = findViewById(R.id.txtStatus)
        txtDetail = findViewById(R.id.txtDetail)
        btnConnect = findViewById(R.id.btnConnect)
        radHttp = findViewById(R.id.radHttp)
        radSocks5 = findViewById(R.id.radSocks5)
        edtHost = findViewById(R.id.edtHost)
        edtPort = findViewById(R.id.edtPort)
        swAuth = findViewById(R.id.swAuth)
        boxAuth = findViewById(R.id.boxAuth)
        edtUser = findViewById(R.id.edtUser)
        edtPass = findViewById(R.id.edtPass)

        swAuth.setOnCheckedChangeListener { _, checked ->
            boxAuth.visibility = if (checked) View.VISIBLE else View.GONE
        }
        if (savedInstanceState == null) fillForm(ProxyConfig.load(this))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (saveForm() != null) {
                toast(if (VpnState.isActive) "Saved, reconnecting…" else "Saved")
                reconnectIfActive()
            }
        }
        btnConnect.setOnClickListener {
            if (VpnState.isActive) sendToService(ProxyVpnService.ACTION_STOP) else connect()
        }
        handleConnectExtra(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleConnectExtra(intent)
    }

    /** Dibuka dari tile panel: langsung sambungkan (minta izin VPN dulu bila perlu). */
    private fun handleConnectExtra(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_CONNECT, false) != true) return
        intent.removeExtra(EXTRA_CONNECT)
        if (!VpnState.isActive) connect()
    }

    override fun onResume() {
        super.onResume()
        VpnState.listeners.add(statusListener)
        renderStatus()
    }

    override fun onPause() {
        VpnState.listeners.remove(statusListener)
        super.onPause()
    }

    // ---------------------------------------------------------------- form

    private fun fillForm(c: ProxyConfig) {
        (if (c.type == ProxyConfig.TYPE_HTTP) radHttp else radSocks5).isChecked = true
        edtHost.setText(c.host)
        edtPort.setText(if (c.port > 0) c.port.toString() else "")
        swAuth.isChecked = c.useAuth
        boxAuth.visibility = if (c.useAuth) View.VISIBLE else View.GONE
        edtUser.setText(c.username)
        edtPass.setText(c.password)
    }

    /** Validasi dan simpan isi form. Mengembalikan null (dan menandai field) jika tidak valid. */
    private fun saveForm(): ProxyConfig? {
        val host = edtHost.text.toString().trim()
        if (host.isEmpty() || host.any { it.isWhitespace() || it == '/' }) {
            return invalid(edtHost, "Enter proxy host (without http://)")
        }
        val port = edtPort.text.toString().toIntOrNull()
        if (port == null || port !in 1..65535) return invalid(edtPort, "Port must be 1 - 65535")

        val useAuth = swAuth.isChecked
        val user = edtUser.text.toString()
        if (useAuth && user.isEmpty()) return invalid(edtUser, "Enter username")

        val cfg = ProxyConfig(
            type = if (radHttp.isChecked) ProxyConfig.TYPE_HTTP else ProxyConfig.TYPE_SOCKS5,
            host = host,
            port = port,
            useAuth = useAuth,
            username = user,
            password = edtPass.text.toString(),
        )
        cfg.save(this)
        return cfg
    }

    private fun invalid(field: EditText, message: String): ProxyConfig? {
        field.error = message
        field.requestFocus()
        return null
    }

    // ---------------------------------------------------------------- koneksi

    private fun connect() {
        if (saveForm() == null) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, REQ_VPN)
        } else {
            sendToService(ProxyVpnService.ACTION_START)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_VPN) return
        if (resultCode == RESULT_OK) sendToService(ProxyVpnService.ACTION_START)
        else toast("VPN permission denied")
    }

    private fun reconnectIfActive() {
        if (VpnState.isActive) sendToService(ProxyVpnService.ACTION_RECONNECT)
    }

    private fun sendToService(action: String) {
        val intent = Intent(this, ProxyVpnService::class.java).setAction(action)
        if (action == ProxyVpnService.ACTION_START) {
            VpnState.set(VpnState.Status.CONNECTING, "Connecting…")
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } else {
            startService(intent)
        }
    }

    private fun renderStatus() {
        val (label, color) = when (VpnState.status) {
            VpnState.Status.DISCONNECTED -> "Disconnected" to R.color.idle
            VpnState.Status.CONNECTING -> "Connecting…" to R.color.warn
            VpnState.Status.CONNECTED -> "Connected" to R.color.ok
            VpnState.Status.PAUSED -> "Paused" to R.color.warn
            VpnState.Status.ERROR -> "Proxy error" to R.color.err
        }
        txtStatus.text = label
        txtStatus.setTextColor(getColor(color))
        txtDetail.text = VpnState.message.ifEmpty {
            if (VpnState.isActive) "" else "Enter proxy details, then tap Connect"
        }
        val active = VpnState.isActive
        btnConnect.text = if (active) "Disconnect" else "Connect"
        btnConnect.backgroundTintList = getColorStateList(if (active) R.color.err else R.color.primary)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_CONNECT = "connect"
        private const val REQ_VPN = 1
    }
}
