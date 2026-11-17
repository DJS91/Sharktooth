package com.sphy.airconcontroller

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.ScrollView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.swipeUp
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withId
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.hamcrest.CoreMatchers.containsString
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CampTestsActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val sessionFile get() = File(instrumentation.targetContext.filesDir, "camp-test-session.json")
    private var previousSession: ByteArray? = null
    private var activity: CampTestsActivity? = null

    private val resolver get() = instrumentation.targetContext.contentResolver
    private var previousBrightness = 0
    private var previousMode = 0

    @Before fun preserveSession() {
        previousSession = if (sessionFile.exists()) sessionFile.readBytes() else null
        if (sessionFile.exists()) check(sessionFile.delete())
        shell("appops set ${instrumentation.targetContext.packageName} WRITE_SETTINGS allow")
        // A known auto-mode, mid-level start proves the test restores both values.
        previousBrightness = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
        previousMode = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 150)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
    }

    @After fun restoreSession() {
        activity?.let { page ->
            awaitReady(page)
            instrumentation.runOnMainSync { page.finish() }
            instrumentation.waitForIdleSync()
        }
        shell("appops set ${instrumentation.targetContext.packageName} WRITE_SETTINGS allow")
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, previousBrightness)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, previousMode)
        val previous = previousSession
        if (previous != null) sessionFile.writeBytes(previous)
        else if (sessionFile.exists()) check(sessionFile.delete())
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { fd ->
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }

    private fun assertSystemBrightness(level: Int, mode: Int) {
        assertEquals(level, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS))
        assertEquals(mode, Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE))
    }

    @Test fun shortcutRecordingReportsOnScreenWithoutExport() {
        val page = open()
        awaitReady(page)
        instrumentation.runOnMainSync { page.findViewById<View>(R.id.campTestShortcut).performClick() }
        awaitReady(page)
        instrumentation.runOnMainSync {
            val text = page.findViewById<TextView>(R.id.campTestReport).text.toString()
            assertTrue(text, text.contains("Local ADB is unavailable") || text.contains("Car shortcut recording started"))
        }
    }

    @Test fun displayDimNeedsNoSettingsScreenWhenPermissionIsMissing() {
        shell("appops set ${instrumentation.targetContext.packageName} WRITE_SETTINGS deny")
        val page = open()
        awaitReady(page)
        instrumentation.runOnMainSync { page.findViewById<View>(R.id.campTestDim).performClick() }
        val text = awaitReport(page, "Display dim to 10% started")
        assertTrue(text, text.contains("Display dim to 10% started"))
        // The emulator has no authorised local ADB, so the app must report it and still run the BYD route.
        assertTrue(text, Settings.System.canWrite(instrumentation.targetContext) || text.contains("Could not grant"))
        instrumentation.runOnMainSync {
            assertTrue(page.hasWindowFocus())
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, 20f, 20f, 0)
                try { page.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            assertEquals(View.GONE, page.findViewById<View>(R.id.campTestDimOverlay).visibility)
        }
    }

    @Test fun capturesCompareAndSurviveReopeningWithoutVehicleSdk() {
        var page = open()
        instrumentation.runOnMainSync {
            page.findViewById<View>(R.id.campTestCapture).performClick()
            assertEquals(page.getString(R.string.camp_test_baseline_needed),
                page.findViewById<TextView>(R.id.campTestStatus).text.toString())
            page.findViewById<View>(R.id.campTestBaseline).performClick()
        }
        awaitReady(page)
        instrumentation.runOnMainSync {
            page.findViewById<EditText>(R.id.campTestNote).setText("Manual test observation")
            page.findViewById<View>(R.id.campTestCapture).performClick()
        }
        awaitReady(page)
        instrumentation.runOnMainSync {
            val text = page.findViewById<TextView>(R.id.campTestReport).text.toString()
            assertTrue(text.contains("Differences from baseline:"))
            assertTrue(text.contains("Manual test observation"))
            assertTrue(text.contains("Headlights.selector"))
            assertTrue(text.contains("Headlights.readPaths = "))
            page.finish()
        }
        instrumentation.waitForIdleSync()
        page = open()
        instrumentation.runOnMainSync {
            val text = page.findViewById<TextView>(R.id.campTestReport).text.toString()
            assertTrue(text.contains("Manual test observation"))
            assertTrue("Headlight diagnostics must survive reopening", text.contains("Headlights.readPaths = "))
            page.findViewById<View>(R.id.campTestCapture).performClick()
        }
        awaitReady(page)
        instrumentation.runOnMainSync {
            assertTrue(page.findViewById<TextView>(R.id.campTestReport).text.contains("Differences from baseline:"))
            val report = page.findViewById<TextView>(R.id.campTestReport)
            (report.parent.parent as ScrollView).fullScroll(View.FOCUS_DOWN)
        }
        onView(withId(R.id.campTestShowReport)).check(matches(isDisplayed())).perform(click())
        onView(withId(android.R.id.message))
            .check(matches(isDisplayed()))
            .check(matches(withText(containsString("Manual test observation"))))
            .check { view, error ->
                if (error != null) throw error
                val body = view as TextView
                assertTrue("On-screen results must be selectable", body.isTextSelectable)
                assertEquals(page.getColor(R.color.text_primary), body.currentTextColor)
                assertTrue("Long results must scroll within the viewer",
                    generateSequence(body.parent) { it.parent }.filterIsInstance<View>()
                        .any { it.canScrollVertically(1) })
            }
        onView(withId(androidx.appcompat.R.id.scrollView)).perform(swipeUp()).check { view, error ->
            if (error != null) throw error
            assertTrue("Swiping must reveal more of the saved report", view.scrollY > 0)
        }
        onView(withText(android.R.string.ok)).perform(click())
        instrumentation.runOnMainSync { assertTrue("Viewing results must stay in the app", page.hasWindowFocus()) }
    }

    @Test fun displayDimSetsRealBrightnessAndRestoresOnTouchAndForegroundLoss() {
        val page = open()
        awaitReady(page)
        instrumentation.runOnMainSync {
            page.findViewById<View>(R.id.campTestDim).performClick()
            assertSystemBrightness(26, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, 20f, 20f, 0)
                try { assertTrue(page.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            assertSystemBrightness(150, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            assertEquals(View.GONE, page.findViewById<View>(R.id.campTestDimOverlay).visibility)
        }
        awaitReady(page)
        instrumentation.runOnMainSync {
            page.findViewById<View>(R.id.campTestDim).performClick()
            assertSystemBrightness(26, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        }
        awaitReady(page)
        val other = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, DebugHubActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            assertSystemBrightness(150, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        } finally {
            instrumentation.runOnMainSync { other.finish() }
        }
    }

    @Test fun displayDimHasAutomaticTimeout() {
        val page = open()
        awaitReady(page)
        instrumentation.runOnMainSync {
            page.findViewById<View>(R.id.campTestDim).performClick()
            assertEquals(View.VISIBLE, page.findViewById<View>(R.id.campTestDimOverlay).visibility)
        }
        awaitOverlayGone(page)
        awaitReady(page)
        assertSystemBrightness(150, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
        val text = awaitReport(page, "10-second timeout")
        assertTrue(text, text.contains("10-second timeout"))
    }

    @Test fun screenOffSendsCarShortcutCommandAndReportsEachRoute() {
        val page = open()
        awaitReady(page)
        instrumentation.runOnMainSync { page.carScreenOff() }
        awaitReady(page)
        val text = awaitReport(page, "ADB am startservice")
        // No BYD Car Settings on the emulator, so the direct route fails and ADB is tried.
        assertTrue(text, text.contains("Car screen off") && text.contains("BYD Car Settings not installed"))
        assertTrue(text, text.contains("ADB am startservice"))
        instrumentation.runOnMainSync {
            assertEquals(View.GONE, page.findViewById<View>(R.id.campTestDimOverlay).visibility)
        }
    }

    private fun awaitReport(page: CampTestsActivity, expected: String): String {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var text = ""
        while (!text.contains(expected) && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { text = page.findViewById<TextView>(R.id.campTestReport).text.toString() }
            Thread.sleep(50)
        }
        return text
    }

    private fun awaitOverlayGone(page: CampTestsActivity) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var restored = false
        while (!restored && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                restored = page.findViewById<View>(R.id.campTestDimOverlay).visibility == View.GONE
            }
            if (!restored) Thread.sleep(100)
        }
        assertTrue("Display test must restore automatically", restored)
    }

    @Test fun windowCommandsReportUnavailableSdkAndStopIsNeverLocked() {
        val page = open()
        instrumentation.runOnMainSync { page.findViewById<View>(R.id.campTestWindowsStop).performClick() }
        val deadline = SystemClock.uptimeMillis() + 10_000
        var text = ""
        while (!text.contains("STOP windows command") && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { text = page.findViewById<TextView>(R.id.campTestReport).text.toString() }
            Thread.sleep(50)
        }
        assertTrue(text, text.contains("STOP windows command") && text.contains("unavailable"))
        instrumentation.runOnMainSync {
            page.findViewById<View>(R.id.campTestBaseline).performClick()
            // A running operation disables the other tests but must leave STOP usable.
            assertFalse(page.findViewById<Button>(R.id.campTestWindowsClose).isEnabled)
            assertTrue(page.findViewById<Button>(R.id.campTestWindowsStop).isEnabled)
        }
        awaitReady(page)
    }

    @Test fun drlRestoreReportsUnavailableSdkWithoutCrashing() {
        val page = open()
        instrumentation.runOnMainSync { page.findViewById<View>(R.id.campTestDrlOn).performClick() }
        awaitReady(page)
        instrumentation.runOnMainSync {
            val text = page.findViewById<TextView>(R.id.campTestReport).text.toString()
            assertTrue(text, text.contains("setDayTimeLightState(1)") && text.contains("NOT SENT"))
        }
    }

    @Test fun lightingInspectionReportsApisAndRawReadFailuresWithoutSendingControls() {
        val page = open()
        instrumentation.runOnMainSync { page.findViewById<View>(R.id.campTestLightingInspect).performClick() }
        awaitReady(page)
        val text = awaitReport(page, "Lighting and daylight inspection")
        assertTrue(text, text.contains("no vehicle methods or settings are invoked"))
        assertTrue(text, text.contains("No daylight-sensor or theme writes are performed"))
        assertTrue(text, text.contains("Headlights.readPaths"))
        assertTrue(text, text.contains("Sensor (android.hardware.bydauto.sensor.BYDAutoSensorDevice)"))
        assertFalse(text, text.contains("Headlight OFF test ("))
        onView(withId(android.R.id.message))
            .check(matches(isDisplayed()))
            .check(matches(withText(containsString("Headlights.readPaths"))))
            .check(matches(withText(containsString("no vehicle methods or settings are invoked"))))
        onView(withText(android.R.string.ok)).perform(click())
    }

    @Test fun headlightOffTestsRequireConfirmationAndNeverClaimSuccessWithoutSdk() {
        assumeTrue("Active command UI integration test is emulator-only",
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val page = open()
        for ((id, path) in listOf(R.id.campTestHeadlightsNamed to "NAMED", R.id.campTestHeadlightsFeature to "FEATURE")) {
            var before = ""
            instrumentation.runOnMainSync {
                before = page.findViewById<TextView>(R.id.campTestReport).text.toString()
                page.findViewById<View>(id).performClick()
            }
            onView(withText(R.string.camp_test_headlights_confirm)).check(matches(isDisplayed()))
            onView(withText(android.R.string.cancel)).perform(click())
            instrumentation.runOnMainSync {
                assertEquals("Cancel must not run a headlight command", before,
                    page.findViewById<TextView>(R.id.campTestReport).text.toString())
                page.findViewById<View>(id).performClick()
            }
            onView(withText(R.string.camp_test_headlights_send)).perform(click())
            awaitReady(page)
            val text = awaitReport(page, "Headlight OFF test ($path) read-back")
            assertTrue(text, text.contains("Headlight OFF test ($path)"))
            assertTrue(text, text.contains("Command accepted: false"))
            assertTrue(text, text.contains("NOT CONFIRMED"))
            assertTrue(text, text.contains("MANUAL RESTORATION REQUIRED"))
            onView(withId(android.R.id.message))
                .check(matches(isDisplayed()))
                .check(matches(withText(containsString("Command accepted: false"))))
                .check(matches(withText(containsString("NOT CONFIRMED"))))
                .check(matches(withText(containsString("MANUAL RESTORATION REQUIRED"))))
                .check { view, error ->
                    if (error != null) throw error
                    if (path == "FEATURE") assertFalse("Automatic results must show this test, not the earlier one",
                        (view as TextView).text.contains("Headlight OFF test (NAMED)"))
                }
            onView(withText(android.R.string.ok)).perform(click())
        }
    }

    private fun open(): CampTestsActivity {
        val page = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, CampTestsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as CampTestsActivity
        activity = page
        awaitReady(page)
        return page
    }

    private fun awaitReady(page: CampTestsActivity) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var ready = false
        while (!ready && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { ready = page.findViewById<Button>(R.id.campTestBaseline).isEnabled }
            if (!ready) Thread.sleep(50)
        }
        assertTrue("Interactive test operation should finish", ready)
        instrumentation.waitForIdleSync()
    }
}
