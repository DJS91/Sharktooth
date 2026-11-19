package com.sphy.airconcontroller

import android.content.Intent
import android.bluetooth.BluetoothManager
import android.media.AudioManager
import android.os.Build
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
            Intent(context, CampModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as CampModeActivity
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val enable = activity.findViewById<Button>(R.id.campModeEnable)
                val stop = activity.findViewById<Button>(R.id.campModeStopWindows)
                assertEquals(View.GONE, stop.visibility)
                assertEquals(stop.javaClass, enable.javaClass)
                assertEquals(stop.backgroundTintList?.defaultColor, enable.backgroundTintList?.defaultColor)
                assertEquals(stop.textSize, enable.textSize, 0f)
                val list = activity.findViewById<ViewGroup>(R.id.campModeChecklist)
                val rows = (0 until list.childCount).map { list.getChildAt(it) as CheckBox }
                assertTrue(rows.all {
                    (it.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.LEFT
                })
                val ids = listOf(R.id.campStepDikey, R.id.campStepCarPlay, R.id.campStepBluetooth, R.id.campStepClimate, R.id.campStepWindows,
                    R.id.campStepDrls, R.id.campStepCluster, R.id.campStepHud, R.id.campStepMedia, R.id.campStepScreen)
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
                val idle = CampModeSession.State(false, false, "")
                activity.renderControls(idle, false, 80.0, 10)
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
                assertNotEquals(disabledTextColor, enable.currentTextColor)
                activity.renderControls(idle, true, 10.1, 11)
                assertFalse(enable.isEnabled)
                activity.renderControls(CampModeSession.State(true, false, "", remindHeadlights = true),
                    false, null, 10)
                assertEquals(View.VISIBLE, enable.visibility)
                assertTrue(enable.isEnabled)
                assertEquals(activity.getString(R.string.camp_mode_retry_restore), enable.text.toString())
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
    }
}
