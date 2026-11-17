package com.sphy.airconcontroller

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sphy.airconcontroller.dikey.DiKeySession.ConnectionState
import com.sphy.airconcontroller.fridge.EvakoolProtocol
import com.sphy.airconcontroller.fridge.isFridgeTilePreview
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HomeNavigationTest {
    @Test fun sidebarHighlightSlidesBetweenTilesAndKeepsItsPositionAfterNavigation() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val main = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        val monitor = instrumentation.addMonitor(AdasActivity::class.java.name, null, false)
        val samples = mutableListOf<Float>()
        val highlight = main.findViewById<View>(R.id.homeNavigationHighlight)
        val listener = ViewTreeObserver.OnPreDrawListener {
            samples.add(highlight.y)
            true
        }
        var adas: Activity? = null
        var startY = 0f
        var endY = 0f
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val sidebar = main.findViewById<FrameLayout>(R.id.homeSidebar)
                val target = main.findViewById<View>(R.id.homeAdasButton)
                val bounds = Rect()
                target.getDrawingRect(bounds)
                sidebar.offsetDescendantRectToMyCoords(target, bounds)
                startY = highlight.y
                endY = bounds.top.toFloat()
                assertTrue(endY > startY)
                highlight.viewTreeObserver.addOnPreDrawListener(listener)
                target.performClick()
                assertEquals(startY, highlight.y, 0f)
                assertTrue(highlight.animate().interpolator is AccelerateDecelerateInterpolator)
            }
            adas = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
            assertNotNull("ADAS should open when the slide completes", adas)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                highlight.viewTreeObserver.removeOnPreDrawListener(listener)
                val movingSamples = samples.filter { it > startY && it < endY }
                assertTrue("The highlight should travel through intermediate positions", movingSamples.size >= 3)
                assertTrue("The highlight should slide without jumping backwards", movingSamples.zipWithNext().all { (a, b) -> b >= a })
                val activity = adas!!
                val sidebar = activity.findViewById<FrameLayout>(R.id.homeSidebar)
                val selected = activity.findViewById<View>(R.id.homeAdasButton)
                val bounds = Rect()
                selected.getDrawingRect(bounds)
                sidebar.offsetDescendantRectToMyCoords(selected, bounds)
                val settled = activity.findViewById<View>(R.id.homeNavigationHighlight)
                assertEquals(View.VISIBLE, settled.visibility)
                assertEquals(bounds.top.toFloat(), settled.y, 0f)
                assertEquals(selected.width, settled.width)
                assertEquals(selected.height, settled.height)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync {
                if (highlight.viewTreeObserver.isAlive) highlight.viewTreeObserver.removeOnPreDrawListener(listener)
                adas?.finish()
                main.finish()
            }
        }
    }

    @Test fun tappingAnotherTileDuringTheSlideRetargetsWithoutOpeningTheFirstPage() {
        assumeTrue(ValueAnimator.areAnimatorsEnabled())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val main = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        ) as MainActivity
        val adasMonitor = instrumentation.addMonitor(AdasActivity::class.java.name, null, false)
        val modesMonitor = instrumentation.addMonitor(CampModeActivity::class.java.name, null, false)
        val retargeted = CountDownLatch(1)
        var modes: Activity? = null
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val highlight = main.findViewById<View>(R.id.homeNavigationHighlight)
                val startY = highlight.y
                main.findViewById<View>(R.id.homeAdasButton).performClick()
                highlight.postOnAnimationDelayed({
                    val before = highlight.y
                    assertTrue(before > startY)
                    main.findViewById<View>(R.id.homeModesButton).performClick()
                    assertEquals("Retargeting must keep the current position", before, highlight.y, 0f)
                    retargeted.countDown()
                }, 80)
            }
            assertTrue(retargeted.await(5, TimeUnit.SECONDS))
            modes = instrumentation.waitForMonitorWithTimeout(modesMonitor, 10_000)
            assertNotNull(modes)
            assertEquals("The cancelled destination must not open", 0, adasMonitor.hits)
        } finally {
            instrumentation.removeMonitor(adasMonitor)
            instrumentation.removeMonitor(modesMonitor)
            instrumentation.runOnMainSync {
                modes?.finish()
                main.finish()
            }
        }
    }

    @Test fun sidebarTransitionsOnlyAnimateThePageIncludingReturnToVehicle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val resumed = mutableListOf<Triple<HomeActivity, Float, Float>>()
        var latch = CountDownLatch(1)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is HomeActivity) return
                val page = activity.findViewById<FrameLayout>(R.id.homePageContainer).getChildAt(0)
                val sidebar = activity.findViewById<View>(R.id.homeControlsBarButton)
                resumed.add(Triple(activity, page.alpha, sidebar.alpha))
                latch.countDown()
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            val main = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            latch = CountDownLatch(1)
            instrumentation.runOnMainSync {
                if (main.findViewById<View>(R.id.homeControlsBarButton).visibility == View.VISIBLE) {
                    main.findViewById<View>(R.id.homeControlsBarButton).performClick()
                } else {
                    (main as MainActivity).navigateTo(ColorConfigActivity::class.java)
                }
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertEquals(ColorConfigActivity::class.java, resumed.last().first.javaClass)
            assertEquals(1f, resumed.last().third, 0f)
            if (ValueAnimator.areAnimatorsEnabled()) assertEquals(0f, resumed.last().second, 0f)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val activity = resumed.last().first
                assertTrue(activity.findViewById<View>(R.id.homeControlsBarButton).isSelected)
                assertEquals(R.id.controlsBarColours, activity.findViewById<MaterialButtonToggleGroup>(R.id.controlsBarTabs).checkedButtonId)
                if (!OpenDiKeyApp.from(activity).dikey.isConnected) {
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.colorDebugDialLabel).visibility)
                }
            }

            latch = CountDownLatch(1)
            instrumentation.runOnMainSync {
                resumed.last().first.findViewById<View>(R.id.controlsBarButtons).performClick()
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertEquals(ButtonMappingActivity::class.java, resumed.last().first.javaClass)
            assertEquals(1f, resumed.last().third, 0f)
            if (ValueAnimator.areAnimatorsEnabled()) assertEquals(0f, resumed.last().second, 0f)
            instrumentation.runOnMainSync {
                val activity = resumed.last().first
                assertTrue(activity.findViewById<View>(R.id.homeControlsBarButton).isSelected)
                assertEquals(R.id.controlsBarButtons, activity.findViewById<MaterialButtonToggleGroup>(R.id.controlsBarTabs).checkedButtonId)
            }

            latch = CountDownLatch(1)
            instrumentation.runOnMainSync {
                resumed.last().first.findViewById<View>(R.id.controlsBarColours).performClick()
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertEquals(ColorConfigActivity::class.java, resumed.last().first.javaClass)
            instrumentation.runOnMainSync {
                val activity = resumed.last().first
                assertTrue(activity.findViewById<View>(R.id.homeControlsBarButton).isSelected)
                assertEquals(R.id.controlsBarColours, activity.findViewById<MaterialButtonToggleGroup>(R.id.controlsBarTabs).checkedButtonId)
            }

            latch = CountDownLatch(1)
            instrumentation.runOnMainSync {
                resumed.last().first.findViewById<View>(R.id.homeVehicleInfoButton).performClick()
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            assertEquals(MainActivity::class.java, resumed.last().first.javaClass)
            assertEquals(1f, resumed.last().third, 0f)
            if (ValueAnimator.areAnimatorsEnabled()) assertEquals(0f, resumed.last().second, 0f)
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
            instrumentation.runOnMainSync {
                val page = resumed.last().first.findViewById<FrameLayout>(R.id.homePageContainer).getChildAt(0)
                assertEquals(1f, page.alpha, 0f)
            }
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    @Test fun controlsBarVisibilityFollowsConnectionState() {
        for (state in ConnectionState.entries) {
            assertTrue(controlsBarAvailable(true, state))
            assertEquals(state == ConnectionState.CONNECTED, controlsBarAvailable(false, state))
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val main = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as MainActivity
        try {
            instrumentation.waitForIdleSync()
            val session = OpenDiKeyApp.from(main).dikey
            val actualState = runBlocking { session.connectionState.first() }
            instrumentation.runOnMainSync {
                val tile = main.findViewById<View>(R.id.homeControlsBarButton)
                try {
                    main.renderConnectionState(ConnectionState.DISCONNECTED)
                    assertEquals(View.VISIBLE, tile.visibility)
                    main.renderConnectionState(ConnectionState.CONNECTING)
                    assertEquals(View.VISIBLE, tile.visibility)
                    main.renderConnectionState(ConnectionState.CONNECTED)
                    assertEquals(View.VISIBLE, tile.visibility)
                    main.renderConnectionState(ConnectionState.DISCONNECTED)
                    assertEquals(View.VISIBLE, tile.visibility)
                } finally {
                    main.renderConnectionState(actualState)
                }
            }
        } finally {
            instrumentation.runOnMainSync { main.finish() }
        }
    }

    @Test fun debugFridgePreviewDoesNotReplaceRealOrDisconnectedReadings() {
        val real = EvakoolProtocol.Status(true, true, 6, -12, 4, -18, "12.5")
        assertTrue(isFridgeTilePreview(true, false, null))
        assertFalse(isFridgeTilePreview(false, false, null))
        assertFalse(isFridgeTilePreview(true, true, null))
        assertFalse(isFridgeTilePreview(true, true, real))
        assertFalse(isFridgeTilePreview(true, false, real))

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val main = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            instrumentation.waitForIdleSync()
            val fridge = OpenDiKeyApp.from(main).fridge
            assumeFalse("A real fridge has been seen", fridge.seenConnected)
            instrumentation.runOnMainSync {
                assertNull(fridge.status)
                assertEquals(View.VISIBLE, main.findViewById<View>(R.id.homeFridgeButton).visibility)
                val temperatures = main.findViewById<TextView>(R.id.homeFridgeTemperatures).text.toString()
                assertTrue(temperatures.contains("4°C"))
                assertTrue(temperatures.contains("-18°C"))
                assertEquals("13.8 V\nEco", main.findViewById<TextView>(R.id.homeFridgeStatus).text.toString())
                assertTrue(main.findViewById<View>(R.id.homeFridgeButton).contentDescription.contains("Example readings"))
            }
        } finally {
            instrumentation.runOnMainSync { main.finish() }
        }
    }

    @Test fun modesOpensCampWithoutActivatingVehicleControls() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(CampModeActivity::class.java.name, null, false)
        val main = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        var camp: Activity? = null
        try {
            instrumentation.runOnMainSync { main.findViewById<View>(R.id.homeModesButton).performClick() }
            camp = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
            assertNotNull("Modes should open Camp Mode", camp)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val activity = camp!!
                assertTrue(activity.findViewById<View>(R.id.homeModesButton).isSelected)
                assertEquals(R.id.modesCamp, activity.findViewById<MaterialButtonToggleGroup>(R.id.modesTabs).checkedButtonId)
                assertTrue(activity.findViewById<View>(R.id.modesPet).isEnabled)
                val enable = activity.findViewById<android.widget.Button>(R.id.campModeEnable)
                assertTrue(enable.isEnabled)
                assertEquals("Enable Camping Mode", enable.text.toString())
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.campModeStatus).visibility)
                assertEquals("", activity.findViewById<android.widget.TextView>(R.id.campModeReport).text.toString())
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync {
                camp?.finish()
                main.finish()
            }
        }
    }
}
