package com.sphy.airconcontroller

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.WriterException
import com.sphy.airconcontroller.diagnostics.DiagnosticUpload
import com.sphy.airconcontroller.diagnostics.diagnosticQrCode
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug probe menus behind the home bug icon. */
class DebugHubActivity : OpenDiKeyActivity() {
    private var wifiDetailsVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_hub)

        findViewById<android.widget.ImageButton>(R.id.debugBackButton).setOnClickListener {
            finish()
        }
        val bridge = OpenDiKeyApp.from(this).dikey.usbBridge
        val wifiDebug = findViewById<android.widget.TextView>(R.id.wifiDebugText)
        val wifiDetails = findViewById<View>(R.id.wifiDebugDetails)
        val wifiToggle = findViewById<Button>(R.id.wifiDebugToggleButton)
        fun renderWifiDetails() {
            wifiDetails.visibility = if (wifiDetailsVisible) View.VISIBLE else View.GONE
            wifiToggle.setText(if (wifiDetailsVisible) R.string.wifi_debug_hide else R.string.wifi_debug_show)
        }
        wifiDetailsVisible = savedInstanceState?.getBoolean(STATE_WIFI_DETAILS_VISIBLE) ?: false
        renderWifiDetails()
        wifiToggle.setOnClickListener {
            wifiDetailsVisible = !wifiDetailsVisible
            renderWifiDetails()
        }
        findViewById<android.widget.Button>(R.id.wifiDebugRefreshButton).setOnClickListener {
            if (!bridge.requestWifiStatus()) {
                val message = if (bridge.isUsbOpen) R.string.wifi_debug_send_failed
                else R.string.wifi_debug_usb_unavailable
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bridge.wifiDiagnostics.collect { lines ->
                    wifiDebug.text = lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
                        ?: getString(R.string.wifi_debug_empty)
                }
            }
        }
        if (bridge.isUsbOpen) bridge.requestWifiStatus()
        val uploadButton = findViewById<Button>(R.id.sendDiagnosticStorageButton)
        val upload = OpenDiKeyApp.from(this).diagnosticUpload
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                upload.state.collectLatest { state -> renderDiagnosticUpload(state) }
            }
        }
        uploadButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.diag_storage_confirm_title)
                .setMessage(R.string.diag_storage_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.diag_storage_send) { _, _ ->
                    upload.start()
                }
                .show()
        }
        findViewById<android.view.View>(R.id.openDiagnosticsButton).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openBluetoothDiagnosticsButton).setOnClickListener {
            startActivity(Intent(this, BluetoothDiagnosticsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openAdasLogButton).setOnClickListener {
            startActivity(Intent(this, AdasAutoApplyLogActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openAdbServiceButton).setOnClickListener {
            openBydAdbServiceMenu()
        }
        findViewById<android.view.View>(R.id.openClimateButton).setOnClickListener {
            startActivity(Intent(this, ClimateTestActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openDikeyButton).setOnClickListener {
            startActivity(Intent(this, DiKeyProbeActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openUsbButton).setOnClickListener {
            startActivity(Intent(this, UsbProbeActivity::class.java))
        }
        findViewById<android.view.View>(R.id.openVehicleDumpButton).setOnClickListener {
            startActivity(Intent(this, VehicleDumpActivity::class.java))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_WIFI_DETAILS_VISIBLE, wifiDetailsVisible)
        super.onSaveInstanceState(outState)
    }

    internal suspend fun renderDiagnosticUpload(state: DiagnosticUpload.State) {
        findViewById<Button>(R.id.sendDiagnosticStorageButton).isEnabled = !state.busy
        val uploadStatus = findViewById<TextView>(R.id.diagStorageStatus)
        val text = when {
            state.url != null -> getString(R.string.diag_storage_done, state.url)
            state.error != null -> getString(R.string.diag_storage_failed, state.error)
            state.busy -> state.progress ?: getString(R.string.diag_storage_uploading)
            else -> ""
        }
        uploadStatus.text = text
        uploadStatus.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        val qrResult = findViewById<View>(R.id.diagStorageQrResult)
        val qrImage = findViewById<ImageView>(R.id.diagStorageQrImage)
        qrResult.visibility = View.GONE
        qrImage.setImageDrawable(null)
        val url = state.url ?: return
        val size = resources.getDimensionPixelSize(R.dimen.diagnostic_qr_size)
        val bitmap = try {
            withContext(Dispatchers.Default) { diagnosticQrCode(url, size) }
        } catch (e: WriterException) {
            Log.e("DebugHubActivity", "Could not generate diagnostics QR code", e)
            Toast.makeText(this, R.string.diag_storage_qr_failed, Toast.LENGTH_LONG).show()
            return
        }
        qrImage.setImageDrawable(BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false })
        qrResult.visibility = View.VISIBLE
    }

    private fun openBydAdbServiceMenu() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(BYD_ADB_SERVICE_URI))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.adb_service_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val STATE_WIFI_DETAILS_VISIBLE = "wifi_details_visible"
        /** Resolves to com.byd.secretcode/.activity.AdbActivity, which runs as the system uid. */
        const val BYD_ADB_SERVICE_URI = "byd_pad://secretcode:9521/adbactivity"
    }
}
