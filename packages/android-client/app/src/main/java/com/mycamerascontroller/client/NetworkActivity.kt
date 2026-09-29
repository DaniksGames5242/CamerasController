package com.mycamerascontroller.client

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.BarcodeEncoder

/** The network's invite (QR + code) and leaving the network. */
class NetworkActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FRESH = "fresh"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Network.load(this)
        if (cfg == null) {
            startActivity(Intent(this, SetupActivity::class.java)); finish(); return
        }
        val fresh = intent.getBooleanExtra(EXTRA_FRESH, false)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_network)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        val code = Network.encodeInvite(cfg)
        findViewById<TextView>(R.id.title).text =
            getString(if (fresh) R.string.network_created else R.string.network_title, cfg.name)
        findViewById<TextView>(R.id.codeText).text = code
        findViewById<View>(R.id.turnWarning).visibility =
            if (cfg.turnWorkerUrl == null && cfg.turnServers.isEmpty()) View.VISIBLE else View.GONE

        runCatching {
            val bitmap: Bitmap = BarcodeEncoder().encodeBitmap(
                code, BarcodeFormat.QR_CODE, 720, 720,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 1),
            )
            findViewById<ImageView>(R.id.qrImage).setImageBitmap(bitmap)
        }

        findViewById<MaterialButton>(R.id.copyButton).setOnClickListener {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("CamerasController", code))
            Toast.makeText(this, R.string.network_copied, Toast.LENGTH_LONG).show()
        }
        findViewById<MaterialButton>(R.id.doneButton).setOnClickListener {
            if (fresh) startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
        findViewById<MaterialButton>(R.id.leaveButton).apply {
            visibility = if (fresh) View.GONE else View.VISIBLE
            setOnClickListener {
                AlertDialog.Builder(this@NetworkActivity)
                    .setMessage(R.string.network_leave_confirm)
                    .setPositiveButton(R.string.network_leave) { _, _ ->
                        Network.clear(this@NetworkActivity)
                        startActivity(
                            Intent(this@NetworkActivity, SetupActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        )
                        finish()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }
}
