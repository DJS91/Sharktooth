package com.sphy.airconcontroller

import android.content.Intent
import android.media.AudioManager
import android.widget.CheckBox
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sphy.airconcontroller.byd.BydAcController
import com.sphy.airconcontroller.camp.CampMediaAudio
import com.sphy.airconcontroller.camp.CampModeController
import com.sphy.airconcontroller.camp.CampModeSession
import com.sphy.airconcontroller.camp.CampSavedState
import com.sphy.airconcontroller.camp.CampStep
import com.sphy.airconcontroller.camp.CampScreenWakeReceiver
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampModeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

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
                assertEquals("Enable Camping Mode", enable.text.toString())
                assertEquals(stop.javaClass, enable.javaClass)
                assertEquals(stop.currentTextColor, enable.currentTextColor)
                assertEquals(stop.backgroundTintList, enable.backgroundTintList)
                assertEquals(stop.textSize, enable.textSize, 0f)
                val list = activity.findViewById<LinearLayout>(R.id.campModeChecklist)
                val rows = (0 until list.childCount).map { list.getChildAt(it) as CheckBox }
                val ids = listOf(R.id.campStepClimate, R.id.campStepWindows, R.id.campStepHeadlights,
                    R.id.campStepDrls, R.id.campStepMedia, R.id.campStepScreen)
                assertEquals(ids, rows.map { it.id })
                for ((index, step) in CampStep.entries.withIndex()) {
                    val completed = CampStep.entries.take(index).toSet()
                    activity.renderChecklist(CampModeSession.State(true, true, "", completed, step))
                    assertEquals(List(rows.size) { it < index }, rows.map { it.isChecked })
                    assertTrue(rows[index].text.contains("applying"))
                    assertTrue("Progress indicators must not be user-editable", rows.none { it.isClickable })
                }
                activity.renderChecklist(CampModeSession.State(true, false, "", CampStep.entries.toSet()))
                assertTrue(rows.all { it.isChecked })
                assertTrue(rows.none { it.text.contains("applying") })
                activity.renderChecklist(CampModeSession.State(false, false, ""))
                assertTrue(rows.none { it.isChecked })
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun mediaMuteAndRestoreLeaveCallAndAlarmVolumesUntouched() {
        val audio = context.getSystemService(AudioManager::class.java)
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
            assertTrue(media.restore(before.volume, before.muted).success)
            assertEquals(before, media.snapshot())
            assertEquals(call, audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL))
            assertEquals(alarm, audio.getStreamVolume(AudioManager.STREAM_ALARM))
        } finally {
            media.restore(original.volume, original.muted)
        }
    }

    @Test fun savedClimateAndAudioSurviveSerializationIncludingMissingReadings() {
        val saved = CampSavedState(
            BydAcController.AcSnapshot(true, true, null, false, 21, null, null, 4,
                2, 1, 2, 1, false, null, true, false, true),
            0, true, false, 2,
        )
        assertEquals(saved, CampModeController.decodeSaved(CampModeController.encodeSaved(saved)))
        val legacy = CampModeController.encodeSaved(saved).apply { remove("headlightMode") }
        assertEquals(saved.copy(headlightMode = null), CampModeController.decodeSaved(legacy))
    }
}
