package com.sphy.airconcontroller

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import androidx.annotation.VisibleForTesting
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sphy.airconcontroller.adb.AdbPermissionManager
import com.sphy.airconcontroller.byd.BydDisplayController
import com.sphy.airconcontroller.byd.BydDrlController
import com.sphy.airconcontroller.byd.BydWindowController
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CampTestsActivity : OpenDiKeyActivity() {
    private lateinit var reader: CampTestReader
    private lateinit var windows: BydWindowController
    private lateinit var drl: BydDrlController
    private lateinit var status: TextView
    private lateinit var reportView: TextView
    private lateinit var buttons: List<Button>
    private var baseline: CampTestSnapshot? = null
    private var report = ""
    private var busy = false
    private var grantingDim = false
    private var shortcutStart: String? = null
    private lateinit var display: BydDisplayController
    internal enum class DisplayTest { DIM, OFF }
    private var displayTest: DisplayTest? = null
    private var savedDisplay: BydDisplayController.Saved? = null
    private var consumeDimTouch = false
    private val handler = Handler(Looper.getMainLooper())
    private val restoreDim = Runnable { restoreBrightness("10-second timeout") }
    private val persistenceMutex = Mutex()
    private val sessionFile by lazy { AtomicFile(File(filesDir, "camp-test-session.json")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camp_tests)
        reader = CampTestReader(this)
        windows = BydWindowController(this)
        drl = BydDrlController(this)
        display = BydDisplayController(this)
        status = findViewById(R.id.campTestStatus)
        reportView = findViewById(R.id.campTestReport)
        buttons = listOf(
            R.id.campTestBaseline, R.id.campTestCapture, R.id.campTestAcOff, R.id.campTestWindowsClose,
            R.id.campTestDrlOff, R.id.campTestDrlOn,
            R.id.campTestDim, R.id.campTestScreenOff, R.id.campTestShortcut, R.id.campTestShare, R.id.campTestClear,
        ).map { findViewById(it) }
        findViewById<View>(R.id.campTestShortcut).setOnClickListener {
            if (shortcutStart == null) startShortcutRecording() else finishShortcutRecording()
        }
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
        findViewById<View>(R.id.campTestWindowsClose).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camp_test_windows_close)
                .setMessage(R.string.camp_test_windows_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.camp_test_windows_close) { _, _ -> closeWindows() }
                .show()
        }
        // Not in [buttons]: STOP must stay usable while a close is in progress.
        findViewById<View>(R.id.campTestWindowsStop).setOnClickListener { stopWindows() }
        findViewById<View>(R.id.campTestDrlOff).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camp_test_drl_off)
                .setMessage(R.string.camp_test_drl_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.camp_test_drl_off) { _, _ -> setDrl(false) }
                .show()
        }
        findViewById<View>(R.id.campTestDrlOn).setOnClickListener { setDrl(true) }
        findViewById<View>(R.id.campTestDim).setOnClickListener { dim() }
        findViewById<View>(R.id.campTestScreenOff).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camp_test_screen_off)
                .setMessage(R.string.camp_test_screen_off_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.camp_test_screen_off) { _, _ -> startDisplayTest(DisplayTest.OFF) }
                .show()
        }
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
            withContext(Dispatchers.IO) { display.prepare() }
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

    private fun closeWindows() = operation {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            append("Close windows skipped", "Test screen lost foreground before the command.")
            persist()
            return@operation
        }
        withContext(NonCancellable) {
            val before = withContext(Dispatchers.IO) { windows.positions() }
            val result = withContext(Dispatchers.IO) { windows.closeAll() }
            append("Explicit close all windows command", "${result.method} -> ${result.detail}\nOpen % before: ${formatWindows(before)}")
            persist()
            render()
            var after = before
            if (result.success) {
                // Windows take several seconds to travel; poll until all report closed.
                for (i in 0 until 10) {
                    delay(1000)
                    after = withContext(Dispatchers.IO) { windows.positions() }
                    if (after.values.all { it == 0 }) break
                }
            }
            val observed = when {
                !result.success -> "NOT SENT: ${result.detail}"
                after.values.all { it == 0 } -> "Read-back reports all windows closed."
                after.values.all { it == null } -> "NOT CONFIRMED: window position read-back unavailable."
                else -> "NOT CONFIRMED: some windows still report open."
            }
            append("Close windows read-back", "$observed\nOpen % after: ${formatWindows(after)}")
            persist()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !after.values.all { it == 0 }) {
                Toast.makeText(this@CampTestsActivity, observed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun stopWindows() {
        lifecycleScope.launch(NonCancellable) {
            val result = withContext(Dispatchers.IO) { windows.stopAll() }
            append("STOP windows command", "${result.method} -> ${result.detail}")
            render()
            runCatching { persist() }.onFailure { Log.w("CampTestsActivity", "Cannot save stop", it) }
            Toast.makeText(this@CampTestsActivity, if (result.success) "Windows stop sent" else "Stop failed: ${result.detail}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatWindows(positions: Map<String, Int?>) =
        positions.entries.joinToString { (name, open) -> "$name=${open?.let { "$it%" } ?: "?"}" }

    private fun setDrl(on: Boolean) = operation {
        val target = if (on) "ON" else "OFF"
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            append("DRL $target skipped", "Test screen lost foreground before the command.")
            persist()
            return@operation
        }
        withContext(NonCancellable) {
            val before = withContext(Dispatchers.IO) { drl.isOn() }
            val result = withContext(Dispatchers.IO) { drl.set(on) }
            append("Explicit DRL $target command", "${result.method} -> ${result.detail}\nDRL before: ${formatDrl(before)}")
            persist()
            var after = before
            if (result.success) {
                // The car may accept the write but switch only after a moment, or not at all outside Park.
                for (i in 0 until 10) {
                    delay(150)
                    after = withContext(Dispatchers.IO) { drl.isOn() }
                    if (after == on) break
                }
            }
            val observed = when {
                !result.success -> "NOT SENT: ${result.detail}"
                after == on -> "Read-back reports DRL $target."
                after == null -> "NOT CONFIRMED: DRL read-back unavailable."
                else -> "NOT CONFIRMED: car accepted the command but kept DRL ${formatDrl(after)} (it changes them only in Park)."
            }
            append("DRL $target read-back", "$observed\nDRL after: ${formatDrl(after)}")
            persist()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && after != on) {
                Toast.makeText(this@CampTestsActivity, observed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun formatDrl(on: Boolean?) = when (on) {
        true -> "ON"
        false -> "OFF"
        null -> "unknown"
    }

    private fun dim() {
        if (busy || displayTest != null || grantingDim) return
        if (display.canWriteSystem()) {
            startDisplayTest(DisplayTest.DIM)
            return
        }
        grantingDim = true
        status.setText(R.string.camp_test_busy)
        lifecycleScope.launch {
            val grant = AdbPermissionManager.grantWriteSettings(this@CampTestsActivity)
            grantingDim = false
            if (!display.canWriteSystem()) {
                append(
                    "System brightness permission",
                    "Could not grant 'Modify system settings' over local ADB (exit ${grant.exitCode}: ${grant.output.ifBlank { "no output" }}). Trying the BYD brightness setting only.",
                )
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startDisplayTest(DisplayTest.DIM)
        }
    }

    @VisibleForTesting
    internal fun startDisplayTest(test: DisplayTest) {
        if (busy || displayTest != null) return
        val saved = display.save()
        savedDisplay = saved
        displayTest = test
        val result = if (test == DisplayTest.DIM) display.setBrightness(DIM_PERCENT) else display.backlight(false)
        findViewById<TextView>(R.id.campTestDimOverlay).apply {
            // Dimming stays see-through so the real panel change is visible; screen off is fully black.
            setBackgroundColor(if (test == DisplayTest.DIM) 0x33000000 else 0xFF000000.toInt())
            visibility = View.VISIBLE
        }
        handler.postDelayed(restoreDim, 10_000)
        operation {
            val label = if (test == DisplayTest.DIM) "Display dim to $DIM_PERCENT% started" else "Screen backlight OFF started"
            append(label, "Accepted: ${result.success}\n${result.detail}\nPrevious system brightness=${saved.brightness ?: "?"} mode=${saved.mode ?: "?"}\nIf the panel did not visibly change, note that in the observation.")
            persist()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (displayTest != null && event.actionMasked == MotionEvent.ACTION_DOWN) {
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
        val test = displayTest ?: return
        handler.removeCallbacks(restoreDim)
        displayTest = null
        // Restoration itself is synchronous and never depends on a coroutine completing.
        val result = if (test == DisplayTest.DIM) {
            display.restore(savedDisplay ?: BydDisplayController.Saved(null, null))
        } else {
            display.backlight(true)
        }
        findViewById<View>(R.id.campTestDimOverlay).visibility = View.GONE
        append("Display restored", "Reason: $reason\nAccepted: ${result.success}\n${result.detail}")
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

    private fun startShortcutRecording() = operation {
        val probe = AdbPermissionManager.runShellCommand(this, "echo ready")
        if (!probe.output.contains("ready")) {
            append("Car shortcut recording", "Local ADB is unavailable, so the car's log can't be read: ${probe.output.ifBlank { "exit ${probe.exitCode}" }}")
            persist()
            return@operation
        }
        // Logcat timestamps use the head unit's local time, as does this format.
        shortcutStart = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        findViewById<Button>(R.id.campTestShortcut).setText(R.string.camp_test_shortcut_finish)
        append("Car shortcut recording started", getString(R.string.camp_test_shortcut_steps))
        persist()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.camp_test_shortcut_record)
            .setMessage(R.string.camp_test_shortcut_steps)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun finishShortcutRecording() = operation {
        val start = shortcutStart ?: return@operation
        val result = AdbPermissionManager.runShellCommand(
            this,
            "logcat -d -v time -b main,system,events -T '$start' | grep -iE '$SHORTCUT_LOG_PATTERN' | grep -vE '\\( *${android.os.Process.myPid()}\\)|BoundBrokerSvc' | head -n $SHORTCUT_LOG_LINES",
        )
        shortcutStart = null
        findViewById<Button>(R.id.campTestShortcut).setText(R.string.camp_test_shortcut_record)
        val log = result.output.ifBlank { "No matching log lines (exit ${result.exitCode})." }
        append("Car shortcut log since $start", log)
        persist()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.camp_test_shortcut_title)
                .setMessage(log)
                .setPositiveButton(android.R.string.ok, null)
                .show()
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

    private companion object {
        const val DIM_PERCENT = 10
        // Activity starts, intents, key presses and screen power events are what a shortcut can trigger.
        const val SHORTCUT_LOG_PATTERN =
            "START u|act=|cmp=|backlight|screen_toggled|power_screen|gotosleep|go_to_sleep|wakeup|keyevent|keycode|shortcut|am_start|am_broadcast|am_focused|wm_on_|bydauto"
        // Lines from the start of recording, where the shortcut press is.
        const val SHORTCUT_LOG_LINES = 250
    }
}
