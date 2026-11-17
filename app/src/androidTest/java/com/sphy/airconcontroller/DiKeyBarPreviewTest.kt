package com.sphy.airconcontroller

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButtonToggleGroup
import com.sphy.airconcontroller.dikey.DialDisplay
import com.sphy.airconcontroller.dikey.DialDisplayType
import com.sphy.airconcontroller.dikey.DialDisplays
import com.sphy.airconcontroller.dikey.DiKeyProtocol
import com.sphy.airconcontroller.dikey.LedMode
import com.sphy.airconcontroller.lighting.LightingPeriod
import com.sphy.airconcontroller.ui.ColorSettingCard
import com.sphy.airconcontroller.ui.DiKeyBarPreview
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiKeyBarPreviewTest {
    @Test fun stripsChangeIndependentlyWithoutTintingTheButtons() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val original = BitmapFactory.decodeResource(context.resources, R.drawable.dikey_bar_preview)
            val preview = DiKeyBarPreview(context)
            preview.layout(0, 0, original.width, original.height)
            val initial = render(preview)

            preview.setBarColor(1, 255, 0, 0)
            val red = render(preview)
            for (x in listOf(60, 200, 345)) {
                assertTrue(Color.red(red.getPixel(x, 50)) > Color.green(red.getPixel(x, 50)))
            }
            assertEquals(initial.getPixel(1000, 50), red.getPixel(1000, 50))
            assertEquals(initial.getPixel(1900, 50), red.getPixel(1900, 50))

            preview.setBarColor(2, 0, 0, 255)
            preview.setBarColor(3, 255, 255, 255)
            val result = render(preview)
            assertEquals(red.getPixel(100, 50), result.getPixel(100, 50))
            for (x in listOf(590, 1000, 1515)) {
                assertTrue(Color.blue(result.getPixel(x, 50)) > Color.green(result.getPixel(x, 50)))
            }
            for (x in listOf(1765, 1900, 2050)) {
                val white = result.getPixel(x, 50)
                assertEquals(Color.red(white), Color.green(white))
                assertEquals(Color.green(white), Color.blue(white))
            }
            for ((x, y) in listOf(100 to 80, 1000 to 80, 1900 to 80, 450 to 50, 1650 to 50)) {
                assertEquals("Non-strip pixel $x,$y changed", initial.getPixel(x, y), result.getPixel(x, y))
            }

            preview.setBarColor(1, 0, 0, 0)
            val black = render(preview).getPixel(100, 50)
            assertEquals(Color.red(black), Color.green(black))
            assertEquals(Color.green(black), Color.blue(black))
            assertTrue(Color.red(black) < 40)
        }
    }

    @Test fun keyBacklightChangesBothLightsInAllTenKeys() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val original = BitmapFactory.decodeResource(context.resources, R.drawable.dikey_bar_preview)
            val preview = DiKeyBarPreview(context).apply { layout(0, 0, original.width, original.height) }
            val initial = render(preview)
            preview.setKeyBacklightColor(0, 255, 0)
            val green = render(preview)
            for (x in listOf(115, 280, 650, 805, 974, 1135, 1295, 1455, 1825, 1990)) {
                for (y in listOf(80, 95)) {
                    val pixel = green.getPixel(x, y)
                    assertTrue("Key light $x,$y should be green", Color.green(pixel) > Color.red(pixel))
                    assertTrue("Key light $x,$y should be green", Color.green(pixel) > Color.blue(pixel))
                }
            }
            for ((x, y) in listOf(100 to 50, 1000 to 50, 1900 to 50, 115 to 110, 450 to 80)) {
                assertEquals("Non-key pixel $x,$y changed", initial.getPixel(x, y), green.getPixel(x, y))
            }
            preview.setKeyBacklightColor(0, 0, 0)
            val black = render(preview).getPixel(115, 80)
            assertEquals(Color.red(black), Color.green(black))
            assertEquals(Color.green(black), Color.blue(black))
            assertTrue(Color.red(black) < 40)
            preview.setKeyBacklightColor(255, 255, 255)
            val white = render(preview).getPixel(115, 80)
            assertEquals(Color.red(white), Color.green(white))
            assertEquals(Color.green(white), Color.blue(white))
        }
    }

    @Test fun barGlowFollowsBrightnessAndStaysIndependent() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val original = BitmapFactory.decodeResource(context.resources, R.drawable.dikey_bar_preview)
            val preview = DiKeyBarPreview(context).apply { layout(0, 0, original.width, original.height) }
            preview.setBarColor(1, 0, 0, 0)
            val dark = render(preview)
            preview.setBarColor(1, 128, 0, 0)
            val dim = render(preview)
            preview.setBarColor(1, 255, 0, 0)
            val bright = render(preview)
            assertTrue(Color.red(dim.getPixel(200, 36)) > Color.red(dark.getPixel(200, 36)))
            assertTrue(Color.red(bright.getPixel(200, 36)) > Color.red(dim.getPixel(200, 36)))
            assertEquals(dark.getPixel(1000, 36), bright.getPixel(1000, 36))
        }
    }

    @Test fun keyGlowFollowsBrightnessForAllTenKeys() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val original = BitmapFactory.decodeResource(context.resources, R.drawable.dikey_bar_preview)
            val preview = DiKeyBarPreview(context).apply { layout(0, 0, original.width, original.height) }
            preview.setKeyBacklightColor(0, 0, 0)
            val dark = render(preview)
            preview.setKeyBacklightColor(128, 0, 0)
            val dim = render(preview)
            preview.setKeyBacklightColor(255, 0, 0)
            val bright = render(preview)
            for (x in listOf(115, 280, 650, 805, 974, 1135, 1295, 1455, 1825, 1990)) {
                assertTrue("Dim glow should appear at key $x", Color.red(dim.getPixel(x, 102)) > Color.red(dark.getPixel(x, 102)))
                assertTrue("Glow should increase at key $x", Color.red(bright.getPixel(x, 102)) > Color.red(dim.getPixel(x, 102)))
            }
            assertEquals(dark.getPixel(1000, 50), bright.getPixel(1000, 50))
        }
    }

    @Test fun barLightsHaveRoundedCornersAtBothEnds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val original = BitmapFactory.decodeResource(context.resources, R.drawable.dikey_bar_preview)
            val preview = DiKeyBarPreview(context).apply { layout(0, 0, original.width, original.height) }
            for (bar in 1..3) preview.setBarColor(bar, 255, 0, 0)
            val image = render(preview)
            for ((edge, interior) in listOf(55 to 61, 349 to 343, 586 to 592, 1519 to 1513, 1761 to 1767, 2055 to 2049)) {
                for (y in listOf(45, 55)) {
                    assertTrue(
                        "Rounded corner $edge,$y should be darker than the bar interior",
                        Color.red(image.getPixel(edge, y)) < Color.red(image.getPixel(interior, y)) / 2,
                    )
                }
            }
        }
    }

    @Test fun dialNumbersUseSevenSegmentsAndUpdateIndependently() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val original = BitmapFactory.decodeResource(context.resources, R.drawable.dikey_bar_preview)
            val preview = DiKeyBarPreview(context).apply { layout(0, 0, original.width, original.height) }
            val blank = render(preview)
            val right = DialDisplay(DialDisplayType.DRIVER_FAN.code, 3)
            preview.setDialDisplays(DialDisplays(DialDisplay(DialDisplayType.PASSENGER_TEMP.code, 24), right))
            val digits = render(preview)

            assertTrue(Color.red(digits.getPixel(440, 68)) > 200) // 2, top segment
            assertTrue(Color.red(digits.getPixel(432, 80)) < 30) // 2, unlit upper-left
            assertTrue(Color.red(digits.getPixel(432, 108)) > 200) // 2, lower-left
            assertTrue(Color.red(digits.getPixel(478, 68)) < 30) // 4, unlit top
            assertTrue(Color.red(digits.getPixel(470, 80)) > 200) // 4, upper-left
            assertTrue(Color.red(digits.getPixel(1624, 68)) < 30) // No leading zero
            assertTrue(Color.red(digits.getPixel(1648, 95)) > 200) // Centered 3, middle

            preview.setDialDisplays(DialDisplays(DialDisplay(DiKeyProtocol.DISPLAY_TYPE_BLANK, 0), right))
            val leftOff = render(preview)
            assertEquals(blank.getPixel(440, 68), leftOff.getPixel(440, 68))
            assertEquals(digits.getPixel(1648, 95), leftOff.getPixel(1648, 95))
            preview.setDialDisplays(DialDisplays())
            val disconnected = render(preview)
            assertEquals(blank.getPixel(1648, 95), disconnected.getPixel(1648, 95))

            preview.setDialDisplays(DialDisplays(right = DialDisplay(DialDisplayType.MEDIA_VOLUME.code, 0)))
            val zero = render(preview)
            assertTrue(Color.red(zero.getPixel(1648, 68)) > 200)
            assertTrue(Color.red(zero.getPixel(1648, 95)) < 30)
            assertTrue(Color.red(zero.getPixel(1624, 68)) < 30)

            preview.setDialDisplays(DialDisplays(right = DialDisplay(DialDisplayType.MEDIA_VOLUME.code, 10)))
            val ten = render(preview)
            assertTrue(Color.red(ten.getPixel(1642, 80)) > 200)
            assertTrue(Color.red(ten.getPixel(1662, 68)) > 200)
            assertTrue(Color.red(ten.getPixel(1662, 95)) < 30)
        }
    }

    @Test fun switchingProfilesReloadsAllThreePreviewColours() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val settings = OpenDiKeyApp.from(instrumentation.targetContext).dikey.settings
        val originalPeriod = settings.editingLightingPeriod
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, ColorConfigActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        try {
            instrumentation.runOnMainSync {
                val toggle = activity.findViewById<MaterialButtonToggleGroup>(R.id.colorPeriodToggle)
                val preview = activity.findViewById<DiKeyBarPreview>(R.id.colorBarPreview)
                val reference = DiKeyBarPreview(activity).apply {
                    setPadding(preview.paddingLeft, preview.paddingTop, preview.paddingRight, preview.paddingBottom)
                    layout(0, 0, preview.width, preview.height)
                }
                for (id in listOf(R.id.colorPeriodDay, R.id.colorPeriodNight, R.id.colorPeriodDay)) {
                    toggle.check(id)
                    listOf(R.id.colorLeft, R.id.colorMiddle, R.id.colorRight).forEachIndexed { index, cardId ->
                        val card = activity.findViewById<ColorSettingCard>(cardId)
                        reference.setBarColor(index + 1, card.red, card.green, card.blue)
                    }
                    val keys = activity.findViewById<ColorSettingCard>(R.id.colorKeys)
                    reference.setKeyBacklightColor(keys.red, keys.green, keys.blue)
                    val session = OpenDiKeyApp.from(activity).dikey
                    reference.setDialDisplays(
                        if (session.isConnected) {
                            session.controller.dialDisplays.value
                        } else {
                            DialDisplays(
                                DialDisplay(DialDisplayType.PASSENGER_TEMP.code, 24),
                                DialDisplay(DialDisplayType.DRIVER_FAN.code, 3),
                            )
                        },
                    )
                    assertTrue("Preview must match the selected profile", render(preview).sameAs(render(reference)))
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.findViewById<MaterialButtonToggleGroup>(R.id.colorPeriodToggle).check(
                    if (originalPeriod == LightingPeriod.NIGHT) R.id.colorPeriodNight else R.id.colorPeriodDay,
                )
                activity.finish()
            }
        }
    }

    @Test fun previewAndControlsFitRepresentativePageSizes() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().targetContext,
                R.style.Theme_OpenDiKey,
            )
            val density = context.resources.displayMetrics.density
            for ((width, height) in listOf(640 to 480, 1120 to 640)) {
                val page = LayoutInflater.from(context).inflate(R.layout.activity_color_config, null) as ViewGroup
                page.findViewById<View>(R.id.colorBackButton).visibility = View.GONE
                page.findViewById<View>(R.id.backButtonEnd).visibility = View.GONE
                listOf(R.id.colorLeft, R.id.colorMiddle, R.id.colorRight, R.id.colorKeys).forEachIndexed { index, id ->
                    val card = page.findViewById<ColorSettingCard>(id)
                    val params = card.layoutParams as LinearLayout.LayoutParams
                    params.width = if (index == 0) 0 else (104 * density).toInt()
                    params.weight = if (index == 0) 1f else 0f
                    card.layoutParams = params
                    card.setTitle(listOf("Left bar", "Middle bar", "Right bar", "Key backlight")[index])
                    card.setRgb(255, 128, 64)
                    card.setExpanded(index == 0) {}
                }
                page.measure(
                    View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                page.layout(0, 0, page.measuredWidth, page.measuredHeight)
                val preview = page.findViewById<View>(R.id.colorBarPreview)
                val card = page.findViewById<ColorSettingCard>(R.id.colorLeft)
                if (width == 1120) {
                    assertTrue("Preview should be substantially larger", preview.height >= 90 * density)
                }
                val cardBounds = Rect()
                card.getDrawingRect(cardBounds)
                page.offsetDescendantRectToMyCoords(card, cardBounds)
                assertTrue("Preview should be above the colour controls", preview.bottom <= cardBounds.top)
                val hue = card.findViewById<View>(R.id.colorSettingHue)
                val hex = card.findViewById<View>(R.id.colorSettingHex)
                assertTrue("Colour bar must remain usable", hue.width >= 90 * density)
                assertTrue("Hex should be to the right of the colour bar", hex.left >= hue.right)
                val collapsed = page.findViewById<ColorSettingCard>(R.id.colorMiddle)
                val activeBar = card.findViewById<View>(R.id.colorSettingSwatch)
                val summaryBar = collapsed.findViewById<View>(R.id.colorSettingSummarySwatch)
                val activeBarBounds = Rect()
                val summaryBarBounds = Rect()
                activeBar.getDrawingRect(activeBarBounds)
                summaryBar.getDrawingRect(summaryBarBounds)
                page.offsetDescendantRectToMyCoords(activeBar, activeBarBounds)
                page.offsetDescendantRectToMyCoords(summaryBar, summaryBarBounds)
                assertEquals("Colour bars should line up", activeBarBounds.top, summaryBarBounds.top)
                assertEquals(Color.rgb(255, 128, 64), (activeBar.background as GradientDrawable).color!!.defaultColor)
                assertEquals(Color.rgb(255, 128, 64), (summaryBar.background as GradientDrawable).color!!.defaultColor)
                val pairs = listOf(
                    R.id.colorSettingSaturationLabel to R.id.colorSettingSummarySaturation,
                    R.id.colorSettingModeLabel to R.id.colorSettingSummaryMode,
                    R.id.colorSettingBrightnessLabel to R.id.colorSettingSummaryBrightness,
                    R.id.colorSettingRedLabel to R.id.colorSettingSummaryRed,
                    R.id.colorSettingGreenLabel to R.id.colorSettingSummaryGreen,
                    R.id.colorSettingBlueLabel to R.id.colorSettingSummaryBlue,
                )
                fun assertAligned() {
                    for ((activeId, summaryId) in pairs) {
                        val activeBounds = Rect()
                        val summaryBounds = Rect()
                        val activeView = card.findViewById<View>(activeId)
                        val summaryView = collapsed.findViewById<View>(summaryId)
                        activeView.getDrawingRect(activeBounds)
                        summaryView.getDrawingRect(summaryBounds)
                        page.offsetDescendantRectToMyCoords(activeView, activeBounds)
                        page.offsetDescendantRectToMyCoords(summaryView, summaryBounds)
                        assertEquals("Active and summary values should align", activeBounds.top, summaryBounds.top)
                    }
                }
                assertAligned()
                for (id in listOf(R.id.colorSettingHue, R.id.colorSettingSaturation, R.id.colorSettingMode,
                    R.id.colorSettingBrightness, R.id.colorSettingRed, R.id.colorSettingGreen, R.id.colorSettingBlue)) {
                    val control = card.findViewById<View>(id)
                    val bounds = Rect()
                    control.getDrawingRect(bounds)
                    card.offsetDescendantRectToMyCoords(control, bounds)
                    assertTrue("Every control must fit without scrolling", bounds.top >= 0 && bounds.bottom <= card.height && bounds.height() > 0)
                }
                for (id in listOf(R.id.colorMiddle, R.id.colorRight, R.id.colorKeys)) {
                    val title = page.findViewById<ColorSettingCard>(id).findViewById<TextView>(R.id.colorSettingSummaryTitle)
                    assertEquals(1, title.layout.lineCount)
                    assertEquals("Segment names must not be truncated", 0, title.layout.getEllipsisCount(0))
                    val summary = page.findViewById<ColorSettingCard>(id)
                    for (valueId in listOf(R.id.colorSettingSummarySaturation, R.id.colorSettingSummaryMode,
                        R.id.colorSettingSummaryBrightness, R.id.colorSettingSummaryRed,
                        R.id.colorSettingSummaryGreen, R.id.colorSettingSummaryBlue)) {
                        val value = summary.findViewById<TextView>(valueId)
                        assertEquals("Values must remain on one line", 1, value.layout.lineCount)
                        assertEquals("Values must not be truncated", 0, value.layout.getEllipsisCount(0))
                    }
                }
            }
        }
    }

    @Test fun selectingSummariesExpandsOnlyOneCardWithoutChangingValues() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, ColorConfigActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        Thread.sleep(300)
        instrumentation.waitForIdleSync()
        try {
            val cards = listOf(R.id.colorLeft, R.id.colorMiddle, R.id.colorRight, R.id.colorKeys)
                .map { activity.findViewById<ColorSettingCard>(it) }
            val colors = cards.map { Triple(it.red, it.green, it.blue) }
            for (selected in listOf(0, 2, 3, 1, 0)) {
                instrumentation.runOnMainSync {
                    if (selected != 0 || cards[0].findViewById<View>(R.id.colorSettingControls).visibility != View.VISIBLE) {
                        val summary = cards[selected].findViewById<View>(R.id.colorSettingSummaryContainer)
                        val now = android.os.SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(now, now, action, summary.width / 2f, summary.height / 2f, 0)
                            summary.dispatchTouchEvent(event)
                            event.recycle()
                        }
                    }
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    cards.forEachIndexed { index, card ->
                        assertEquals(if (index == selected) View.VISIBLE else View.GONE, card.findViewById<View>(R.id.colorSettingControls).visibility)
                        assertEquals(if (index == selected) View.GONE else View.VISIBLE, card.findViewById<View>(R.id.colorSettingSummaryContainer).visibility)
                        assertEquals(colors[index], Triple(card.red, card.green, card.blue))
                    }
                }
                instrumentation.waitForIdleSync()
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun expandingSectionAnimatesItsWidth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scale = android.provider.Settings.Global.getFloat(
            instrumentation.targetContext.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
        org.junit.Assume.assumeTrue("Animations must be enabled at normal speed", scale >= 1f)
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, ColorConfigActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        try {
            val left = activity.findViewById<ColorSettingCard>(R.id.colorLeft)
            val keys = activity.findViewById<ColorSettingCard>(R.id.colorKeys)
            val expandedWidth = left.width
            val collapsedWidth = keys.width
            instrumentation.runOnMainSync {
                keys.findViewById<View>(R.id.colorSettingSummaryContents).performClick()
            }
            var observedIntermediateWidths = false
            repeat(20) {
                if (!observedIntermediateWidths) {
                    Thread.sleep(25)
                    instrumentation.runOnMainSync {
                        observedIntermediateWidths =
                            left.width in (collapsedWidth + 1) until expandedWidth &&
                            keys.width in (collapsedWidth + 1) until expandedWidth
                    }
                }
            }
            assertTrue("Both sections should animate between their start and end widths", observedIntermediateWidths)
            Thread.sleep((400 * scale).toLong())
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(collapsedWidth, left.width)
                assertEquals(expandedWidth, keys.width)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun selectingKeysKeepsSummaryValuesAligned() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, ColorConfigActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.waitForIdleSync()
        try {
            val left = activity.findViewById<ColorSettingCard>(R.id.colorLeft)
            val keys = activity.findViewById<ColorSettingCard>(R.id.colorKeys)
            instrumentation.runOnMainSync {
                keys.findViewById<View>(R.id.colorSettingSummaryContents).performClick()
            }
            Thread.sleep(450)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(View.VISIBLE, keys.findViewById<View>(R.id.colorSettingControls).visibility)
                val page = activity.findViewById<ViewGroup>(R.id.colorCards)
                for ((activeId, summaryId) in listOf(
                    R.id.colorSettingSaturationLabel to R.id.colorSettingSummarySaturation,
                    R.id.colorSettingBrightnessLabel to R.id.colorSettingSummaryBrightness,
                    R.id.colorSettingRedLabel to R.id.colorSettingSummaryRed,
                    R.id.colorSettingGreenLabel to R.id.colorSettingSummaryGreen,
                    R.id.colorSettingBlueLabel to R.id.colorSettingSummaryBlue,
                )) {
                    val active = keys.findViewById<View>(activeId)
                    val summary = left.findViewById<View>(summaryId)
                    val activeBounds = Rect()
                    val summaryBounds = Rect()
                    active.getDrawingRect(activeBounds)
                    summary.getDrawingRect(summaryBounds)
                    page.offsetDescendantRectToMyCoords(active, activeBounds)
                    page.offsetDescendantRectToMyCoords(summary, summaryBounds)
                    assertEquals("Values should remain aligned", activeBounds.top, summaryBounds.top)
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun colourBarAndSaturationUpdateLiveAndSummaryReflectsValues() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_OpenDiKey)
            val card = ColorSettingCard(context)
            card.setTitle("Left bar")
            card.setRgb(255, 255, 255)
            card.setMode(LedMode.BLINK)
            card.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
            card.layout(0, 0, 600, 900)
            var changes = 0
            var commits = 0
            card.onColorChanged = { _, _, _ -> changes++ }
            card.onColorCommitted = { _, _, _ -> commits++ }
            val hue = card.findViewById<SeekBar>(R.id.colorSettingHue)
            val now = android.os.SystemClock.uptimeMillis()
            val x = hue.paddingLeft + (hue.width - hue.paddingLeft - hue.paddingRight) / 3f
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, x, hue.height / 2f, 0)
                hue.dispatchTouchEvent(event)
                event.recycle()
            }
            assertTrue("Hue selection should produce green from white", card.green > 240 && card.red < 20 && card.blue < 20)
            assertTrue(changes > 0)
            assertEquals(1, commits)
            val saturation = card.findViewById<SeekBar>(R.id.colorSettingSaturation)
            val saturationX = saturation.paddingLeft + (saturation.width - saturation.paddingLeft - saturation.paddingRight) / 2f
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(now, now, action, saturationX, saturation.height / 2f, 0)
                saturation.dispatchTouchEvent(event)
                event.recycle()
            }
            assertTrue("Saturation should make green paler", card.red in 120..135 && card.green > 240 && card.blue in 120..135)
            assertEquals(2, commits)
            card.setExpanded(false) {}
            val summary = card.findViewById<TextView>(R.id.colorSettingSummaryHex)
            assertEquals("#%02X%02X%02X".format(card.red, card.green, card.blue), summary.text)
            assertEquals(context.getString(R.string.color_summary_mode, context.getString(R.string.led_mode_blink)), card.findViewById<TextView>(R.id.colorSettingSummaryMode).text)
            card.setRgb(128, 64, 32)
            assertEquals("#804020", summary.text)
            assertEquals("Red: 128", card.findViewById<TextView>(R.id.colorSettingSummaryRed).text)
            assertEquals("Grn: 64", card.findViewById<TextView>(R.id.colorSettingSummaryGreen).text)
            assertEquals("Blu: 32", card.findViewById<TextView>(R.id.colorSettingSummaryBlue).text)
            assertEquals("Bri: 128", card.findViewById<TextView>(R.id.colorSettingSummaryBrightness).text)
            val thumb = hue.thumb
            thumb.alpha = 0
            val trackOnly = render(hue)
            thumb.alpha = 255
            val withMarker = render(hue)
            val trackX = hue.width * 3 / 4
            val trackTop = (0 until hue.height).first { Color.alpha(trackOnly.getPixel(trackX, it)) > 0 }
            for (y in trackTop until hue.height) {
                for (x in 0 until hue.width) {
                    assertEquals("Marker must not overlap the colour bar", trackOnly.getPixel(x, y), withMarker.getPixel(x, y))
                }
            }
            thumb.setBounds(0, 0, thumb.intrinsicWidth, thumb.intrinsicHeight)
            val marker = Bitmap.createBitmap(thumb.intrinsicWidth, thumb.intrinsicHeight, Bitmap.Config.ARGB_8888)
            thumb.draw(Canvas(marker))
            val density = context.resources.displayMetrics.density
            assertEquals(Color.rgb(128, 64, 32), marker.getPixel((16 * density).toInt(), (10 * density).toInt()))
            assertEquals(Color.WHITE, marker.getPixel((16 * density).toInt(), (20 * density).toInt()))
            for (y in 0 until marker.height) {
                for (x in 0 until marker.width) assertNotEquals("Marker must have no black border", Color.BLACK, marker.getPixel(x, y))
            }
            card.setModeVisible(false)
            assertEquals(View.INVISIBLE, card.findViewById<View>(R.id.colorSettingSummaryMode).visibility)
        }
    }

    private fun render(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            view.draw(Canvas(it))
        }
}
