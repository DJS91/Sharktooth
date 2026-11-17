package com.sphy.airconcontroller

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
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

    @Before fun preserveSession() {
        previousSession = if (sessionFile.exists()) sessionFile.readBytes() else null
        if (sessionFile.exists()) check(sessionFile.delete())
    }

    @After fun restoreSession() {
        activity?.let { page ->
            awaitReady(page)
            instrumentation.runOnMainSync { page.finish() }
            instrumentation.waitForIdleSync()
        }
        val previous = previousSession
        if (previous != null) sessionFile.writeBytes(previous)
        else if (sessionFile.exists()) check(sessionFile.delete())
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
            page.finish()
        }
        instrumentation.waitForIdleSync()
        page = open()
        instrumentation.runOnMainSync {
            val text = page.findViewById<TextView>(R.id.campTestReport).text.toString()
            assertTrue(text.contains("Manual test observation"))
            page.findViewById<View>(R.id.campTestCapture).performClick()
        }
        awaitReady(page)
        instrumentation.runOnMainSync {
            assertTrue(page.findViewById<TextView>(R.id.campTestReport).text.contains("Differences from baseline:"))
        }
    }

    @Test fun temporaryDimRestoresOnTouchAndForegroundLoss() {
        val page = open()
        var previous = 0f
        instrumentation.runOnMainSync {
            previous = page.window.attributes.screenBrightness
            page.findViewById<View>(R.id.campTestDim).performClick()
            assertEquals(0.1f, page.window.attributes.screenBrightness, 0f)
            val now = SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, 20f, 20f, 0)
                try { assertTrue(page.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            assertEquals(previous, page.window.attributes.screenBrightness, 0f)
            assertEquals(View.GONE, page.findViewById<View>(R.id.campTestDimOverlay).visibility)
        }
        awaitReady(page)
        instrumentation.runOnMainSync { page.findViewById<View>(R.id.campTestDim).performClick() }
        awaitReady(page)
        val other = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, DebugHubActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            instrumentation.runOnMainSync {
                assertEquals(previous, page.window.attributes.screenBrightness, 0f)
            }
        } finally {
            instrumentation.runOnMainSync { other.finish() }
        }
    }

    @Test fun temporaryDimHasAutomaticTimeout() {
        val page = open()
        var previous = 0f
        instrumentation.runOnMainSync {
            previous = page.window.attributes.screenBrightness
            page.findViewById<View>(R.id.campTestDim).performClick()
        }
        val deadline = SystemClock.uptimeMillis() + 15_000
        var restored = false
        while (!restored && SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                restored = page.findViewById<View>(R.id.campTestDimOverlay).visibility == View.GONE
            }
            if (!restored) Thread.sleep(100)
        }
        assertTrue("Temporary dim must restore automatically", restored)
        awaitReady(page)
        instrumentation.runOnMainSync {
            assertEquals(previous, page.window.attributes.screenBrightness, 0f)
            assertTrue(page.findViewById<TextView>(R.id.campTestReport).text.contains("10-second timeout"))
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
