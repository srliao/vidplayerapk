package io.github.srliao.kioskplayer

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.srliao.kioskplayer.core.UiState
import org.videolan.libvlc.util.VLCVideoLayout

class PlayerActivity : AppCompatActivity() {

    private val kiosk get() = KioskApp.kiosk

    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var statusChip: TextView
    private lateinit var currentName: TextView
    private lateinit var nextButton: Button

    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        videoLayout = findViewById(R.id.video_layout)
        statusChip = findViewById(R.id.status_chip)
        currentName = findViewById(R.id.current_name)
        nextButton = findViewById(R.id.btn_next)

        nextButton.setOnClickListener { kiosk.next(); render() }
        findViewById<Button>(R.id.btn_setup).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        findViewById<Button>(R.id.btn_diag).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }

        kiosk.onUiState = { state -> runOnUiThread { renderState(state) } }
    }

    override fun onStart() {
        super.onStart()
        // First run: nothing configured, so go straight to setup.
        if (kiosk.streams.entries.isEmpty()) {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        registerNetworkCallback()
        kiosk.attach(videoLayout)
        render()
    }

    override fun onStop() {
        kiosk.detach()
        unregisterNetworkCallback()
        super.onStop()
    }

    override fun onDestroy() {
        kiosk.onUiState = null
        super.onDestroy()
    }

    private fun render() {
        currentName.text = kiosk.streams.current?.displayName ?: getString(R.string.no_stream)
        nextButton.isEnabled = kiosk.streams.entries.size > 1
    }

    private fun renderState(state: UiState) {
        render()
        val text = when (state) {
            is UiState.NoStream -> getString(R.string.no_stream)
            is UiState.Connecting -> "Connecting…"
            is UiState.Live -> null
            is UiState.Retrying -> buildString {
                append("Reconnecting (attempt ${state.attempt})")
                append(" — retrying in ${(state.nextRetryInMs + 999) / 1000}s")
                state.lastError?.let { append("\n$it") }
            }
        }
        statusChip.text = text ?: ""
        statusChip.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = kiosk.onNetworkChanged(true)
            override fun onLost(network: Network) = kiosk.onNetworkChanged(false)
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        connectivity = cm
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        val cm = connectivity ?: return
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        connectivity = null
        networkCallback = null
    }
}
