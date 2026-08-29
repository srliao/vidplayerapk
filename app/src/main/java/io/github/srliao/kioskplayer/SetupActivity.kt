package io.github.srliao.kioskplayer

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.srliao.kioskplayer.core.UrlCheck
import io.github.srliao.kioskplayer.core.UrlValidator

class SetupActivity : AppCompatActivity() {

    private val kiosk get() = KioskApp.kiosk

    private lateinit var listContainer: LinearLayout
    private lateinit var nameInput: EditText
    private lateinit var urlInput: EditText
    private lateinit var errorLabel: TextView
    private lateinit var receivePanel: LinearLayout
    private lateinit var receiveStatus: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var server: StreamReceiverServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        listContainer = findViewById(R.id.stream_list)
        nameInput = findViewById(R.id.input_name)
        urlInput = findViewById(R.id.input_url)
        errorLabel = findViewById(R.id.input_error)
        receivePanel = findViewById(R.id.receive_panel)
        receiveStatus = findViewById(R.id.receive_status)

        findViewById<Button>(R.id.btn_paste).setOnClickListener { pasteUrl() }
        findViewById<Button>(R.id.btn_add).setOnClickListener { addStream() }
        findViewById<Button>(R.id.btn_close).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_receive).setOnClickListener { startReceiving() }
        findViewById<Button>(R.id.btn_receive_cancel).setOnClickListener { stopReceiving() }

        renderList()
    }

    override fun onPause() {
        // A kiosk must not hold a listening socket once you have walked away
        // from this screen.
        stopReceiving()
        super.onPause()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val streams = kiosk.streams
        val inflater = LayoutInflater.from(this)

        for (entry in streams.entries) {
            val row = inflater.inflate(R.layout.row_stream, listContainer, false)
            row.findViewById<TextView>(R.id.row_marker).text =
                if (entry.id == streams.current?.id) "●" else ""
            row.findViewById<TextView>(R.id.row_name).text = entry.displayName
            row.findViewById<TextView>(R.id.row_url).text = entry.url

            // Tapping a row switches to it and returns to the player.
            row.setOnClickListener {
                kiosk.select(entry.id)
                finish()
            }
            row.findViewById<Button>(R.id.row_delete).setOnClickListener {
                kiosk.remove(entry.id)
                renderList()
            }
            listContainer.addView(row)
        }
    }

    private fun pasteUrl() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()

        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show()
            return
        }
        urlInput.setText(text)
    }

    private fun addStream() {
        // Validation only - no live connection test. You test a stream by
        // tapping it and watching the player, which has the full reconnect
        // machinery and a diagnostics screen behind it.
        when (val check = UrlValidator.check(urlInput.text.toString())) {
            is UrlCheck.Invalid -> showError(check.reason)
            is UrlCheck.Valid -> {
                kiosk.add(nameInput.text.toString(), check.url)
                nameInput.setText("")
                urlInput.setText("")
                errorLabel.visibility = View.GONE
                renderList()
            }
        }
    }

    private fun showError(reason: String) {
        errorLabel.text = reason
        errorLabel.visibility = View.VISIBLE
    }

    private fun startReceiving() {
        if (server != null) return

        val started = StreamReceiverServer { name, url ->
            // The callback arrives on the server thread; every touch of the
            // stream list and the views has to happen on the main thread.
            handler.post {
                kiosk.add(name, url)
                renderList()
                receiveStatus.text = getString(R.string.receive_added, name ?: "<no name>")
            }
        }

        receivePanel.visibility = View.VISIBLE
        if (runCatching { started.start() }.isFailure) {
            receiveStatus.text = getString(R.string.receive_failed)
            return
        }
        server = started
        receiveStatus.text = getString(R.string.receive_waiting, localIpv4())
    }

    private fun stopReceiving() {
        server?.stop()
        server = null
        receivePanel.visibility = View.GONE
    }
}
