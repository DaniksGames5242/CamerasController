package com.mycamerascontroller.client

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * First run: join an existing network with an invite code/QR, or create a
 * new one from the user's own Firebase project config.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var errorText: TextView
    private lateinit var codeInput: TextInputEditText

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { codeInput.setText(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_setup)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        errorText = findViewById(R.id.errorText)
        codeInput = findViewById(R.id.codeInput)
        val nameInput = findViewById<TextInputEditText>(R.id.nameInput)
        val firebaseInput = findViewById<TextInputEditText>(R.id.firebaseInput)
        val turnInput = findViewById<TextInputEditText>(R.id.turnInput)
        val join = findViewById<MaterialButton>(R.id.joinButton)
        val create = findViewById<MaterialButton>(R.id.createButton)

        findViewById<MaterialButton>(R.id.scanButton).setOnClickListener {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.setup_scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false)
            )
        }

        join.setOnClickListener {
            val cfg = try {
                Network.decodeInvite(codeInput.text?.toString().orEmpty())
            } catch (e: NetworkException) {
                showError(e.message); return@setOnClickListener
            }
            check(join, cfg) { openMain() }
        }

        create.setOnClickListener {
            val cfg = try {
                Network.parseFirebaseSnippet(
                    nameInput.text?.toString().orEmpty().trim(),
                    firebaseInput.text?.toString().orEmpty(),
                    turnInput.text?.toString(),
                )
            } catch (e: NetworkException) {
                showError(e.message); return@setOnClickListener
            }
            check(create, cfg) {
                startActivity(Intent(this, NetworkActivity::class.java).putExtra(NetworkActivity.EXTRA_FRESH, true))
                finish()
            }
        }
    }

    private fun check(button: MaterialButton, cfg: NetworkConfig, onOk: () -> Unit) {
        showError(null)
        val label = button.text
        button.isEnabled = false
        button.text = getString(R.string.setup_checking)
        Network.test(this, cfg) { error ->
            button.isEnabled = true
            button.text = label
            if (error != null) {
                showError(error); return@test
            }
            Network.save(this, cfg)
            onOk()
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun showError(msg: String?) {
        errorText.text = msg
        errorText.visibility = if (msg == null) View.GONE else View.VISIBLE
    }
}
