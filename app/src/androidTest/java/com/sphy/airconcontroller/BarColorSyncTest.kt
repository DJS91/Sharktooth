package com.sphy.airconcontroller

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.button.MaterialButton
import com.sphy.airconcontroller.dikey.LedMode
import com.sphy.airconcontroller.dikey.DiKeyFrameSink
import com.sphy.airconcontroller.dikey.DiKeyProtocol
import com.sphy.airconcontroller.lighting.LightingPeriod
import com.sphy.airconcontroller.lighting.LightingScheduler
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.storage.LedBarState
import com.sphy.airconcontroller.storage.LedRgb
import com.sphy.airconcontroller.storage.LightingProfile
import com.sphy.airconcontroller.ui.ColorSettingCard
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BarColorSyncTest {
    @Test fun changingTheOtherProfileStreamsLatestColoursBeforeDragEnds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = OpenDiKeyApp.from(context).dikey
        assumeFalse("Avoid changing a connected DiKey during this test", session.isConnected)
        val prefs = context.getSharedPreferences("aircon_settings", Context.MODE_PRIVATE)
        val original = prefs.all.filterKeys {
            it.startsWith("lighting_") || it.startsWith("dikey_led_") || it.startsWith("dikey_bl_")
        }
        val handler = Handler(Looper.getMainLooper())
        val writes = mutableListOf<ByteArray>()
        var activity: ColorConfigActivity? = null
        try {
            instrumentation.runOnMainSync {
                if (session.settings.barsSynced) session.settings.setBarsSynced(false)
                session.settings.saveLightingProfile(LightingPeriod.DAY, profile(10))
                session.settings.saveLightingProfile(LightingPeriod.NIGHT, profile(60))
                session.settings.editingLightingPeriod = LightingPeriod.NIGHT
                LightingScheduler.preview(context, LightingPeriod.DAY)
            }
            val page = instrumentation.startActivitySync(
                Intent(context, ColorConfigActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as ColorConfigActivity
            activity = page
            instrumentation.waitForIdleSync()
            val changed = CountDownLatch(1)
            instrumentation.runOnMainSync {
                assertEquals(LightingPeriod.NIGHT, LightingScheduler.currentPeriod())
                session.controller.attachSink(object : DiKeyFrameSink {
                    override fun writeFrame(bytes: ByteArray): Boolean {
                        writes.add(bytes.copyOf())
                        handler.post { session.controller.onWriteComplete(true) }
                        return true
                    }
                })
                session.controller.onTransportReady()
                val left = page.findViewById<ColorSettingCard>(R.id.colorLeft)
                val keys = page.findViewById<ColorSettingCard>(R.id.colorKeys)
                for (step in 0..15) {
                    handler.postDelayed({
                        left.setRgb(100 + step, 30, 40)
                        left.onColorChanged?.invoke(left.red, left.green, left.blue)
                        keys.setRgb(20, 50, 100 + step)
                        keys.onColorChanged?.invoke(keys.red, keys.green, keys.blue)
                        if (step == 15) changed.countDown()
                    }, step * 20L)
                }
            }
            assertTrue(changed.await(5, TimeUnit.SECONDS))
            instrumentation.runOnMainSync {
                val strips = writes.filter { it[3] == DiKeyProtocol.CMD_LED_STRIP && it[5].toInt() == 1 }
                val backlights = writes.filter { it[3] == DiKeyProtocol.CMD_BUTTON_BACKLIGHT }
                assertTrue("Strip writes must continue during a drag", strips.size >= 3)
                assertTrue("Backlight writes must continue during a drag", backlights.size >= 3)
                val left = page.findViewById<ColorSettingCard>(R.id.colorLeft)
                left.onColorCommitted?.invoke(left.red, left.green, left.blue)
                assertEquals(profile(10), session.settings.lightingProfile(LightingPeriod.DAY))
                assertEquals(LedRgb(115, 30, 40), session.settings.lightingProfile(LightingPeriod.NIGHT).bars.getValue(1).color)
            }
            // Let the last queued strip and backlight frames finish before inspecting the sink.
            Thread.sleep(150)
            instrumentation.runOnMainSync {
                assertTrue(writes.any { it.contentEquals(DiKeyProtocol.buildLedStrip(LedMode.ON.code, 1, 115, 30, 40)) })
                assertTrue(writes.any { it.contentEquals(DiKeyProtocol.buildButtonBacklight(20, 50, 115)) })
                session.controller.onTransportDown("Disconnected")
                session.controller.attachSink(null)
                page.finish()
            }
            instrumentation.waitForIdleSync()
        } finally {
            instrumentation.runOnMainSync {
                handler.removeCallbacksAndMessages(null)
                session.controller.onTransportDown("Disconnected")
                session.controller.attachSink(null)
                activity?.finish()
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                LightingScheduler.endPreview(context)
                val edit = prefs.edit()
                prefs.all.keys.filter {
                    it.startsWith("lighting_") || it.startsWith("dikey_led_") || it.startsWith("dikey_bl_")
                }.forEach(edit::remove)
                for ((key, value) in original) {
                    when (value) {
                        is String -> edit.putString(key, value)
                        is Int -> edit.putInt(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        else -> error("Unexpected lighting preference type for $key")
                    }
                }
                edit.commit()
                session.seedFromSettings()
            }
        }
    }

    @Test fun syncPreservesSeparateAndSharedChoicesForBothPeriods() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "bar_sync_test_${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(base) {
            override fun getSharedPreferences(nameIgnored: String?, mode: Int) =
                base.getSharedPreferences(name, mode)
        }
        try {
            val settings = AppSettings(isolated)
            val day = profile(10)
            val night = profile(60)
            settings.saveLightingProfile(LightingPeriod.DAY, day)
            settings.saveLightingProfile(LightingPeriod.NIGHT, night)

            settings.setBarsSynced(true)
            assertTrue(AppSettings(isolated).barsSynced)
            for ((period, original) in listOf(LightingPeriod.DAY to day, LightingPeriod.NIGHT to night)) {
                val synced = settings.lightingProfile(period)
                assertEquals(List(3) { original.bars.getValue(1).color }, (1..3).map { synced.bars.getValue(it).color })
                assertEquals((1..3).map { original.bars.getValue(it).mode }, (1..3).map { synced.bars.getValue(it).mode })
                assertEquals(original.backlight, synced.backlight)
                settings.editingLightingPeriod = period
                val color = if (period == LightingPeriod.DAY) LedRgb(1, 2, 3) else LedRgb(4, 5, 6)
                for (bar in 1..3) {
                    settings.updateEditingBar(bar, synced.bars.getValue(bar).mode, color.red, color.green, color.blue)
                }
            }
            AppSettings(isolated).setBarsSynced(false)
            assertFalse(settings.barsSynced)
            assertEquals(day, settings.lightingProfile(LightingPeriod.DAY))
            assertEquals(night, settings.lightingProfile(LightingPeriod.NIGHT))
            settings.editingLightingPeriod = LightingPeriod.DAY
            settings.updateEditingBar(2, day.bars.getValue(2).mode, 90, 91, 92)

            settings.setBarsSynced(true)
            assertEquals(List(3) { LedRgb(1, 2, 3) }, (1..3).map { settings.lightingProfile(LightingPeriod.DAY).bars.getValue(it).color })
            assertEquals(List(3) { LedRgb(4, 5, 6) }, (1..3).map { settings.lightingProfile(LightingPeriod.NIGHT).bars.getValue(it).color })
            settings.setBarsSynced(false)
            assertEquals(LedRgb(90, 91, 92), settings.lightingProfile(LightingPeriod.DAY).bars.getValue(2).color)
            assertEquals(night, settings.lightingProfile(LightingPeriod.NIGHT))
        } finally {
            base.deleteSharedPreferences(name)
        }
    }

    @Test fun syncButtonChangesControlsAndRestoresColours() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val session = OpenDiKeyApp.from(context).dikey
        assumeFalse("Avoid changing a connected DiKey during this test", session.isConnected)
        val prefs = context.getSharedPreferences("aircon_settings", Context.MODE_PRIVATE)
        fun isLightingKey(key: String) = key.startsWith("lighting_") ||
            key.startsWith("dikey_led_") || key.startsWith("dikey_bl_")
        val original = prefs.all.filterKeys(::isLightingKey)
        val settings = session.settings
        val day = profile(10)
        val night = profile(60)
        var activity: ColorConfigActivity? = null
        try {
            instrumentation.runOnMainSync {
                if (settings.barsSynced) settings.setBarsSynced(false)
                prefs.edit().remove("lighting_synced_color_day").remove("lighting_synced_color_night").apply()
                settings.saveLightingProfile(LightingPeriod.DAY, day)
                settings.saveLightingProfile(LightingPeriod.NIGHT, night)
                settings.editingLightingPeriod = LightingPeriod.DAY
            }
            val page = instrumentation.startActivitySync(
                Intent(context, ColorConfigActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as ColorConfigActivity
            activity = page
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val toggle = page.findViewById<MaterialButtonToggleGroup>(R.id.colorSyncGroup)
                val left = page.findViewById<ColorSettingCard>(R.id.colorLeft)
                val middle = page.findViewById<ColorSettingCard>(R.id.colorMiddle)
                val right = page.findViewById<ColorSettingCard>(R.id.colorRight)
                val keys = page.findViewById<ColorSettingCard>(R.id.colorKeys)
                val button = page.findViewById<MaterialButton>(R.id.colorSyncBars)
                val period = page.findViewById<MaterialButtonToggleGroup>(R.id.colorPeriodToggle)
                button.performClick()
                assertEquals(R.id.colorSyncBars, toggle.checkedButtonId)
                assertTrue(settings.barsSynced)
                assertEquals(View.GONE, middle.visibility)
                assertEquals(View.GONE, right.visibility)
                assertEquals(View.VISIBLE, keys.visibility)
                assertEquals(context.getString(R.string.color_all_bars), left.findViewById<TextView>(R.id.colorSettingTitle).text)
                assertEquals(View.INVISIBLE, left.findViewById<View>(R.id.colorSettingMode).visibility)
                assertEquals(List(3) { day.bars.getValue(1).color }, (1..3).map { settings.colorForBar(it) })

                left.setRgb(12, 34, 56)
                left.onColorChanged?.invoke(left.red, left.green, left.blue)
                period.check(R.id.colorPeriodNight)
                assertEquals(List(3) { LedRgb(12, 34, 56) }, (1..3).map { settings.lightingProfile(LightingPeriod.DAY).bars.getValue(it).color })
                assertEquals(List(3) { night.bars.getValue(1).color }, (1..3).map { settings.colorForBar(it) })
                left.setRgb(78, 90, 12)
                left.onColorChanged?.invoke(left.red, left.green, left.blue)
                period.check(R.id.colorPeriodDay)
                assertEquals(List(3) { LedRgb(78, 90, 12) }, (1..3).map { settings.lightingProfile(LightingPeriod.NIGHT).bars.getValue(it).color })
                button.performClick()
                assertFalse(settings.barsSynced)
                assertEquals(View.VISIBLE, middle.visibility)
                assertEquals(View.VISIBLE, right.visibility)
                assertEquals(day, settings.lightingProfile(LightingPeriod.DAY))
                assertEquals(night, settings.lightingProfile(LightingPeriod.NIGHT))

                button.performClick()
                assertEquals(List(3) { LedRgb(12, 34, 56) }, (1..3).map { settings.colorForBar(it) })
                assertEquals(List(3) { LedRgb(78, 90, 12) }, (1..3).map { settings.lightingProfile(LightingPeriod.NIGHT).bars.getValue(it).color })
                assertEquals((1..3).map { day.bars.getValue(it).mode }, (1..3).map { settings.modeForBar(it) })
                assertEquals(day.backlight, settings.lightingProfile(LightingPeriod.DAY).backlight)
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val edit = prefs.edit()
                prefs.all.keys.filter(::isLightingKey).forEach(edit::remove)
                for ((key, value) in original) {
                    when (value) {
                        is String -> edit.putString(key, value)
                        is Int -> edit.putInt(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        else -> error("Unexpected lighting preference type for $key")
                    }
                }
                edit.commit()
                session.seedFromSettings()
            }
        }
    }

    private fun profile(base: Int) = LightingProfile(
        bars = mapOf(
            1 to LedBarState(LedMode.ON.code, LedRgb(base, 1, 2)),
            2 to LedBarState(LedMode.BLINK.code, LedRgb(base + 1, 3, 4)),
            3 to LedBarState(LedMode.BREATH.code, LedRgb(base + 2, 5, 6)),
        ),
        backlight = LedRgb(7, 8, 9),
    )
}
