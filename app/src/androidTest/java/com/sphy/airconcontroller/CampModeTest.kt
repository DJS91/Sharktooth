package com.sphy.airconcontroller

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.bluetooth.BluetoothManager
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Gravity
import android.widget.CheckBox
import android.widget.Button
import android.widget.TextView
import android.view.ViewGroup
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.byd.Dilink5SdkInjector
import com.sphy.airconcontroller.camp.CampCarPlay
import com.sphy.airconcontroller.camp.CampActionError
import com.sphy.airconcontroller.camp.CampMediaAudio
import com.sphy.airconcontroller.camp.CampHeadunitBluetooth
import com.sphy.airconcontroller.camp.CampModeController
import com.sphy.airconcontroller.camp.CampModeSession
import com.sphy.airconcontroller.camp.CampSavedState
import com.sphy.airconcontroller.camp.CampStep
import com.sphy.airconcontroller.camp.CampScreenWakeReceiver
import com.sphy.airconcontroller.dikey.LedMode
import com.sphy.airconcontroller.storage.LedBarState
import com.sphy.airconcontroller.storage.LedRestoreSnapshot
import com.sphy.airconcontroller.storage.LedRgb
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampModeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun activeCarPlayIdLookupSurvivesVehicleSdkInjectionWithoutDisconnecting() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            assumeTrue("Requires installed BYD vehicle SDK", Dilink5SdkInjector.ensure(context.applicationContext))
            val client = CampCarPlay.bind(context.applicationContext)
            assumeTrue("Requires installed OEM CarPlay", client != null)
            checkNotNull(client).use {
                assumeTrue("Requires an active CarPlay phone for the missing-ID fallback", it.state() == 2)
                val ids = it.activeDeviceIds()
                assertTrue("Active phone must resolve to a real device ID", ids.isNotEmpty())
                assertTrue(ids.all { id -> id.isNotBlank() })
                assertEquals("Read-only lookup must not disconnect CarPlay", 2, it.state())
            }
        }

    @Test fun headunitBluetoothSwitchesOffAndBackOnUsingAndroidAdapterReadback() = runBlocking {
        assumeTrue("Bluetooth toggling integration test is emulator-only",
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val adapter = checkNotNull(context.getSystemService(BluetoothManager::class.java)?.adapter)
        val original = adapter.isEnabled
        val bluetooth = CampHeadunitBluetooth(context)
        try {
            bluetooth.prepare()
            val off = bluetooth.setEnabled(false)
            assertTrue(off.detail, off.success)
            assertFalse(adapter.isEnabled)
            val on = bluetooth.setEnabled(true)
            assertTrue(on.detail, on.success)
            assertTrue(adapter.isEnabled)
        } finally {
            val restored = bluetooth.setEnabled(original)
            assertTrue("Restore emulator Bluetooth: ${restored.detail}", restored.success)
        }
    }

    @Test fun wakeSignalsOnlyTriggerRestorationForScreenOnOrUnlock() {
        var wakes = 0
        val receiver = CampScreenWakeReceiver { wakes++ }
        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_OFF))
        assertEquals(0, wakes)
        receiver.onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        assertEquals(1, wakes)
        receiver.onReceive(context, Intent(Intent.ACTION_USER_PRESENT))
        assertEquals(2, wakes)
    }

    @Test fun checklistChecksOnlyConfirmedActionsAndFinishesWithScreenOff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(context, CampModeActivity::class.java)
                .putExtra(CampModeActivity.EXTRA_LIVE_CAMP_MODE, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as CampModeActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val enable = activity.findViewById<Button>(R.id.campModeEnable)
                val saveSoc = activity.findViewById<android.widget.SeekBar>(R.id.campSaveSocTarget)
                val saveSocSwitch = activity.findViewById<android.widget.CompoundButton>(R.id.campSaveSocSwitch)
                assertTrue(activity.findViewById<TextView>(R.id.campSaveSocHeading).typeface.isBold)
                assertTrue(activity.findViewById<TextView>(R.id.campSaveSocHelp).text.isNotBlank())
                val saveSocValue = activity.findViewById<TextView>(R.id.campSaveSocValue)
                activity.renderSaveSoc(CampModeController.SaveSocStatus())
                assertFalse(saveSocSwitch.isEnabled)
                assertFalse(saveSoc.isEnabled)
                assertEquals(activity.getString(R.string.camp_save_soc_unavailable), saveSocValue.text.toString())
                activity.renderSaveSoc(CampModeController.SaveSocStatus(
                    com.sphy.airconcontroller.byd.BydSaveSocController.Reading(null, 45, 100),
                ))
                assertFalse(saveSocSwitch.isEnabled)
                assertFalse(saveSoc.isEnabled)
                assertEquals(45, saveSoc.progress)
                assertEquals(activity.getString(R.string.camp_save_soc_switch_unavailable),
                    activity.findViewById<TextView>(R.id.campSaveSocHelp).text.toString())
                activity.renderSaveSoc(CampModeController.SaveSocStatus(
                    com.sphy.airconcontroller.byd.BydSaveSocController.Reading(false, 45, 100),
                ))
                assertEquals(activity.getString(R.string.camp_save_soc_help),
                    activity.findViewById<TextView>(R.id.campSaveSocHelp).text.toString())
                assertTrue(saveSocSwitch.isEnabled)
                assertFalse(saveSocSwitch.isChecked)
                assertFalse(saveSoc.isEnabled)
                assertEquals(45, saveSoc.progress)
                activity.renderSaveSoc(CampModeController.SaveSocStatus(
                    com.sphy.airconcontroller.byd.BydSaveSocController.Reading(true, 60, 70),
                ))
                assertTrue(saveSocSwitch.isChecked)
                assertTrue(saveSoc.isEnabled)
                assertEquals(60, saveSoc.progress)
                assertEquals(70, saveSoc.max)
                val stop = activity.findViewById<Button>(R.id.campModeStopWindows)
                assertEquals(View.GONE, stop.visibility)
                assertEquals(stop.javaClass, enable.javaClass)
                assertEquals(activity.getColor(R.color.dashboard_accent_blue), enable.backgroundTintList?.defaultColor)
                assertEquals(stop.textSize, enable.textSize, 0f)
                val list = activity.findViewById<ViewGroup>(R.id.campModeChecklist)
                val rows = (0 until 2).flatMap { column ->
                    (0 until list.childCount).map { index ->
                        (list.getChildAt(index) as ViewGroup).getChildAt(column) as CheckBox
                    }
                }
                assertTrue(rows.all {
                    (it.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT
                })
                val ids = listOf(R.id.campStepDikey, R.id.campStepBluetooth, R.id.campStepWindows,
                    R.id.campStepCluster, R.id.campStepMedia, R.id.campStepCarPlay, R.id.campStepClimate,
                    R.id.campStepDrls, R.id.campStepHud, R.id.campStepScreen)
                assertEquals(ids, rows.map { it.id })
                for ((index, step) in CampStep.entries.withIndex()) {
                    val completed = CampStep.entries.take(index).toSet()
                    activity.renderChecklist(CampModeSession.State(true, true, "", completed, step))
                    activity.renderControls(CampModeSession.State(true, true, "", completed, step), true, 80.0, 10)
                    assertEquals(View.VISIBLE, stop.visibility)
                    assertEquals(List(rows.size) { it < index }, rows.map { it.isChecked })
                    assertTrue(rows[index].text.contains("applying"))
                    assertTrue("Progress indicators must not be user-editable", rows.none { it.isClickable })
                }
                activity.renderChecklist(CampModeSession.State(true, false, "", CampStep.entries.toSet()))
                activity.renderControls(CampModeSession.State(true, false, "", CampStep.entries.toSet()), true, 80.0, 10)
                assertEquals(View.GONE, stop.visibility)
                assertTrue(rows.all { it.isChecked })
                assertTrue(rows.none { it.text.contains("applying") })
                activity.renderChecklist(CampModeSession.State(false, false, ""))
                assertTrue(rows.none { it.isChecked })
                assertEquals("Turn Controls Bar Lights off", rows[0].text.toString())
                assertEquals("Disconnect Phone", rows[5].text.toString())
                val idle = CampModeSession.State(false, false, "")
                val windowSafety = activity.findViewById<View>(R.id.campModeWindowSafety)
                activity.renderControls(idle, false, 80.0, 10)
                assertEquals(View.GONE, windowSafety.visibility)
                assertEquals(View.VISIBLE, enable.visibility)
                assertFalse(enable.isEnabled)
                assertEquals("Waiting for vehicle Park Gear", enable.text.toString())
                val disabledTextColor = enable.currentTextColor
                val status = activity.findViewById<TextView>(R.id.campModeStatus)
                assertEquals(View.GONE, status.visibility)
                assertEquals("", status.text.toString())
                activity.renderControls(idle, true, null, 10)
                assertFalse(enable.isEnabled)
                assertEquals("Enable Camping Mode", enable.text.toString())
                assertEquals(View.VISIBLE, status.visibility)
                activity.renderControls(idle, true, 10.0, 10)
                assertFalse(enable.isEnabled)
                activity.renderControls(idle, true, 10.1, 10)
                assertTrue(enable.isEnabled)
                assertEquals(View.VISIBLE, windowSafety.visibility)
                assertEquals(View.GONE, status.visibility)
                assertEquals("", status.text.toString())
                assertNotEquals(disabledTextColor, enable.currentTextColor)
                activity.renderControls(idle, true, 10.1, 11)
                assertFalse(enable.isEnabled)
                activity.renderControls(CampModeSession.State(true, false, "", remindHeadlights = true),
                    false, null, 10)
                assertEquals(View.VISIBLE, enable.visibility)
                assertFalse(enable.isEnabled)
                assertEquals(activity.getString(R.string.camp_mode_enable), enable.text.toString())
                assertNull(activity.headlightReminderDialog)
                activity.renderControls(CampModeSession.State(false, false, "", remindHeadlights = true),
                    false, null, 10)
                assertTrue(activity.headlightReminderDialog?.isShowing == true)
                assertEquals(
                    activity.getString(R.string.camp_mode_headlight_reminder),
                    activity.headlightReminderDialog?.findViewById<TextView>(android.R.id.message)?.text.toString(),
                )
                activity.renderControls(idle, true, 80.0, 10)
                assertTrue(activity.headlightReminderDialog?.isShowing == true)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun debugPreviewShowsParkAndBatteryAndSimulatesWithoutChangingDeviceOrLiveSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(context, CampModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as CampModeActivity
        val live = CampModeController.get(context)
        val liveState = live.state.value
        val prefs = context.getSharedPreferences("camp-mode", android.content.Context.MODE_PRIVATE)
        val originalPrefs = prefs.all
        val audio = context.getSystemService(AudioManager::class.java)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val bluetoothEnabled = adapter?.isEnabled
        val ids = listOf(
            R.id.campStepDikey, R.id.campStepCarPlay, R.id.campStepBluetooth, R.id.campStepClimate,
            R.id.campStepWindows, R.id.campStepDrls, R.id.campStepCluster, R.id.campStepHud,
            R.id.campStepMedia, R.id.campStepScreen,
        )
        fun waitForUi(condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var ready = false
            while (!ready && SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync { ready = condition() }
                if (!ready) SystemClock.sleep(50)
            }
            assertTrue("Preview did not reach the expected state", ready)
        }
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.campModePreviewNotice).visibility)
                assertEquals("P", activity.findViewById<TextView>(R.id.campModeGear).text.toString())
                assertEquals("76%", activity.findViewById<TextView>(R.id.campModeSoc).text.toString())
                val start = activity.findViewById<Button>(R.id.campModeEnable)
                assertEquals("Start Camping Mode", start.text.toString())
                assertTrue(start.isEnabled)
                val blueButtons = listOf(start,
                    activity.findViewById<Button>(R.id.campModeCutoffDecrease),
                    activity.findViewById<Button>(R.id.campModeCutoffIncrease))
                for (button in blueButtons) {
                    assertEquals(Color.BLACK, button.currentTextColor)
                    val buttonBitmap = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
                    button.draw(Canvas(buttonBitmap))
                    assertEquals("Start and cutoff buttons should have an opaque blue fill",
                        Color.rgb(0, 144, 255), buttonBitmap.getPixel(button.width / 8, button.height / 2))
                    buttonBitmap.recycle()
                }
                val note = activity.findViewById<TextView>(R.id.campModeHeadlightNote)
                assertEquals("Headlights must be turned off manually as this is a ADR requirement.", note.text.toString())
                assertTrue("The note belongs below Start", note.top >= start.bottom)
                val overview = activity.findViewById<View>(R.id.campModeOverview)
                val options = activity.findViewById<View>(R.id.campModeOptions)
                assertTrue("The cards should have equal widths, allowing for pixel rounding",
                    kotlin.math.abs(overview.width - options.width) <= 1)
                val checklist = activity.findViewById<ViewGroup>(R.id.campModeChecklist)
                val checklistParent = checklist.parent as ViewGroup
                val actionsHeading = checklistParent.getChildAt(checklistParent.indexOfChild(checklist) - 1)
                assertEquals("Leave 10 pixels between Actions and the columns", 10, checklist.top - actionsHeading.bottom)
                val windowSafety = activity.findViewById<TextView>(R.id.campModeWindowSafety)
                for (textView in listOf(note, windowSafety)) {
                    assertEquals(Gravity.CENTER_HORIZONTAL, textView.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
                    assertEquals("Both notes should share the Start button's horizontal centre",
                        start.left + start.right, textView.left + textView.right)
                }
                val text = note.text as android.text.Spanned
                val bold = text.getSpans(0, text.length, android.text.style.StyleSpan::class.java).single()
                val orange = text.getSpans(0, text.length, android.text.style.ForegroundColorSpan::class.java).single()
                assertEquals(android.graphics.Typeface.BOLD, bold.style)
                assertEquals(activity.getColor(R.color.status_warning), orange.foregroundColor)
                for (span in listOf(bold, orange)) {
                    assertEquals("Headlights must be turned off manually",
                        text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).toString())
                }
                assertFalse(activity.findViewById<View>(R.id.campSaveSocSwitch).isEnabled)
                start.performClick()
            }
            waitForUi { activity.findViewById<View>(R.id.campModeStopWindows).visibility == View.VISIBLE }
            waitForUi {
                ids.all { activity.findViewById<CheckBox>(it).isChecked } &&
                    activity.findViewById<TextView>(R.id.campModeHeading).text ==
                    activity.getString(R.string.camp_mode_on)
            }
            instrumentation.runOnMainSync {
                assertEquals(View.GONE, activity.findViewById<View>(R.id.campModeStopWindows).visibility)
                assertTrue(context.getSystemService(PowerManager::class.java).isInteractive)
                val now = SystemClock.uptimeMillis()
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(now, now, action, 1f, 1f, 0)
                    try { assertTrue(activity.dispatchTouchEvent(event)) } finally { event.recycle() }
                }
            }
            waitForUi {
                activity.headlightReminderDialog?.isShowing == true &&
                    ids.none { activity.findViewById<CheckBox>(it).isChecked }
            }
            assertEquals(liveState, live.state.value)
            assertEquals(originalPrefs, prefs.all)
            assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertEquals(bluetoothEnabled, adapter?.isEnabled)
            instrumentation.runOnMainSync {
                assertTrue(activity.findViewById<Button>(R.id.campModeEnable).isEnabled)
                activity.headlightReminderDialog?.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.performClick()
            }
            assertEquals(originalPrefs, prefs.all)
        } finally {
            instrumentation.runOnMainSync {
                activity.headlightReminderDialog?.dismiss()
                activity.finish()
            }
        }
    }

    @Test fun completedChecklistCirclesContainWhiteTicks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(context, CampModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as CampModeActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                activity.renderChecklist(CampModeSession.State(
                    active = false, busy = false, report = "", completedSteps = emptySet(),
                ))
                val uncheckedList = activity.findViewById<ViewGroup>(R.id.campModeChecklist)
                for (i in 0 until uncheckedList.childCount) {
                    val row = uncheckedList.getChildAt(i) as ViewGroup
                    for (j in 0 until row.childCount) {
                        val box = row.getChildAt(j) as CheckBox
                        val drawable = checkNotNull(androidx.core.widget.CompoundButtonCompat.getButtonDrawable(box))
                        val image = Bitmap.createBitmap(
                            drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888,
                        )
                        try {
                            drawable.setBounds(0, 0, image.width, image.height)
                            drawable.draw(Canvas(image))
                            val tickPixel = image.getPixel(image.height * 10 / 24, image.height * 15 / 24)
                            assertEquals("Unchecked circle should contain a grey tick",
                                Color.parseColor("#FF8E8E99"), tickPixel)
                            assertFalse(box.isChecked)
                            assertFalse(box.isClickable)
                        } finally {
                            image.recycle()
                        }
                    }
                }
                activity.renderChecklist(CampModeSession.State(
                    active = true, busy = false, report = "", completedSteps = CampStep.entries.toSet(),
                ))
                val checklist = activity.findViewById<ViewGroup>(R.id.campModeChecklist)
                for (i in 0 until checklist.childCount) {
                    val row = checklist.getChildAt(i) as ViewGroup
                    for (j in 0 until row.childCount) {
                        val box = row.getChildAt(j) as CheckBox
                        val drawable = checkNotNull(androidx.core.widget.CompoundButtonCompat.getButtonDrawable(box))
                        val image = Bitmap.createBitmap(
                            drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888,
                        )
                        try {
                            drawable.setBounds(0, 0, image.width, image.height)
                            drawable.draw(Canvas(image))
                            val pixels = IntArray(image.width * image.height)
                            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                            assertTrue("Completed circle must retain its green fill", Color.parseColor("#FF3DDC84") in pixels)
                            assertTrue("Completed circle must show a white tick", Color.WHITE in pixels)
                            assertFalse(box.isClickable)
                        } finally {
                            image.recycle()
                        }
                    }
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun failuresAreRedBelowTheLeftCardAndListedInTheReminderUntilRecovery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(context, CampModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as CampModeActivity
        val failed = CampModeSession.State(
            active = false, busy = false, report = "", completedSteps = emptySet(),
            remindHeadlights = true, failedSteps = mapOf(
                CampStep.HUD to CampActionError("Restore HUD", "HUD read-back failed", true),
                CampStep.WINDOWS to CampActionError("Close windows", "Window command timed out"),
            ),
            sessionError = "The saved session may return after an app restart; verify restoration manually.",
        )
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                activity.renderChecklist(failed)
                activity.renderControls(failed, true, 76.0, 10)
                val start = activity.findViewById<Button>(R.id.campModeEnable)
                assertTrue(start.isEnabled)
                assertEquals("Start Camping Mode", start.text.toString())
                val errors = activity.findViewById<TextView>(R.id.campModeErrors)
                assertEquals(View.VISIBLE, errors.visibility)
                assertTrue(errors.text.contains("HUD read-back failed"))
                assertTrue(errors.text.contains("Window command timed out"))
                val hud = activity.findViewById<CheckBox>(R.id.campStepHud)
                assertTrue(hud.isActivated)
                assertFalse(hud.isChecked)
                val drawable = checkNotNull(androidx.core.widget.CompoundButtonCompat.getButtonDrawable(hud))
                val image = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
                try {
                    drawable.setBounds(0, 0, image.width, image.height)
                    drawable.draw(Canvas(image))
                    val pixels = IntArray(image.width * image.height)
                    image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                    assertTrue(activity.getColor(R.color.tyre_fault) in pixels)
                    assertTrue(Color.WHITE in pixels)
                    assertFalse(Color.parseColor("#FF3DDC84") in pixels)
                } finally {
                    image.recycle()
                }
                val reminder = checkNotNull(activity.headlightReminderDialog)
                assertTrue(reminder.isShowing)
                val message = checkNotNull(reminder.findViewById<TextView>(android.R.id.message)).text.toString()
                assertTrue(message.contains(activity.getString(R.string.camp_mode_headlight_reminder)))
                assertTrue(message.contains("perform these failed recovery actions manually"))
                assertTrue(message.contains("Restore HUD"))
                assertTrue(message.contains("Check that all windows are closed"))
                assertTrue(message.contains("saved session may return after an app restart"))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val overview = activity.findViewById<View>(R.id.campModeOverview)
                val errors = activity.findViewById<TextView>(R.id.campModeErrors)
                assertSame(overview.parent, errors.parent)
                assertTrue("Errors should sit below the left card", errors.top >= overview.bottom)
                activity.renderControls(failed.copy(busy = true, restoring = true), true, 76.0, 10)
                val resolved = failed.copy(active = false, completedSteps = emptySet(), failedSteps = emptyMap(),
                    sessionError = null)
                activity.renderChecklist(resolved)
                activity.renderControls(resolved, true, 76.0, 10)
                assertEquals(View.GONE, errors.visibility)
                val hud = activity.findViewById<CheckBox>(R.id.campStepHud)
                assertFalse(hud.isActivated)
                assertFalse(hud.isChecked)
                val reminder = checkNotNull(activity.headlightReminderDialog)
                assertTrue(reminder.isShowing)
                assertFalse(checkNotNull(reminder.findViewById<TextView>(android.R.id.message)).text
                    .toString().contains("Restore HUD"))
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.headlightReminderDialog?.dismiss()
                activity.finish()
            }
        }
    }

    @Test fun mediaMuteAndRestoreLeaveCallAndAlarmVolumesUntouched() = kotlinx.coroutines.runBlocking {
        val audio = context.getSystemService(AudioManager::class.java)
        org.junit.Assume.assumeFalse("This test exercises Android stream volume, not OEM car audio.", audio.isVolumeFixed)
        val media = CampMediaAudio(context)
        val original = media.snapshot()
        val call = audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val alarm = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            val before = media.snapshot()
            assertTrue(media.mute().success)
            assertTrue(media.mute().success)
            assertEquals(0, media.snapshot().volume)
            assertTrue(media.restore(before.volume, before.muted, before.usesCarMute).success)
            assertEquals(before, media.snapshot())
            assertEquals(call, audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
            assertEquals(alarm, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        } finally {
            media.restore(original.volume, original.muted, original.usesCarMute)
        }
    }

    @Test fun savedClimateAndAudioSurviveSerializationIncludingMissingReadings() {
        val saved = CampSavedState(
            BydAcController.AcSnapshot(true, true, null, false, 21, null, null, 4,
                2, 1, 2, 1, false, null, true, false, true),
            0, true, false,
            LedRestoreSnapshot((1..3).associateWith {
                LedBarState(LedMode.ON.code, LedRgb(4 * it, 8 * it, 16 * it))
            }, LedRgb(4, 8, 16)), true, 7, 2, mediaUsesCarMute = true,
        )
        val encoded = CampModeController.encodeSaved(saved)
        assertFalse(encoded.has("headlightMode"))
        assertEquals(saved, CampModeController.decodeSaved(encoded))
        assertEquals(saved, CampModeController.decodeSaved(encoded.put("headlightMode", 2)))
        val legacy = CampModeController.encodeSaved(saved).apply {
            remove("headlightMode")
            remove("dikeyLighting")
            remove("manageHeadunitBluetooth")
            remove("clusterBrightness")
            remove("hudState")
            remove("mediaUsesCarMute")
        }
        assertEquals(saved.copy(dikeyLighting = null, manageHeadunitBluetooth = false,
            clusterBrightness = null, hudState = null, mediaUsesCarMute = false),
            CampModeController.decodeSaved(legacy))
        val errors = mapOf(
            CampStep.HUD to CampActionError("Restore HUD", "Not confirmed", true),
            CampStep.WINDOWS to CampActionError("Close windows", "Timed out"),
        )
        assertEquals(errors, CampModeController.decodeErrors(CampModeController.encodeErrors(errors)))
    }
}
