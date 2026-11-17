package com.sphy.airconcontroller

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sphy.airconcontroller.diagnostics.CampTestReader
import com.sphy.airconcontroller.diagnostics.CampTestSnapshot
import com.sphy.airconcontroller.ui.OpenDiKeyActivity
import com.sphy.airconcontroller.update.AppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Date

class CampTestsActivity : OpenDiKeyActivity() {
    private lateinit var reader: CampTestReader
    private lateinit var status: TextView
    private lateinit var reportView: TextView
    private lateinit var buttons: List<Button>
    private var baseline: CampTestSnapshot? = null
    private var report = ""
    private var busy = false
    private var originalBrightness: Float? = null
    private var consumeDimTouch = false
    private val handler = Handler(Looper.getMainLooper())
    private val restoreDim = Runnable { restoreBrightness("10-second timeout") }
    private val persistenceMutex = Mutex()
    private val sessionFile by lazy { AtomicFile(File(filesDir, "camp-test-session.json")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camp_tests)
        reader = CampTestReader(this)
        status = findViewById(R.id.campTestStatus)
        reportView = findViewById(R.id.campTestReport)
        buttons = listOf(
            R.id.campTestBaseline, R.id.campTestCapture, R.id.campTestAcOff,
            R.id.campTestDim, R.id.campTestShare, R.id.campTestClear,
        ).map { findViewById(it) }
        findViewById<View>(R.id.campTestBack).setOnClickListener { finish() }
        findViewById<View>(R.id.campTestBaseline).setOnClickListener { capture(true) }
        findViewById<View>(R.id.campTestCapture).setOnClickListener { capture(false) }
        findViewById<View>(R.id.campTestAcOff).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camp_test_ac_off)
                .setMessage(R.string.camp_test_ac_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.camp_test_ac_off) { _, _ -> climateOff() }
                .show()
        }
        findViewById<View>(R.id.campTestDim).setOnClickListener { dim() }
        findViewById<View>(R.id.campTestShare).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camp_test_share)
                .setMessage(R.string.camp_test_share_note)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.camp_test_share) { _, _ -> share() }
                .show()
        }
        findViewById<View>(R.id.campTestClear).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camp_test_clear)
                .setMessage(R.string.camp_test_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.camp_test_clear) { _, _ ->
                    operation {
                        baseline = null
                        report = ""
                        persist()
                    }
                }.show()
        }
        operation {
            val saved = withContext(Dispatchers.IO) {
                if (sessionFile.baseFile.exists()) {
                    JSONObject(sessionFile.openRead().bufferedReader().use { it.readText() })
                } else null
            }
            if (saved != null) {
                baseline = saved.optJSONObject("baseline")?.let {
                    CampTestSnapshot(readMap(it.getJSONObject("values")), readMap(it.getJSONObject("errors")))
                }
                report = saved.getString("report")
            }
        }
    }

    private fun capture(replaceBaseline: Boolean) {
        if (!replaceBaseline && baseline == null) {
            status.setText(R.string.camp_test_baseline_needed)
            return
        }
        val label = if (replaceBaseline) "Baseline" else
            findViewById<Spinner>(R.id.campTestStage).selectedItem.toString()
        val note = findViewById<EditText>(R.id.campTestNote).text.toString()
        operation {
            val snapshot = withContext(Dispatchers.IO) { reader.capture() }
            val previous = baseline
            if (replaceBaseline) baseline = snapshot
            append(label, buildString {
                appendLine("User observation: $note")
                if (!replaceBaseline && previous != null) {
                    appendLine("Differences from baseline:")
                    appendLine(snapshot.changesFrom(previous))
                }
                appendLine("Full raw snapshot:")
                appendLine(snapshot.report())
            })
            persist()
        }
    }

    private fun climateOff() = operation {
        val before = withContext(Dispatchers.IO) { reader.capture() }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            append("Climate OFF skipped", "Test screen lost foreground before the command.")
            persist()
            return@operation
        }
        withContext(NonCancellable) {
            val result = withContext(Dispatchers.IO) { reader.climate.stop() }
            // Save the command before waiting for a read-back, even if the screen is left.
            append("Explicit climate OFF command", "$result\nBefore:\n${before.report()}\nClimate is NOT automatically restored.")
            persist()
            delay(1500)
            val after = withContext(Dispatchers.IO) { reader.capture() }
            val observed = when (after.values["Climate.powerOn"]) {
                "false" -> "Read-back reports climate OFF."
                "true" -> "NOT CONFIRMED: read-back still reports climate ON."
                else -> "NOT CONFIRMED: climate power read-back unavailable."
            }
            append("Climate OFF read-back", "$observed\nCommand success flag: ${result.success}\n${after.changesFrom(before)}")
            persist()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                (!result.success || after.values["Climate.powerOn"] != "false")) {
                Toast.makeText(this@CampTestsActivity, observed + "\n" + result.detail, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun dim() {
        if (busy) return
        originalBrightness = window.attributes.screenBrightness
        window.attributes = window.attributes.apply { screenBrightness = 0.1f }
        findViewById<View>(R.id.campTestDimOverlay).visibility = View.VISIBLE
        handler.postDelayed(restoreDim, 10_000)
        operation {
            append("Temporary dim started", "App-window brightness override = 0.1; previous override = $originalBrightness. System brightness settings unchanged.")
            persist()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (originalBrightness != null && event.actionMasked == MotionEvent.ACTION_DOWN) {
            consumeDimTouch = true
            restoreBrightness("touch")
        }
        if (consumeDimTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                consumeDimTouch = false
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun restoreBrightness(reason: String) {
        val previous = originalBrightness ?: return
        handler.removeCallbacks(restoreDim)
        window.attributes = window.attributes.apply { screenBrightness = previous }
        originalBrightness = null
        findViewById<View>(R.id.campTestDimOverlay).visibility = View.GONE
        // Restoration itself is synchronous and never depends on a coroutine completing.
        append("Brightness restored", "Reason: $reason; app-window override restored to $previous.")
        lifecycleScope.launch {
            try {
                persist()
                render()
            } catch (e: IOException) {
                showError(e)
            }
        }
    }

    override fun onPause() {
        restoreBrightness("screen left / foreground lost")
        super.onPause()
    }

    private fun operation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        buttons.forEach { it.isEnabled = false }
        status.setText(R.string.camp_test_busy)
        lifecycleScope.launch {
            try {
                block()
                status.setText(R.string.camp_test_saved)
            } catch (e: IOException) {
                showError(e)
            } catch (e: JSONException) {
                showError(e)
            } catch (e: SecurityException) {
                showError(e)
            } catch (e: ActivityNotFoundException) {
                showError(e)
            } finally {
                busy = false
                buttons.forEach { it.isEnabled = true }
                render()
            }
        }
    }

    private fun append(label: String, text: String) {
        report += "\n=== ${Date()} | $label ===\n$text\n"
    }

    private fun render() {
        reportView.text = report.ifEmpty { getString(R.string.camp_test_empty) }
    }

    private suspend fun persist() = withContext(NonCancellable) {
        persistenceMutex.withLock {
            val snapshot = baseline
            val json = JSONObject().put("report", report)
            if (snapshot != null) {
                json.put("baseline", JSONObject()
                    .put("values", JSONObject(snapshot.values))
                    .put("errors", JSONObject(snapshot.errors)))
            }
            withContext(Dispatchers.IO) {
                val stream = sessionFile.startWrite()
                try {
                    stream.write(json.toString().toByteArray(Charsets.UTF_8))
                    sessionFile.finishWrite(stream)
                } catch (e: IOException) {
                    sessionFile.failWrite(stream)
                    throw e
                }
            }
        }
    }

    private fun share() {
        if (report.isEmpty()) {
            status.setText(R.string.camp_test_baseline_needed)
            return
        }
        operation {
            val text = "Sharktooth Camp Mode interactive tests\n${AppUpdater.currentVersionName(this)}\n" +
                "Observed changes do not validate setter semantics.\n$report"
            val file = withContext(Dispatchers.IO) {
                File(cacheDir, "diagnostics").apply {
                    if (!isDirectory && !mkdirs()) throw IOException("Cannot create report directory")
                }.let { File(it, "camp-mode-tests.txt") }.apply { writeText(text) }
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.camp_test_share)))
            } else {
                throw IOException("Return to the test page and tap Share test report again.")
            }
        }
    }

    private fun showError(e: Exception) {
        Log.e("CampTestsActivity", "Interactive test failed", e)
        val message = getString(R.string.camp_test_failed, e.message ?: e.javaClass.simpleName)
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun readMap(json: JSONObject): Map<String, String> =
        json.keys().asSequence().associateWith { json.getString(it) }
}
