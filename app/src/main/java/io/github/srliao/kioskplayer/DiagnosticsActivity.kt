package io.github.srliao.kioskplayer

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class DiagnosticsActivity : AppCompatActivity() {

    private val kiosk get() = KioskApp.kiosk

    private lateinit var header: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView

    private val handler = Handler(Looper.getMainLooper())
    private val server = DiagnosticsServer { KioskApp.kiosk.snapshotJson() }
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        header = findViewById(R.id.diag_header)
        log = findViewById(R.id.diag_log)
        scroll = findViewById(R.id.diag_scroll)
        findViewById<Button>(R.id.btn_close).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        runCatching { server.start() }
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        server.stop()
        super.onPause()
    }

    private fun render() {
        header.text = buildString {
            append("endpoint  http://").append(localIpv4()).append(":8080/stats\n\n")
            append(kiosk.snapshotJson().lineSequence().take(20).joinToString("\n"))
        }

        log.text = kiosk.diagnostics.snapshot().joinToString("\n") { event ->
            "%8d %-5s %-12s %s".format(
                event.atMs / 1000, event.level.name, event.state, event.message,
            )
        }
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
