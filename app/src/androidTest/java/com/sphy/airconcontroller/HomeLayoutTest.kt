package com.sphy.airconcontroller

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import com.sphy.airconcontroller.ui.ColorSettingCard
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeLayoutTest {
    @Test fun wifiStatusStaysNextToButtonStatus() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext,
                R.style.Theme_OpenDiKey,
            )
            val root = LayoutInflater.from(context).inflate(R.layout.activity_main, null)
            val status = root.findViewById<TextView>(R.id.mainConnStatus)
            val wifi = root.findViewById<View>(R.id.mainWifiRow)
            val wifiText = (wifi as ViewGroup).getChildAt(1) as TextView
            val version = root.findViewById<TextView>(R.id.homeVersionText)
            version.text = "2026.11.16-1852-local"
            wifi.visibility = View.VISIBLE
            val density = context.resources.displayMetrics.density
            for (width in listOf(640, 1280)) {
                for (statusText in listOf(R.string.dikey_state_connected, R.string.dikey_state_disconnected)) {
                    status.setText(statusText)
                    root.measure(
                        View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((720 * density).toInt(), View.MeasureSpec.EXACTLY),
                    )
                    root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                    assertEquals((8 * density).toInt(), wifi.left - status.right)
                    assertTrue("Wi-Fi status must fit in the header", wifi.right <= (wifi.parent as View).width)
                    assertEquals(1, status.layout.lineCount)
                    assertEquals(1, wifiText.layout.lineCount)
                    assertTrue("Status must not overlap version", (wifi.parent as View).right <= version.left)
                    assertTrue("Version must not overlap debug", version.right <= root.findViewById<View>(R.id.mainDebugButton).left)
                }
            }
        }
    }

    @Test fun sidebarAndPageStayInsetAndFridgeAppearsAboveProjection() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (theme in listOf(R.style.Theme_OpenDiKey, R.style.Theme_OpenDiKey_Modern)) {
                val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, theme)
                val root = LayoutInflater.from(context).inflate(R.layout.activity_main, null)
                val fridge = root.findViewById<View>(R.id.homeFridgeButton)
                val projection = root.findViewById<View>(R.id.homeProjectionButton)
                val controls = root.findViewById<View>(R.id.homeControlsBarButton)
                assertEquals(View.GONE, fridge.visibility)
                assertEquals(View.GONE, controls.visibility)
                fridge.visibility = View.VISIBLE
                controls.visibility = View.VISIBLE
                projection.visibility = View.VISIBLE

                val density = context.resources.displayMetrics.density
                root.measure(
                    View.MeasureSpec.makeMeasureSpec((1280 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((720 * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)

                val page = root.findViewById<View>(R.id.homePageContainer)
                val vehicle = root.findViewById<View>(R.id.homeVehicleInfoButton)
                val rail = root.findViewById<View>(R.id.homeSidebar)
                val navigationItems = listOf(
                    vehicle, controls,
                    root.findViewById(R.id.homeAdasButton), root.findViewById(R.id.homeSentryButton),
                    root.findViewById(R.id.homeModesButton), fridge, projection,
                )
                assertTrue(page.left > 0 && page.width > rail.width)
                assertTrue(page.right < rail.left)
                assertTrue(vehicle.top < controls.top)
                assertTrue(fridge.bottom <= projection.top)
                assertTrue(projection.bottom <= rail.height)
                navigationItems.forEach { assertEquals("Navigation item must be square", it.width, it.height) }
                val surfaceColor = (rail.background as GradientDrawable).color!!.defaultColor
                assertTrue(Color.alpha(surfaceColor) in 1..254)
                assertEquals(surfaceColor, (page.background as GradientDrawable).color!!.defaultColor)
                for (panel in listOf(page, rail)) {
                    val parent = panel.parent as ViewGroup
                    assertFalse("Panel shadows must escape their parent's bounds", parent.clipChildren)
                    assertFalse("Panel shadows must extend through the outer padding", parent.clipToPadding)
                    assertTrue("Keep content inside the rounded panels", panel.clipToOutline)
                    val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                    panel.background.setBounds(0, 0, 100, 100)
                    panel.background.draw(Canvas(image))
                    val inset = (2 * density).toInt()
                    assertEquals("Panel should have no border", image.getPixel(50, 50), image.getPixel(inset, 50))
                    image.recycle()
                }
                assertFalse("Shadows must not be clipped at the top of the content row", (root as ViewGroup).clipChildren)

                val temperatures = fridge.findViewById<TextView>(R.id.homeFridgeTemperatures)
                val status = fridge.findViewById<TextView>(R.id.homeFridgeStatus)
                val disconnected = fridge.findViewById<TextView>(R.id.homeFridgeDisconnected)
                val reconnect = fridge.findViewById<Button>(R.id.homeFridgeReconnect)
                temperatures.text = "L 4°C\nR -18°C"
                status.text = "13.8 V\nMax"
                val title = projection.findViewById<TextView>(R.id.homeProjectionTitle)
                val countdown = projection.findViewById<TextView>(R.id.homeProjectionSubtitle)
                title.visibility = View.GONE
                countdown.visibility = View.VISIBLE
                countdown.text = context.getString(R.string.projection_auto_launching, 3)
                root.measure(
                    View.MeasureSpec.makeMeasureSpec((1280 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((720 * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                assertTrue(status.bottom <= fridge.height - fridge.paddingBottom)
                assertEquals(2, temperatures.layout.lineCount)
                assertEquals(0, temperatures.layout.getEllipsisCount(0))
                assertEquals(0, temperatures.layout.getEllipsisCount(1))
                assertEquals(2, status.layout.lineCount)
                assertEquals(0, status.layout.getEllipsisCount(0))
                assertEquals(0, status.layout.getEllipsisCount(1))
                temperatures.text = "L -999°F\nR 999°F"
                root.measure(
                    View.MeasureSpec.makeMeasureSpec((1280 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((720 * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                assertEquals(2, temperatures.layout.lineCount)
                assertEquals(0, temperatures.layout.getEllipsisCount(0))
                assertEquals(0, temperatures.layout.getEllipsisCount(1))
                assertTrue(countdown.bottom <= projection.height - projection.paddingBottom)
                assertTrue(countdown.layout.lineCount <= countdown.maxLines)
                assertEquals(0, countdown.layout.getEllipsisCount(countdown.layout.lineCount - 1))
                status.visibility = View.GONE
                reconnect.visibility = View.VISIBLE
                temperatures.visibility = View.GONE
                disconnected.visibility = View.VISIBLE
                root.measure(
                    View.MeasureSpec.makeMeasureSpec((1280 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((720 * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                assertTrue(reconnect.bottom <= fridge.height - fridge.paddingBottom)
                assertEquals(0, disconnected.layout.getEllipsisCount(disconnected.layout.lineCount - 1))
            }
        }
    }

    @Test fun cardsAndSectionsAreBorderlessInBothStylesAndLightModes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (nightMode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration)
                config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                val base = instrumentation.targetContext.createConfigurationContext(config)
                for (theme in listOf(R.style.Theme_OpenDiKey, R.style.Theme_OpenDiKey_Modern)) {
                    val context = ContextThemeWrapper(base, theme)
                    for (id in listOf(R.drawable.bg_feature_tile, R.drawable.bg_info_block)) {
                        val drawable = ContextCompat.getDrawable(context, id)!!
                        val image = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
                        drawable.setBounds(0, 0, 100, 100)
                        drawable.draw(Canvas(image))
                        assertEquals("A section edge should match its fill", image.getPixel(50, 50), image.getPixel(0, 50))
                        image.recycle()
                    }
                    val page = LayoutInflater.from(context).inflate(R.layout.activity_color_config, null)
                    for (id in listOf(R.id.colorLeft, R.id.colorMiddle, R.id.colorRight, R.id.colorKeys)) {
                        assertEquals(0, page.findViewById<ColorSettingCard>(id).strokeWidth)
                    }
                }
            }
        }
    }

    @Test fun dashboardColumnsUsePageHeightWithoutAHeading() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext,
                R.style.Theme_OpenDiKey,
            )
            val density = context.resources.displayMetrics.density
            for ((width, height) in listOf(640 to 480, 1120 to 640)) {
                val page = LayoutInflater.from(context).inflate(R.layout.activity_vehicle_info, null) as ViewGroup
                page.measure(
                    View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                page.layout(0, 0, page.measuredWidth, page.measuredHeight)
                assertNull(page.findViewById<View>(R.id.homePageTitle))
                for (id in listOf(R.id.vehicleColBattery, R.id.vehicleColDynamics, R.id.vehicleColTyres)) {
                    val scroll = page.findViewById<View>(id).parent as View
                    val bounds = Rect()
                    scroll.getDrawingRect(bounds)
                    page.offsetDescendantRectToMyCoords(scroll, bounds)
                    assertEquals("Cards should start at the page gutter, not below a title", (16 * density).toInt(), bounds.top)
                    assertEquals(page.height - (16 * density).toInt(), bounds.bottom)
                }
            }
        }
    }

    @Test fun pageTitlesAreCenteredAndHeaderControlsFit() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val context = ContextThemeWrapper(
            InstrumentationRegistry.getInstrumentation().targetContext,
            R.style.Theme_OpenDiKey,
        )
        val pages = listOf(
            Triple(R.layout.activity_color_config, R.id.controlsBarTabs, R.id.colorPeriodToggle),
            Triple(R.layout.activity_button_mapping, R.id.controlsBarTabs, null),
            Triple(R.layout.activity_adas, R.id.homePageTitle, R.id.adasModeToggle),
            Triple(R.layout.activity_sentry, R.id.homePageTitle, null),
            Triple(R.layout.activity_pet_mode, R.id.modesTabs, R.id.petSettingsButton),
            Triple(R.layout.activity_camp_mode, R.id.modesTabs, null),
            Triple(R.layout.activity_fridge, R.id.fridgeName, null),
        )
        val backIds = listOf(
            R.id.colorBackButton, R.id.mappingBackButton,
            R.id.adasBackButton, R.id.sentryBackButton, R.id.petBackButton,
            R.id.fridgeBack, R.id.backButtonEnd,
        )
        val density = context.resources.displayMetrics.density
        for (width in listOf(640, 1120)) {
            for ((layout, titleId, actionId) in pages) {
                val page = LayoutInflater.from(context).inflate(layout, null) as ViewGroup
                backIds.forEach { page.findViewById<View>(it)?.visibility = View.GONE }
                page.measure(
                    View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                page.layout(0, 0, page.measuredWidth, page.measuredHeight)
                val title = page.findViewById<View>(titleId)
                val titleBounds = Rect()
                title.getDrawingRect(titleBounds)
                page.offsetDescendantRectToMyCoords(title, titleBounds)
                assertTrue("Title should be centered", kotlin.math.abs(titleBounds.centerX() - page.width / 2) <= 1)
                if (title is TextView) {
                    assertEquals(Gravity.CENTER_HORIZONTAL, title.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
                }
                if (titleId == R.id.controlsBarTabs || titleId == R.id.modesTabs) {
                    val tabIds = if (titleId == R.id.controlsBarTabs) {
                        listOf(R.id.controlsBarColours, R.id.controlsBarButtons)
                    } else {
                        listOf(R.id.modesPet, R.id.modesCamp)
                    }
                    for (id in tabIds) {
                        val tab = page.findViewById<TextView>(id)
                        assertTrue("Tab must fit within submenu", tab.left >= 0 && tab.right <= title.width)
                        assertEquals(0, tab.layout.getEllipsisCount(tab.layout.lineCount - 1))
                    }
                }
                if (actionId != null) {
                    val action = page.findViewById<View>(actionId)
                    val bounds = Rect()
                    action.getDrawingRect(bounds)
                    page.offsetDescendantRectToMyCoords(action, bounds)
                    assertTrue("Header control must not overlap title", bounds.left >= titleBounds.right)
                    assertTrue("Header control must fit", bounds.right <= page.width && bounds.width() > 0)
                }
            }
        }
        }
    }
}
