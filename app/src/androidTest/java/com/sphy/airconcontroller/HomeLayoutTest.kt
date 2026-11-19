package com.sphy.airconcontroller

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
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
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.sphy.airconcontroller.ui.ColorSettingCard
import com.sphy.airconcontroller.ui.AttitudeHorizonView
import com.sphy.airconcontroller.ui.DashboardCard
import com.sphy.airconcontroller.ui.DashboardGrid
import com.sphy.airconcontroller.ui.DashboardLayout
import com.sphy.airconcontroller.update.CompanionApps
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.storage.SidebarSide
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeLayoutTest {
    @Test fun attitudeFacesAreTranslucentAndScalesRemainReadableInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration)
                config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                val base = instrumentation.targetContext.createConfigurationContext(config)
                for (theme in listOf(R.style.Theme_OpenDiKey, R.style.Theme_OpenDiKey_Modern)) {
                    val context = ContextThemeWrapper(base, theme)
                    val gauge = AttitudeHorizonView(context)
                    gauge.setAttitude(0.0, 0.0)
                    gauge.measure(
                        View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(252, View.MeasureSpec.EXACTLY),
                    )
                    gauge.layout(0, 0, 240, 252)
                    val image = Bitmap.createBitmap(240, 252, Bitmap.Config.ARGB_8888)
                    gauge.draw(Canvas(image))
                    val face = image.getPixel(60, 166)
                    assertTrue("The gauge face must let the card show through", Color.alpha(face) in 1..220)
                    val surface = ContextCompat.getColor(context, R.color.surface)
                    val faceOnCard = ColorUtils.compositeColors(face, surface)
                    val luminance = ColorUtils.calculateLuminance(faceOnCard)
                    assertTrue("The gauge must follow the surrounding light/dark palette",
                        if (night == Configuration.UI_MODE_NIGHT_NO) luminance > 0.65 else luminance < 0.15)
                    for ((x, y) in listOf(66 to 166, 60 to 172)) {
                        val background = ColorUtils.compositeColors(image.getPixel(x, y), surface)
                        assertTrue("The face must stay smooth without horizontal or vertical grid lines",
                            ColorUtils.calculateContrast(background, faceOnCard) < 1.04)
                    }
                    val tick = ContextCompat.getColor(context, R.color.attitude_ticks)
                    assertTrue("The scale needs readable text contrast in either mode",
                        ColorUtils.calculateContrast(tick, faceOnCard) >= 4.5)
                    fun scaleInk(x: Int, y: Int): Boolean {
                        val pixel = image.getPixel(x, y)
                        return kotlin.math.abs(Color.red(pixel) - Color.red(tick)) +
                            kotlin.math.abs(Color.green(pixel) - Color.green(tick)) +
                            kotlin.math.abs(Color.blue(pixel) - Color.blue(tick)) < 75
                    }
                    val labelRows = (47..65).filter { y -> (115..125).any { x -> scaleInk(x, y) } }
                    assertTrue("Upper scale numbers must render larger than the old seven-pixel glyphs",
                        labelRows.isNotEmpty() && labelRows.last() - labelRows.first() + 1 >= 9)
                    assertTrue("Major ticks must reach further into the dial and stay clearly visible",
                        scaleInk(187, 69))
                    image.recycle()
                }
            }
        }
    }

    @Test fun vehicleGroundStaysAtTheTyresAndReadoutsAreTenPercentHigher() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_OpenDiKey)
            for (mode in AttitudeHorizonView.AxisMode.entries) {
                val source = BitmapFactory.decodeResource(context.resources,
                    if (mode == AttitudeHorizonView.AxisMode.ROLL) R.drawable.shark_front else R.drawable.shark_side)
                val row = IntArray(source.width)
                val tyreBottom = (source.height - 1 downTo 0).first { y ->
                    source.getPixels(row, 0, source.width, 0, y, source.width, 1)
                    row.any { Color.alpha(it) >= 128 }
                } + 1
                val photoWidth = if (mode == AttitudeHorizonView.AxisMode.ROLL) 104f * 0.8f else 146f
                val photoHeight = photoWidth * source.height / source.width
                val groundOffset = photoHeight * (tyreBottom.toFloat() / source.height - 0.5f)
                val gauge = AttitudeHorizonView(context).apply { axisMode = mode }
                for (width in listOf(240, 480)) {
                    val scale = width / 240f
                    val height = (252 * scale).toInt()
                    fun render(angle: Double): Bitmap {
                        gauge.setAttitude(angle, angle)
                        gauge.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                        )
                        gauge.layout(0, 0, width, height)
                        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            .also { gauge.draw(Canvas(it)) }
                    }
                    for (angle in listOf(-45.0, -20.0, 0.0, 20.0, 45.0)) {
                        val image = render(angle)
                        val radians = Math.toRadians(angle)
                        val outsideTyre = (photoWidth / 2 + 6) * if (angle < 0) 1 else -1
                        val x = ((120 + outsideTyre * kotlin.math.cos(radians) -
                            groundOffset * kotlin.math.sin(radians)) * scale).toInt()
                        val y = ((126 + outsideTyre * kotlin.math.sin(radians) +
                            groundOffset * kotlin.math.cos(radians)) * scale).toInt()
                        val hasGround = (-1..1).any { dx ->
                            (-1..1).any { dy ->
                                val color = image.getPixel(x + dx, y + dy)
                                Color.green(color) - Color.red(color) > 35 &&
                                    Color.blue(color) - Color.red(color) > 55
                            }
                        }
                        assertTrue("$mode ground must meet the tyres at $angle degrees ($width px)", hasGround)
                        image.recycle()
                    }
                    val actual = render(0.0)
                    // Use the same dial background for the reference text's antialiased edges.
                    val original = requireNotNull(actual.copy(Bitmap.Config.ARGB_8888, true))
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = ContextCompat.getColor(context, R.color.attitude_accent)
                        textAlign = Paint.Align.CENTER
                        textSize = 26f
                        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                    }
                    Canvas(original).apply {
                        scale(scale, scale)
                        drawText(context.getString(R.string.vehicle_info_attitude_angle_fmt, 0.0),
                            120f, 225f - 5f / scale, textPaint)
                    }
                    fun readoutBottom(image: Bitmap): Int =
                        ((232 * scale).toInt() downTo (190 * scale).toInt()).first { y ->
                            ((75 * scale).toInt()..(165 * scale).toInt()).any { x ->
                                val color = image.getPixel(x, y)
                                Color.alpha(color) > 220 &&
                                    kotlin.math.abs(Color.red(color) - Color.red(textPaint.color)) +
                                    kotlin.math.abs(Color.green(color) - Color.green(textPaint.color)) +
                                    kotlin.math.abs(Color.blue(color) - Color.blue(textPaint.color)) < 60
                            }
                        }
                    assertEquals("Raise the $mode readout by ten percent of the gauge height at $width px",
                        height * 0.1f, (readoutBottom(original) - readoutBottom(actual)).toFloat(), 1f)
                    actual.recycle()
                    original.recycle()
                }
                source.recycle()
            }
        }
    }

    @Test fun attitudeCardKeepsBothVehicleGaugesVisibleAtDashboardWidths() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration)
                config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                val base = instrumentation.targetContext.createConfigurationContext(config)
                for (theme in listOf(R.style.Theme_OpenDiKey, R.style.Theme_OpenDiKey_Modern)) {
                    val context = ContextThemeWrapper(base, theme)
                    val card = LayoutInflater.from(context).inflate(R.layout.view_attitude_block, null)
                    val roll = card.findViewById<AttitudeHorizonView>(R.id.attitudeRollHorizon)
                    val pitch = card.findViewById<AttitudeHorizonView>(R.id.attitudePitchHorizon)
                    pitch.axisMode = AttitudeHorizonView.AxisMode.PITCH
                    for (width in listOf(160, 300, 420)) {
                        card.measure(
                            View.MeasureSpec.makeMeasureSpec(
                                (width * context.resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY,
                            ),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        )
                        card.layout(0, 0, card.measuredWidth, card.measuredHeight)
                        assertEquals(View.VISIBLE, roll.visibility)
                        assertEquals(View.VISIBLE, pitch.visibility)
                        assertTrue("The side-view Pitch dial belongs on the left", pitch.right < roll.left)
                        for (gauge in listOf(roll, pitch)) {
                            assertTrue("The full circle and title must fit without clipping",
                                gauge.width > 0 && gauge.height >= gauge.width)
                            assertTrue(gauge.bottom <= (gauge.parent as View).height)
                        }
                        val image = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
                        card.draw(Canvas(image))
                        image.recycle()
                    }
                }
            }
        }
    }

    @Test fun vehicleGaugesRotateIndependentlyAndKeepRealReadingsWhenVisuallyClamped() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_OpenDiKey)
            fun render(gauge: AttitudeHorizonView): Bitmap {
                gauge.measure(
                    View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(252, View.MeasureSpec.EXACTLY),
                )
                gauge.layout(0, 0, 240, 252)
                return Bitmap.createBitmap(240, 252, Bitmap.Config.ARGB_8888).also { gauge.draw(Canvas(it)) }
            }
            fun photo(image: Bitmap) = Bitmap.createBitmap(image, 30, 76, 180, 90)
            for (mode in AttitudeHorizonView.AxisMode.entries) {
                val gauge = AttitudeHorizonView(context).apply { axisMode = mode }
                gauge.setAttitude(0.0, 0.0)
                val level = render(gauge)
                gauge.setAttitude(
                    if (mode == AttitudeHorizonView.AxisMode.PITCH) 24.0 else 0.0,
                    if (mode == AttitudeHorizonView.AxisMode.ROLL) 24.0 else 0.0,
                )
                val tilted = render(gauge)
                val levelPhoto = photo(level)
                val tiltedPhoto = photo(tilted)
                assertFalse("The $mode vehicle must rotate with its own sensor reading", levelPhoto.sameAs(tiltedPhoto))
                gauge.setAttitude(24.0, 24.0)
                val otherAxisChanged = render(gauge)
                assertTrue("The other sensor axis must not affect $mode", tilted.sameAs(otherAxisChanged))
                gauge.setAttitude(-24.0, -24.0)
                val opposite = render(gauge)
                val oppositePhoto = photo(opposite)
                assertFalse("Negative angles must tilt the photo the other way", tiltedPhoto.sameAs(oppositePhoto))
                gauge.setAttitude(45.0, 45.0)
                val limit = render(gauge)
                gauge.setAttitude(90.0, 90.0)
                val beyondLimit = render(gauge)
                val limitPhoto = photo(limit)
                val beyondPhoto = photo(beyondLimit)
                assertTrue("Extreme angles must keep the vehicle inside the dial", limitPhoto.sameAs(beyondPhoto))
                val format = if (mode == AttitudeHorizonView.AxisMode.PITCH)
                    R.string.vehicle_info_pitch_fmt else R.string.vehicle_info_roll_fmt
                assertEquals(context.getString(format, String.format(context.resources.configuration.locales[0], "%.1f", 90.0)),
                    gauge.contentDescription)
                gauge.setAttitude(null, null)
                assertEquals(context.getString(format, "\u2014"), gauge.contentDescription)
                for (image in listOf(level, tilted, levelPhoto, tiltedPhoto, otherAxisChanged, opposite,
                    oppositePhoto, limit, beyondLimit, limitPhoto, beyondPhoto)) image.recycle()
            }
        }
    }

    @Test fun campPanelsStaySideBySideAndRemainScrollableOnSmallScreens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_OpenDiKey)
            val density = context.resources.displayMetrics.density
            for ((width, height) in listOf(640 to 360, 1120 to 640)) {
                val root = LayoutInflater.from(context).inflate(R.layout.activity_camp_mode, null)
                root.measure(
                    View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, root.measuredWidth, root.measuredHeight)
                val enable = root.findViewById<View>(R.id.campModeEnable)
                val content = enable.parent as ViewGroup
                assertEquals("Enable button should be horizontally centered", content.width / 2f,
                    (enable.left + enable.right) / 2f, 1f)
                val overview = root.findViewById<View>(R.id.campModeOverview)
                val options = root.findViewById<View>(R.id.campModeOptions)
                assertTrue(overview.right < options.left)
                assertEquals(overview.top, options.top)
                val scroll = (overview.parent.parent as View).parent as android.widget.ScrollView
                assertTrue("Long content must remain scrollable", height != 360 || scroll.canScrollVertically(1))
                val stop = root.findViewById<View>(R.id.campModeStopWindows)
                assertSame("STOP WINDOWS must stay outside the scrolling checklist", root, stop.parent)
                assertTrue("STOP WINDOWS must stay on screen", stop.top >= 0 && stop.bottom <= root.height)
                val checklist = root.findViewById<ViewGroup>(R.id.campModeChecklist)
                val cutoff = root.findViewById<View>(R.id.campModeCutoffValue)
                assertSame(options, checklist.parent.parent)
                assertTrue((cutoff.parent as View).bottom < checklist.top)
                val rows = (0 until checklist.childCount).map { checklist.getChildAt(it) as android.widget.CheckBox }
                assertEquals(10, rows.size)
                for ((above, below) in rows.zipWithNext()) {
                    assertTrue("Checklist rows must not overlap", above.bottom <= below.top)
                }
                for (step in rows) {
                    assertTrue("Each checklist label needs visible width", step.width > step.compoundPaddingLeft + step.compoundPaddingRight)
                    assertTrue("Wrapped labels must not be clipped",
                        step.layout.height <= step.height - step.compoundPaddingTop - step.compoundPaddingBottom)
                    val contentHeight = maxOf(
                        step.minimumHeight, step.layout.height + step.compoundPaddingTop + step.compoundPaddingBottom,
                        step.buttonDrawable?.minimumHeight ?: 0, step.background?.minimumHeight ?: 0,
                    )
                    assertTrue("Checkboxes must not retain height measured with zero-width text",
                        step.height <= contentHeight + density)
                    assertTrue(step.left >= 0 && step.right <= (step.parent as View).width && step.bottom <= checklist.height)
                }
            }
        }
    }

    @Test fun defaultControlLabelsAndDimOverlayFollowBothThemePalettes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (nightMode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration)
                config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                val base = instrumentation.targetContext.createConfigurationContext(config)
                for (theme in listOf(R.style.Theme_OpenDiKey, R.style.Theme_OpenDiKey_Modern)) {
                    val context = ContextThemeWrapper(base, theme)
                    val colors = context.obtainStyledAttributes(intArrayOf(R.attr.dkTextPrimary, R.attr.dkTextMuted)).let {
                        try { intArrayOf(it.getColor(0, Color.MAGENTA), it.getColor(1, Color.MAGENTA)) }
                        finally { it.recycle() }
                    }
                    val expected = colors[0]
                    val page = LayoutInflater.from(context).inflate(R.layout.activity_camp_mode, null)
                    val checklist = page.findViewById<ViewGroup>(R.id.campModeChecklist)
                    val labels = listOf(
                        com.google.android.material.switchmaterial.SwitchMaterial(context) to expected,
                        TextView(context) to colors[1], android.widget.CheckBox(context) to expected,
                        android.widget.RadioButton(context) to expected, android.widget.EditText(context) to expected,
                        LayoutInflater.from(context).inflate(R.layout.activity_camp_tests, null)
                            .findViewById<TextView>(R.id.campTestDimOverlay) to expected,
                    ) + (0 until checklist.childCount).map {
                        (checklist.getChildAt(it) as TextView) to colors[1]
                    }
                    for ((label, color) in labels) {
                        assertEquals("${label.javaClass.simpleName} must follow the selected text palette",
                            color, label.currentTextColor)
                    }
                    if (nightMode == Configuration.UI_MODE_NIGHT_NO) {
                        assertTrue("Light-mode labels must be dark",
                            androidx.core.graphics.ColorUtils.calculateLuminance(expected) < 0.2)
                    }
                }
            }
        }
    }

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
                val sentryItems = ArrayList<View>()
                root.findViewsWithText(sentryItems, context.getString(R.string.sentry_title),
                    View.FIND_VIEWS_WITH_TEXT or View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                assertTrue("Sentry belongs in Settings, not the sidebar", sentryItems.isEmpty())
                val navigationItems = listOf(
                    vehicle, controls,
                    root.findViewById(R.id.homeAdasButton),
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

    @Test fun dashboardFillsThreeColumnsLeftToRightAndUsesOneFullHeightScroll() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext,
                R.style.Theme_OpenDiKey,
            )
            val density = context.resources.displayMetrics.density
            for ((width, height) in listOf(640 to 480, 1120 to 640)) {
                val page = LayoutInflater.from(context).inflate(R.layout.activity_vehicle_info, null) as ViewGroup
                val grid = page.findViewById<DashboardGrid>(R.id.dashboardGrid)
                grid.initialize(DashboardLayout(), DashboardCard.entries.associateWith {
                    TextView(context).apply {
                        text = context.getString(it.title)
                        minimumHeight = (100 * density).toInt()
                    }
                })
                page.measure(
                    View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                page.layout(0, 0, page.measuredWidth, page.measuredHeight)
                assertNull(page.findViewById<View>(R.id.homePageTitle))
                val scroll = page.findViewById<View>(R.id.dashboardScroll)
                assertEquals((16 * density).toInt(), scroll.top)
                assertEquals(page.height - (16 * density).toInt(), scroll.bottom)
                assertSame(scroll, grid.parent)
                assertEquals(3, grid.childCount)
                val slots = (0 until grid.childCount).flatMap { rowIndex ->
                    val row = grid.getChildAt(rowIndex) as ViewGroup
                    assertEquals(3, row.childCount)
                    (0 until row.childCount).map { row.getChildAt(it) }
                }
                assertEquals(DashboardCard.defaultCards, slots.map { it.tag })
                slots.chunked(3).forEach { row ->
                    assertTrue(row[0].left < row[1].left && row[1].left < row[2].left)
                    assertEquals(row[0].width.toFloat(), row[2].width.toFloat(), 1f)
                    assertTrue(row.all { it.height >= (100 * density).toInt() })
                }
                grid.setEditing(true)
                assertEquals("Plus occupies the first slot of a new row after nine cards", 4, grid.childCount)
                val lastRow = grid.getChildAt(3) as ViewGroup
                val plusSlot = lastRow.getChildAt(0) as ViewGroup
                assertEquals(context.getString(R.string.dashboard_add_card), plusSlot.getChildAt(0).contentDescription)
            }
        }
    }

    @Test fun dashboardEditInstructionsMatchCardTitlesAndAreCenteredOnThePage() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_OpenDiKey,
            )
            val title = LayoutInflater.from(context).inflate(R.layout.view_info_block, null)
                .findViewById<TextView>(R.id.infoBlockTitle)
            val density = context.resources.displayMetrics.density
            for (width in listOf(640, 1120)) {
                val page = LayoutInflater.from(context).inflate(R.layout.activity_vehicle_info, null) as ViewGroup
                page.findViewById<View>(R.id.dashboardEditToolbar).visibility = View.VISIBLE
                page.measure(
                    View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((640 * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                page.layout(0, 0, page.measuredWidth, page.measuredHeight)
                val hint = page.findViewById<TextView>(R.id.dashboardEditHint)
                val done = page.findViewById<View>(R.id.dashboardDone)
                val bounds = Rect()
                hint.getDrawingRect(bounds)
                page.offsetDescendantRectToMyCoords(hint, bounds)
                assertEquals(page.width / 2f, bounds.exactCenterX(), 1f)
                assertEquals(Gravity.CENTER, hint.gravity)
                assertEquals(title.textSize, hint.textSize, 0f)
                assertEquals(title.typeface, hint.typeface)
                assertEquals(title.currentTextColor, hint.currentTextColor)
                assertTrue("Instructions must not overlap Done", hint.right <= done.left)
                assertTrue("Wrapped instructions should fit without clipping",
                    hint.layout.height <= hint.height - hint.compoundPaddingTop - hint.compoundPaddingBottom)
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
            Triple(R.layout.activity_pet_mode, R.id.modesTabs, R.id.petSettingsButton),
            Triple(R.layout.activity_camp_mode, R.id.modesTabs, null),
            Triple(R.layout.activity_fridge, R.id.fridgeName, null),
        )
        val backIds = listOf(
            R.id.colorBackButton, R.id.mappingBackButton,
            R.id.adasBackButton, R.id.petBackButton,
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

    @Test fun settingsUpdatesAreFirstAndSentryStaysCompactAtTheBottom() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for (theme in listOf(R.style.Theme_OpenDiKey, R.style.Theme_OpenDiKey_Modern)) {
                val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, theme)
                val density = context.resources.displayMetrics.density
                val page = LayoutInflater.from(context).inflate(R.layout.activity_settings_hub, null)
                val updates = page.findViewById<View>(R.id.settingsUpdatesSection)
                val sentry = page.findViewById<ViewGroup>(R.id.settingsSentrySection)
                val sections = updates.parent as ViewGroup
                assertSame(updates, sections.getChildAt(0))
                assertSame(sentry, sections.getChildAt(sections.childCount - 1))
                val panels = listOf(
                    page.findViewById<ViewGroup>(R.id.overdrivePanel),
                    page.findViewById<ViewGroup>(R.id.strikePanel),
                )
                for ((panel, titles) in panels.zip(listOf(
                    R.string.overdrive_title to R.string.overdrive_open_button,
                    R.string.strike_title to R.string.strike_open_button,
                ))) {
                    panel.findViewById<TextView>(R.id.companionHeadline).setText(titles.first)
                    panel.findViewById<TextView>(R.id.companionBadge).setText(R.string.overdrive_badge)
                    panel.findViewById<TextView>(R.id.companionStatus).text =
                        context.getString(R.string.companion_installed_status, "2026.11.18-1605")
                    panel.findViewById<Button>(R.id.companionOpenButton).apply {
                        visibility = View.VISIBLE
                        setText(titles.second)
                    }
                    panel.findViewById<Button>(R.id.companionInstallButton).setText(R.string.sentry_reinstall_button)
                }
                for (width in listOf(640, 1120)) {
                    page.measure(
                        View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec((480 * density).toInt(), View.MeasureSpec.EXACTLY),
                    )
                    page.layout(0, 0, page.measuredWidth, page.measuredHeight)
                    assertTrue("Sentry should be a compact section, not another full page",
                        sentry.height < 360 * density)
                    assertTrue(panels[0].right <= panels[1].left)
                    for (panel in panels) {
                        for (id in listOf(R.id.companionDetailsButton, R.id.companionOpenButton, R.id.companionInstallButton)) {
                            val button = panel.findViewById<Button>(id)
                            assertTrue("Keep actions large enough to tap", button.width >= 48 * density)
                            assertTrue("Action labels must not be clipped",
                                button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                        }
                    }
                }
            }
        }
    }

    @Test fun settingsSentryRetainsAppControlsAndFullDetails() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val page = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, SettingsHubActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        ) as SettingsHubActivity
        try {
            onView(withId(R.id.settingsCheckUpdateButton)).check(matches(isDisplayed()))
            for ((panelId, installer, descriptions) in listOf(
                Triple(R.id.overdrivePanel, CompanionApps.overdrive,
                    R.string.overdrive_install_body to R.string.overdrive_installed_body),
                Triple(R.id.strikePanel, CompanionApps.strike,
                    R.string.strike_install_body to R.string.strike_installed_body),
            )) {
                val installed = installer.isInstalled(page)
                instrumentation.runOnMainSync {
                    val panel = page.findViewById<View>(panelId)
                    assertEquals(if (installed) View.VISIBLE else View.GONE,
                        panel.findViewById<View>(R.id.companionOpenButton).visibility)
                    assertEquals(page.getString(if (installed) R.string.sentry_reinstall_button else R.string.sentry_install_button),
                        panel.findViewById<Button>(R.id.companionInstallButton).text.toString())
                }
                onView(allOf(withId(R.id.companionDetailsButton), isDescendantOfA(withId(panelId))))
                    .perform(scrollTo(), click())
                onView(withId(android.R.id.message)).inRoot(isDialog()).check { view, error ->
                    if (error != null) throw error
                    val text = (view as TextView).text.toString()
                    assertTrue(text.contains(page.getString(if (installed) descriptions.second else descriptions.first)))
                    if (panelId == R.id.overdrivePanel) {
                        assertTrue(text.contains(page.getString(R.string.overdrive_caveats_title)))
                        assertTrue(text.contains(page.getString(R.string.overdrive_caveats)))
                    }
                }
                onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
            }
        } finally {
            instrumentation.runOnMainSync { page.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun sidebarPositionIsSavedAndMovesTheRailWithoutRebuildingThePage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("aircon_settings", android.content.Context.MODE_PRIVATE)
        val previous = prefs.getString("sidebar_side", null)
        var dashboard: VehicleInfoActivity? = null
        var settingsPage: SettingsHubActivity? = null
        fun assertPosition(page: VehicleInfoActivity, side: SidebarSide) {
            instrumentation.runOnMainSync {
                val sidebar = page.findViewById<View>(R.id.homeSidebar)
                val content = page.findViewById<View>(R.id.homePageContainer)
                val gap = if (side == SidebarSide.LEFT) content.left - sidebar.right
                    else sidebar.left - content.right
                assertEquals("Keep the rail separated from the page on either side",
                    12 * page.resources.displayMetrics.density, gap.toFloat(), 1f)
            }
        }
        try {
            prefs.edit().remove("sidebar_side").commit()
            val page = instrumentation.startActivitySync(
                Intent(context, VehicleInfoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as VehicleInfoActivity
            dashboard = page
            instrumentation.waitForIdleSync()
            assertPosition(page, SidebarSide.RIGHT)
            lateinit var originalContent: View
            lateinit var originalGrid: DashboardGrid
            instrumentation.runOnMainSync {
                originalContent = (page.findViewById<ViewGroup>(R.id.homePageContainer)).getChildAt(0)
                originalGrid = page.findViewById(R.id.dashboardGrid)
            }
            var selected = SidebarSide.RIGHT
            for ((direction, side) in listOf(
                View.LAYOUT_DIRECTION_LTR to SidebarSide.LEFT,
                View.LAYOUT_DIRECTION_LTR to SidebarSide.RIGHT,
                View.LAYOUT_DIRECTION_RTL to SidebarSide.RIGHT,
                View.LAYOUT_DIRECTION_RTL to SidebarSide.LEFT,
            )) {
                instrumentation.runOnMainSync {
                    (page.findViewById<View>(R.id.homeSidebar).parent as View).layoutDirection = direction
                }
                instrumentation.waitForIdleSync()
                val settingsScreen = instrumentation.startActivitySync(
                    Intent(context, SettingsHubActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                ) as SettingsHubActivity
                settingsPage = settingsScreen
                instrumentation.runOnMainSync {
                    val checked = settingsScreen.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(
                        R.id.settingsSidebarSideToggle,
                    ).checkedButtonId
                    assertEquals(if (selected == SidebarSide.LEFT) R.id.settingsSidebarLeft else R.id.settingsSidebarRight,
                        checked)
                }
                onView(withId(if (side == SidebarSide.LEFT) R.id.settingsSidebarLeft else R.id.settingsSidebarRight))
                    .perform(scrollTo(), click())
                assertEquals(side, AppSettings(context).sidebarSide)
                onView(withId(R.id.settingsBackButton)).perform(click())
                settingsPage = null
                instrumentation.waitForIdleSync()
                assertPosition(page, side)
                instrumentation.runOnMainSync {
                    assertSame(originalContent, page.findViewById<ViewGroup>(R.id.homePageContainer).getChildAt(0))
                    assertSame(originalGrid, page.findViewById<DashboardGrid>(R.id.dashboardGrid))
                    assertEquals("Moving the rail must not mirror the dashboard cards",
                        View.LAYOUT_DIRECTION_LTR, originalGrid.layoutDirection)
                }
                selected = side
            }
            instrumentation.runOnMainSync { page.finish() }
            instrumentation.waitForIdleSync()
            val reloaded = instrumentation.startActivitySync(
                Intent(context, VehicleInfoActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as VehicleInfoActivity
            dashboard = reloaded
            instrumentation.waitForIdleSync()
            assertPosition(reloaded, SidebarSide.LEFT)
        } finally {
            instrumentation.runOnMainSync {
                settingsPage?.finish()
                dashboard?.finish()
            }
            instrumentation.waitForIdleSync()
            prefs.edit().putString("sidebar_side", previous).commit()
        }
    }
}
