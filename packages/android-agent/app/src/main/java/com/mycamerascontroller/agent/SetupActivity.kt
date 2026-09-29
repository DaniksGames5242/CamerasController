package com.mycamerascontroller.agent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * First-run screen: join this phone to the owner's network with an invite
 * code (typed, pasted or scanned from the desktop client's QR). Joining is
 * an explicit on-device step with a consent checkbox — the agent never
 * connects itself to anything.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var codeInput: TextInputEditText
    private lateinit var nameInput: TextInputEditText
    private lateinit var consent: MaterialCheckBox
    private lateinit var errorText: TextView
    private lateinit var connect: MaterialButton

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { codeInput.setText(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_setup)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        codeInput = findViewById(R.id.codeInput)
        nameInput = findViewById(R.id.nameInput)
        consent = findViewById(R.id.consentCheck)
        errorText = findViewById(R.id.errorText)
        connect = findViewById(R.id.connectButton)

        nameInput.setText(DeviceIdentity.deviceName(this))
        findViewById<MaterialButton>(R.id.scanButton).setOnClickListener {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.setup_scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }
        connect.setOnClickListener { tryConnect() }
    }

    private fun showError(msg: String?) {
        errorText.text = msg
        errorText.visibility = if (msg == null) View.GONE else View.VISIBLE
    }

    private fun tryConnect() {
        if (!consent.isChecked) {
            showError(getString(R.string.setup_consent_required)); return
        }
        val cfg = try {
            Network.decodeInvite(codeInput.text?.toString().orEmpty())
        } catch (e: NetworkException) {
            showError(e.message); return
        }
        showError(null)
        connect.isEnabled = false
        connect.text = getString(R.string.setup_checking)
        Network.test(this, cfg) { error ->
            connect.isEnabled = true
            connect.text = getString(R.string.setup_connect)
            if (error != null) {
                showError(error); return@test
            }
            nameInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { DeviceIdentity.setDeviceName(this, it) }
            Network.save(this, cfg)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}
