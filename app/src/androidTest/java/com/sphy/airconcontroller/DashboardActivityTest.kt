package com.sphy.airconcontroller

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.ScrollView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.swipeRight
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.fridge.FridgeControlsFragment
import com.sphy.airconcontroller.fridge.formatFridgeUpdateAge
import com.sphy.airconcontroller.ui.DashboardCard
import com.sphy.airconcontroller.ui.DashboardGrid
import com.sphy.airconcontroller.ui.AttitudeHorizonView
import com.sphy.airconcontroller.ui.PercentBar
import com.sphy.airconcontroller.ui.PedalGauge
import com.sphy.airconcontroller.ui.PowerFlowView
import com.sphy.airconcontroller.ui.TripUsageTracker
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("aircon_settings", Context.MODE_PRIVATE)
    private val pageKeys = listOf("dashboard_cards") + (1..4).map { "dashboard_cards_page_$it" }
    private var previousCards = emptyMap<String, String?>()
    private var activity: VehicleInfoActivity? = null

    @Before fun preserveLayout() {
        previousCards = pageKeys.associateWith { prefs.getString(it, null) }
        prefs.edit().apply { pageKeys.forEach { remove(it) } }.commit()
    }

    @After fun restoreLayout() {
        activity?.let { page -> instrumentation.runOnMainSync { page.finish() } }
        instrumentation.waitForIdleSync()
        prefs.edit().apply { previousCards.forEach { (key, value) -> putString(key, value) } }.commit()
    }

    @Test fun mileageReadingsStayAtTheBottomBesideATallerCard() {
        AppSettings(context).dashboardCards = listOf(DashboardCard.MILEAGE, DashboardCard.ENGINE)
        val page = open(samples = true)
        instrumentation.runOnMainSync {
            val rows = page.findViewById<LinearLayout>(R.id.infoBlockRows)
            val card = rows.parent as View
            val slot = card.parent as View
            val hero = card.findViewById<TextView>(R.id.infoBlockHero)
            assertEquals("EV 7200 km\nHEV 5280 km", hero.text.toString())
            val expected = listOf(
                R.string.vehicle_info_row_odo to "12480 km",
                R.string.vehicle_info_row_drive_time to "240 h",
                R.string.vehicle_info_row_avg_speed to "52 km/h",
            )
            expected.forEach { (label, value) ->
                val matches = ArrayList<View>()
                rows.findViewsWithText(matches, page.getString(label), View.FIND_VIEWS_WITH_TEXT)
                val row = matches.single { it.id == R.id.infoRowLabel }.parent as View
                assertEquals(value, row.findViewById<TextView>(R.id.infoRowValue).text.toString())
            }
            assertTrue("Extra row height should go above the readings",
                rows.getChildAt(0).top > card.paddingTop)
            assertEquals(slot.height - card.paddingBottom,
                card.top + rows.top + rows.getChildAt(rows.childCount - 1).bottom)
        }
    }

    @Test fun batteryCardShowsHealthAboveTheGreenChargeAndBlueFuelBars() {
        AppSettings(context).dashboardCards = listOf(DashboardCard.BATTERY)
        val page = open()
        instrumentation.runOnMainSync {
            val label = page.findViewById<TextView>(R.id.infoBlockHeroLabel)
            val value = page.findViewById<TextView>(R.id.infoBlockHero)
            val rows = page.findViewById<LinearLayout>(R.id.infoBlockRows)
            assertEquals(page.getString(R.string.vehicle_info_row_soh), label.text.toString())

            page.bindDashboardBattery(BydVehicleInfoController.BatteryInfo(
                bound = true, sohPct = 97.0, socPct = 75.0, fuelPct = 50.0,
            ))
            assertEquals("97%", value.text.toString())
            page.bindDashboardBattery(BydVehicleInfoController.BatteryInfo(bound = false))
            assertEquals("—", value.text.toString())

            listOf(
                Triple(R.string.vehicle_info_row_soc, "#33FF00", rows.getChildAt(0)),
                Triple(R.string.vehicle_info_row_fuel, "#0090FF", rows.getChildAt(1)),
            ).forEach { (labelId, color, row) ->
                assertEquals(page.getString(labelId), row.findViewById<TextView>(R.id.percentRowLabel).text.toString())
                val bar = row.findViewById<PercentBar>(R.id.percentRowBar)
                bar.setPercent(100.0, animate = false)
                val image = Bitmap.createBitmap(bar.width, bar.height, Bitmap.Config.ARGB_8888)
                try {
                    bar.draw(Canvas(image))
                    assertEquals(Color.parseColor(color), image.getPixel(bar.width / 2, bar.height / 2))
                } finally {
                    image.recycle()
                }

            }
        }
    }

    @Test fun batteryTripMarkersShowDepartureLevelsAndMutedNetUsage() {
        AppSettings(context).dashboardCards = listOf(DashboardCard.BATTERY)
        val page = open(samples = true)
        instrumentation.runOnMainSync {
            val rows = page.findViewById<LinearLayout>(R.id.infoBlockRows)
            page.bindDashboardBattery(
                BydVehicleInfoController.BatteryInfo(bound = true, socPct = 74.0, fuelPct = 58.0),
                startCharge = 76.0, startFuel = 60.0,
            )
            for (row in listOf(rows.getChildAt(0), rows.getChildAt(1))) {
                val usage = row.findViewById<TextView>(R.id.percentRowTripUsage)
                assertEquals("-2%", usage.text.toString())
                assertEquals(View.VISIBLE, usage.visibility)
                assertEquals(page.getColor(R.color.text_muted), usage.currentTextColor)
                assertFalse(usage.typeface.isBold)
            }
            val bar = rows.getChildAt(0).findViewById<PercentBar>(R.id.percentRowBar)
            bar.setPercent(40.0, animate = false)
            bar.setStartPercent(null)
            val without = Bitmap.createBitmap(bar.width, bar.height, Bitmap.Config.ARGB_8888)
            val with = Bitmap.createBitmap(bar.width, bar.height, Bitmap.Config.ARGB_8888)
            try {
                bar.draw(Canvas(without))
                bar.setStartPercent(76.0)
                bar.draw(Canvas(with))
                val x = bar.paddingLeft + ((bar.width - bar.paddingLeft - bar.paddingRight) * 0.65f).toInt()
                val y = bar.height / 2
                assertNotEquals("The used portion should retain a faint departure-level fill",
                    without.getPixel(x, y), with.getPixel(x, y))
                assertNotEquals("The marker must be fainter than the current solid fill",
                    page.getColor(R.color.dashboard_battery_charge), with.getPixel(x, y))
                assertEquals("The marker must not extend past the departure level",
                    without.getPixel(bar.width * 9 / 10, y), with.getPixel(bar.width * 9 / 10, y))
            } finally {
                without.recycle()
                with.recycle()
            }
            page.bindDashboardBattery(
                BydVehicleInfoController.BatteryInfo(bound = true, socPct = 78.0, fuelPct = null),
                startCharge = 76.0, startFuel = 60.0,
            )
            assertEquals(View.GONE, rows.getChildAt(0).findViewById<View>(R.id.percentRowTripUsage).visibility)
            assertEquals(View.GONE, rows.getChildAt(1).findViewById<View>(R.id.percentRowTripUsage).visibility)
        }
    }

    @Test fun tripUsageStateSurvivesReopeningSettings() {
        val settings = AppSettings(context)
        val original = settings.tripUsageState
        try {
            val expected = TripUsageTracker.State(
                lastGear = 4, startCharge = 76.0, startFuel = null,
                startedAtMs = 123_000L, startOdometerKm = 12_468.0,
            )
            settings.tripUsageState = expected
            assertEquals(expected, AppSettings(context).tripUsageState)
        } finally {
            settings.tripUsageState = original
        }
    }

    @Test fun batteryTripDetailsSitDirectlyBelowTheTitleAndUpdateWithDistance() {
        AppSettings(context).dashboardCards = listOf(DashboardCard.BATTERY)
        val page = open(samples = true)
        instrumentation.runOnMainSync {
            val title = page.findViewById<TextView>(R.id.infoBlockTitle)
            val details = page.findViewById<TextView>(R.id.infoBlockSubtitle)
            val trip = TripUsageTracker.State(startedAtMs = 1_000L, startOdometerKm = 12_468.0)
            page.bindDashboardBattery(
                BydVehicleInfoController.BatteryInfo(bound = true, odoKm = 12_480.0),
                trip = trip, nowMs = 1_021_000L,
            )
            assertEquals("Trip Started: 17 minutes ago\nTraveled: 12 km", details.text.toString())
            assertSame(title.parent, details.parent)
            assertEquals(title.left, details.left)
            assertTrue(details.top >= title.bottom)
            page.bindDashboardBattery(
                BydVehicleInfoController.BatteryInfo(bound = true, odoKm = 12_481.5),
                trip = trip, nowMs = 1_081_000L,
            )
            assertEquals("Trip Started: 18 minutes ago\nTraveled: 13.5 km", details.text.toString())
            page.bindDashboardBattery(BydVehicleInfoController.BatteryInfo(bound = false))
            assertEquals("Trip Started: —\nTraveled: — km", details.text.toString())
        }
    }

    @Test fun debugDashboardStartsWithLabeledSamplesAndFilledBars() {
        AppSettings(context).dashboardCards = DashboardCard.entries
        val page = open(samples = true)
        instrumentation.runOnMainSync {
            assertTrue(page.findViewById<TextView>(R.id.homeTitle).text.contains(
                page.getString(R.string.dashboard_sample_data),
            ))
            val grid = page.findViewById<DashboardGrid>(R.id.dashboardGrid)
            val expected = mapOf(
                DashboardCard.BATTERY to "98%", DashboardCard.DRIVE to "48 km/h",
                DashboardCard.TYRES to "OK", DashboardCard.CONSUMPTION to "9.1 L/100km",
                DashboardCard.MILEAGE to "EV 7200 km\nHEV 5280 km", DashboardCard.TABLET to "24%",
                DashboardCard.ENGINE to "1650 rpm", DashboardCard.CHASSIS to "23°C",
                DashboardCard.POWER_FLOW to "+35 kW",
            )
            val captions = mapOf(
                DashboardCard.BATTERY to "State of health",
                DashboardCard.DRIVE to "Speed",
                DashboardCard.TYRES to "Tyre status",
                DashboardCard.CONSUMPTION to "Lifetime fuel economy",
                DashboardCard.MILEAGE to "Distance driven",
                DashboardCard.TABLET to "CPU",
                DashboardCard.ENGINE to "RPM",
                DashboardCard.CHASSIS to "Outside temperature",
                DashboardCard.POWER_FLOW to "Power",
            )
            for (i in 0 until grid.childCount) {
                val row = grid.getChildAt(i) as ViewGroup
                for (j in 0 until row.childCount) {
                    val slot = row.getChildAt(j)
                    val card = slot.tag as? DashboardCard ?: continue
                    if (card == DashboardCard.EVAKOOL_FRIDGE) continue
                    val title = slot.findViewById<TextView>(R.id.infoBlockTitle)
                    assertEquals(page.getColor(R.color.dashboard_title_shadow), title.shadowColor)
                    if (Color.alpha(title.shadowColor) > 0) assertTrue(title.shadowRadius > 0f)
                    val hero = slot.findViewById<TextView>(R.id.infoBlockHero)
                    val caption = slot.findViewById<TextView>(R.id.infoBlockHeroLabel)
                    if (card == DashboardCard.ATTITUDE) {
                        assertNull(hero)
                        assertNull(caption)
                        continue
                    }
                    expected[card]?.let { assertEquals(it, hero.text.toString()) }
                    if (card == DashboardCard.MILEAGE) {
                        val text = hero.text as android.text.Spanned
                        val spans = text.getSpans(0, text.length, android.text.style.ForegroundColorSpan::class.java)
                        assertEquals(listOf("EV", "HEV"), spans.sortedBy(text::getSpanStart).map { span ->
                            assertEquals(page.getColor(R.color.text_muted), span.foregroundColor)
                            text.subSequence(text.getSpanStart(span), text.getSpanEnd(span)).toString()
                        })
                        assertEquals(page.getColor(R.color.text_primary), hero.currentTextColor)
                    }
                    assertTrue(hero.textSize > title.textSize)
                    assertTrue(title.right <= (hero.parent as View).left)
                    assertEquals(0, (hero.parent as View).top)
                    assertEquals(captions.getValue(card), caption.text.toString())
                    assertEquals(View.VISIBLE, caption.visibility)
                    assertTrue(caption.top >= hero.bottom && caption.textSize < hero.textSize)
                    assertEquals(hero.right, caption.right)
                }
            }
            val rows = page.findViewById<LinearLayout>(R.id.infoBlockRows)
            assertEquals("-6%", rows.getChildAt(0).findViewById<TextView>(R.id.percentRowTripUsage).text.toString())
            assertEquals("-4%", rows.getChildAt(1).findViewById<TextView>(R.id.percentRowTripUsage).text.toString())
            listOf("76%" to "#33FF00", "61%" to "#0090FF").forEachIndexed { index, (text, color) ->
                val row = rows.getChildAt(index)
                assertEquals(text, row.findViewById<TextView>(R.id.percentRowValue).text.toString())
                val bar = row.findViewById<PercentBar>(R.id.percentRowBar)
                val image = Bitmap.createBitmap(bar.width, bar.height, Bitmap.Config.ARGB_8888)
                try {
                    bar.draw(Canvas(image))
                    assertEquals(Color.parseColor(color), image.getPixel(bar.width / 2, bar.height / 2))
                    val glowY = bar.paddingTop - 1
                    assertTrue("Filled portion should cast a visible glow above the track",
                        Color.alpha(image.getPixel(bar.width / 4, glowY)) > 20)
                    assertEquals("The unfilled end should not glow",
                        Color.TRANSPARENT, image.getPixel(bar.width * 9 / 10, glowY))
                } finally {
                    image.recycle()
                }
            }
            val temperatures = page.findViewById<TextView>(R.id.homeFridgeTemperatures)
            val status = page.findViewById<TextView>(R.id.homeFridgeStatus)
            val fridge = page.findViewById<View>(R.id.dashboardFridgeCard)
            val logo = fridge.findViewById<ImageView>(R.id.dashboardFridgeLogo)
            assertNull(fridge.findViewById<View>(R.id.infoBlockHero))
            assertEquals(ImageView.ScaleType.FIT_CENTER, logo.scaleType)
            assertEquals(Gravity.CENTER, temperatures.gravity)
            assertEquals(page.findViewById<TextView>(R.id.infoBlockHero).textSize, temperatures.textSize, 0f)
            assertTrue(temperatures.top >= logo.bottom)
            val density = page.resources.displayMetrics.density
            assertTrue(temperatures.top - logo.bottom >= 16 * density - 1)
            assertTrue(status.top - temperatures.bottom >= 12 * density - 1)
            val power = page.findViewById<TextView>(R.id.homeFridgePower)
            val age = page.findViewById<TextView>(R.id.homeFridgeUpdated)
            assertTrue(power.top - status.bottom >= 10 * density - 1)
            assertTrue(age.top - power.bottom >= 10 * density - 1)
            val fridgeSlot = fridge.parent as View
            assertTrue(kotlin.math.abs(fridge.top - (fridgeSlot.height - fridge.bottom)) <= 1)
            assertEquals("L 4°C  R -12°C", temperatures.text.toString())
            assertEquals(1, temperatures.lineCount)
            assertEquals("12.7 V · Eco", status.text.toString())
            assertEquals(1, status.lineCount)
            assertEquals("Power: On", page.findViewById<TextView>(R.id.homeFridgePower).text.toString())
            val updated = page.findViewById<TextView>(R.id.homeFridgeUpdated).text
            assertTrue(Regex("""Last updated: \d+ seconds? ago""").matches(updated))
        }
    }

    @Test fun powerFlowShowsDischargeRegenAndEngineActivityIndependently() {
        val settings = AppSettings(context)
        settings.dashboardCards = listOf(DashboardCard.POWER_FLOW)
        val page = open(samples = true)
        instrumentation.runOnMainSync {
            val diagram = page.findViewById<View>(R.id.dashboardPowerFlowDiagram)
            val hero = page.findViewById<TextView>(R.id.infoBlockHero)
            val blue = Color.parseColor("#1687DB")
            val green = Color.parseColor("#19C76A")
            val amber = Color.parseColor("#FFB45C")
            val regen = page.findViewById<TextView>(R.id.dashboardRegenEnergy)
            val rpm = page.findViewById<TextView>(R.id.dashboardPowerFlowRpm)
            assertEquals("1.275 kWh", regen.text.toString())
            val powerLabel = page.findViewById<TextView>(R.id.infoBlockHeroLabel)
            assertTrue(rpm.top >= powerLabel.bottom)
            assertEquals(page.getString(R.string.dashboard_flow_rpm,
                java.text.NumberFormat.getNumberInstance().format(1650)), rpm.text.toString())
            assertTrue(rpm.textSize < hero.textSize)
            assertEquals(powerLabel.right, rpm.right)
            val image = Bitmap.createBitmap(diagram.width, diagram.height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(image.width * image.height)
            fun assertActiveRoutes(vararg expected: Int) {
                image.eraseColor(Color.TRANSPARENT)
                diagram.draw(Canvas(image))
                image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
                assertEquals(expected.toSet(), setOf(blue, green, amber).filter { it in pixels }.toSet())
            }
            val scale = minOf(image.width / 500f * 0.9f, image.height / 378f)
            val left = (image.width - 500 * scale) / 2f
            val top = (image.height - 378 * scale) / 2f
            fun batteryGreenAt(x: Int, y: Int): Boolean {
                val color = image.getPixel((left + x * scale).toInt(), (top + y * scale).toInt())
                return Color.green(color) > 125 && Color.green(color) > 2 * maxOf(Color.red(color), Color.blue(color))
            }
            fun assertFlowAt(color: Int, vararg points: Pair<Int, Int>) {
                for ((x, y) in points) {
                    val px = (left + x * scale).toInt()
                    val py = (top + y * scale).toInt()
                    assertTrue("Expected a lit drivetrain component near ($x, $y)",
                        (px - 4..px + 4).any { sx ->
                            (py - 4..py + 4).any { sy ->
                                val pixel = image.getPixel(sx, sy)
                                pixel == color ||
                                    (Color.red(pixel) in Color.red(color)..220 &&
                                        Color.blue(pixel) - Color.red(pixel) >= 25 && color == blue) ||
                                    (Color.green(pixel) - Color.red(pixel) >= 25 && color == green) ||
                                    (Color.red(pixel) - Color.blue(pixel) >= 25 && color == amber)
                            }
                        })
                }
            }
            val axlePoints = arrayOf(101 to 145, 190 to 99, 125 to 138,
                310 to 304, 407 to 235, 345 to 293)
            val batteryPoints = arrayOf(177 to 144, 317 to 239, 329 to 247)
            try {
                assertEquals("+35 kW", hero.text.toString())
                assertTrue(diagram.contentDescription.contains("Battery 76%"))
                assertActiveRoutes(blue, amber)
                assertFlowAt(blue, *axlePoints, *batteryPoints)
                assertFlowAt(amber, 384 to 284, 403 to 297)

                page.bindPowerFlow(23.0, -18.0, 0.0, 48.0)
                assertEquals("-18 kW", hero.text.toString())
                assertTrue(diagram.contentDescription.contains("Battery 23%. Regenerating"))
                assertActiveRoutes(green)
                assertFlowAt(green, *axlePoints, *batteryPoints)
                assertTrue("The charged cells should have a bright green top", batteryGreenAt(297, 220))
                assertTrue("The charged cells should extend down the side of the pack", batteryGreenAt(252, 251))
                assertFalse("The uncharged pack should stay neutral", batteryGreenAt(204, 163))

                fun haloGreenness(): Int {
                    val pixel = image.getPixel((left + 187 * scale).toInt(), (top + 143 * scale).toInt())
                    return Color.green(pixel) - maxOf(Color.red(pixel), Color.blue(pixel))
                }
                val regenGlow = haloGreenness()
                page.bindPowerFlow(23.0, 0.0, 1100.0, 48.0)
                assertActiveRoutes(amber)
                val idleGlow = haloGreenness()
                assertTrue("The pack should have a subtle green halo at rest", idleGlow > 0)
                assertTrue("Charging should brighten the halo", regenGlow > idleGlow + 15)
                page.bindPowerFlow(23.0, -18.0, 1100.0, 48.0)
                assertActiveRoutes(green, amber)
                assertEquals("Net charging should glow with the engine running too", regenGlow, haloGreenness())

                page.bindPowerFlow(23.0, -18.0, 1100.0, 0.0)
                assertActiveRoutes()
                assertEquals("-18 kW", hero.text.toString())
                assertEquals(page.getString(R.string.dashboard_flow_rpm,
                    java.text.NumberFormat.getNumberInstance().format(1100)), rpm.text.toString())
                assertTrue(batteryGreenAt(297, 220))
                page.bindPowerFlow(23.0, -18.0, 1100.0, -4.0)
                assertActiveRoutes(green, amber)
                page.bindPowerFlow(23.0, 35.0, 1100.0, 0.0)
                assertActiveRoutes()
                page.bindPowerFlow(23.0, 35.0, 1100.0, null)
                assertActiveRoutes()

                page.bindPowerFlow(100.0, 0.0, 0.0, 48.0)
                assertActiveRoutes()
                assertTrue("A full charge should reach the back of the pack", batteryGreenAt(204, 163))

                page.bindPowerFlow(0.0, 0.0, 0.0, 48.0)
                assertActiveRoutes()
                assertFalse("An empty pack should not show a green cell", batteryGreenAt(297, 220))

                page.bindPowerFlow(24.0, 0.0, 1100.0, 48.0)
                assertTrue(diagram.contentDescription.contains("No battery flow"))
                assertActiveRoutes(amber)

                page.bindPowerFlow(null, Double.NaN, null, 48.0)
                assertEquals("—", hero.text.toString())
                assertTrue(diagram.contentDescription.contains("Flow unavailable"))
                assertActiveRoutes()
                fun alphaAt(x: Int, y: Int) =
                    Color.alpha(image.getPixel((left + x * scale).toInt(), (top + y * scale).toInt()))
                val roofAlpha = alphaAt(218, 30)
                assertTrue("The dark interior should have about one fifth of the roof opacity",
                    kotlin.math.abs(roofAlpha / 5f - alphaAt(305, 151)) <= 1f)
                assertEquals("The background outside the truck should remain clear", 0, alphaAt(0, 0))
                assertTrue("Shadows should fade rather than cut off abruptly",
                    alphaAt(274, 277).toFloat() / roofAlpha in 0.2f..0.63f)
                for ((x, y) in listOf(304 to 219, 145 to 122, 360 to 268, 407 to 235)) {
                    val pixel = image.getPixel((left + x * scale).toInt(), (top + y * scale).toInt())
                    val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
                    assertTrue("The photo must not show baked-in charge or flow near ($x, $y)",
                        Color.alpha(pixel) == roofAlpha && channels.max() - channels.min() <= 20)
                }
            } finally {
                image.recycle()
            }
        }
    }

    @Test fun dashboardTitleGlowIsOnlyVisibleInDarkMode() {
        instrumentation.runOnMainSync {
            for (mode in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val configured = context.createConfigurationContext(Configuration().apply { uiMode = mode })
                val themed = android.view.ContextThemeWrapper(configured, R.style.Theme_OpenDiKey)
                val inflater = android.view.LayoutInflater.from(themed)
                for (layout in listOf(R.layout.view_dashboard_card_header, R.layout.view_attitude_block)) {
                    val title = inflater.inflate(layout, null).findViewById<TextView>(R.id.infoBlockTitle)
                    if (mode == Configuration.UI_MODE_NIGHT_NO) {
                        assertEquals(0, Color.alpha(title.shadowColor))
                    } else {
                        assertEquals(Color.parseColor("#AA808080"), title.shadowColor)
                        assertTrue(title.shadowRadius > 0f)
                    }
                }
            }
        }
    }

    @Test fun powerFlowPhotoIsFainterInLightModeWithoutFadingBatteryCells() {
        instrumentation.runOnMainSync {
            fun render(mode: Int): Pair<Int, Int> {
                val themed = context.createConfigurationContext(Configuration().apply { uiMode = mode })
                val view = PowerFlowView(themed)
                view.bind(23.0, 0.0, 0.0, 0.0)
                view.layout(0, 0, 500, 378)
                val image = Bitmap.createBitmap(500, 378, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(image))
                    assertEquals("The smaller image should leave a clear top margin",
                        Color.TRANSPARENT, image.getPixel(218, 10))
                    return Color.alpha(image.getPixel((25 + 218 * 0.9f).toInt(), (18.9f + 30 * 0.9f).toInt())) to
                        image.getPixel((25 + 297 * 0.9f).toInt(), (18.9f + 220 * 0.9f).toInt())
                } finally {
                    image.recycle()
                }
            }
            val (darkRoof, darkCell) = render(Configuration.UI_MODE_NIGHT_YES)
            val (lightRoof, lightCell) = render(Configuration.UI_MODE_NIGHT_NO)
            assertEquals(255, darkRoof)
            assertTrue(lightRoof.toFloat() / darkRoof in 0.69f..0.71f)
            assertEquals(darkCell, lightCell)
        }
    }

    @Test fun powerFlowRedrawsAndMovesSoftHighlightsWhileVisible() {
        AppSettings(context).dashboardCards = listOf(DashboardCard.POWER_FLOW)
        val page = open(samples = true)
        val diagram = page.findViewById<View>(R.id.dashboardPowerFlowDiagram)
        val frames = java.util.concurrent.CountDownLatch(3)
        val listener = android.view.ViewTreeObserver.OnDrawListener { frames.countDown() }
        fun capture(): List<Int> {
            val bitmap = Bitmap.createBitmap(diagram.width, diagram.height, Bitmap.Config.ARGB_8888)
            try {
                diagram.draw(Canvas(bitmap))
                val scale = minOf(bitmap.width / 500f * 0.9f, bitmap.height / 378f)
                val left = (bitmap.width - 500 * scale) / 2f
                val top = (bitmap.height - 378 * scale) / 2f
                // Follow the exposed rear axle, away from the percentage and battery halo.
                return (173..207).map { x ->
                    val y = 122 - (x - 145) * 33f / 64
                    bitmap.getPixel((left + x * scale).toInt(), (top + y * scale).toInt())
                }
            } finally {
                bitmap.recycle()
            }
        }
        try {
            instrumentation.runOnMainSync { diagram.viewTreeObserver.addOnDrawListener(listener) }
            assertTrue("The visible flow should schedule its own redraws",
                frames.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val captures = mutableListOf<List<Int>>()
            repeat(8) {
                instrumentation.runOnMainSync { captures.add(capture()) }
                SystemClock.sleep(80)
            }
            assertTrue("The highlight should move along the axle", captures.distinct().size > 1)
            val peakRed = captures.flatten().maxOf { Color.red(it) }
            assertTrue("The highlight should stay visible without becoming pure white", peakRed in 100..205)
        } finally {
            instrumentation.runOnMainSync { diagram.viewTreeObserver.removeOnDrawListener(listener) }
        }
    }

    @Test fun fridgeUpdateAgeUsesSecondsThenWholeMinutes() {
        assertEquals("0 seconds ago", formatFridgeUpdateAge(context, 0))
        assertEquals("1 second ago", formatFridgeUpdateAge(context, 1_000))
        assertEquals("59 seconds ago", formatFridgeUpdateAge(context, 59_999))
        assertEquals("1 minute ago", formatFridgeUpdateAge(context, 60_000))
        assertEquals("2 minutes ago", formatFridgeUpdateAge(context, 120_500))
    }

    @Test fun engineShowsRpmHistoryAndDriveShowsBothPedalGauges() {
        AppSettings(context).dashboardCards = listOf(DashboardCard.ENGINE, DashboardCard.DRIVE)
        val page = open(samples = true)
        instrumentation.runOnMainSync {
            val graph = page.findViewById<View>(R.id.dashboardRpmGraph)
            assertEquals(page.getString(R.string.dashboard_rpm_last_hour), graph.contentDescription)
            val density = page.resources.displayMetrics.density
            assertTrue("The chart needs room for the plot and axis labels", graph.height >= 100 * density)
            val chart = Bitmap.createBitmap(graph.width, graph.height, Bitmap.Config.ARGB_8888)
            try {
                graph.draw(Canvas(chart))
                val amber = Color.parseColor("#FFB45C")
                assertTrue("The RPM trace should use the same amber as the engine route",
                    (chart.width / 3..chart.width * 2 / 3).any { x ->
                        (0 until chart.height).any { y -> chart.getPixel(x, y) == amber }
                    })
                val labelColor = page.getColor(R.color.text_muted)
                fun hasLabel(xs: IntRange, ys: IntRange) =
                    xs.any { x -> ys.any { y -> chart.getPixel(x, y) == labelColor } }
                assertTrue("RPM ticks should be drawn in the left gutter",
                    hasLabel(0 until (20 * density).toInt(), 0 until graph.height / 2))
                assertTrue("Time labels should be drawn below the plot",
                    hasLabel(graph.width / 2 until graph.width, graph.height - (12 * density).toInt() until graph.height))
            } finally {
                chart.recycle()
            }
            val engine = (graph.parent as View).parent as View
            assertEquals("1650 rpm", engine.findViewById<TextView>(R.id.infoBlockHero).text.toString())
            val displacement = ArrayList<View>()
            engine.findViewsWithText(displacement, page.getString(R.string.vehicle_info_row_disp), View.FIND_VIEWS_WITH_TEXT)
            assertTrue(displacement.isEmpty())

            val gauge = page.findViewById<PedalGauge>(R.id.dashboardPedalGauge)
            val driveRows = gauge.parent as ViewGroup
            val drive = driveRows.parent as View
            val viewport = page.findViewById<View>(R.id.dashboardSwipeHost)
            assertTrue("The Drive card should occupy at most half the visible Dashboard",
                (drive.parent as View).height <= viewport.height / 2)
            val parkLabel = driveRows.findViewById<TextView>(R.id.infoRowLabel)
            val parkValue = driveRows.findViewById<TextView>(R.id.infoRowValue)
            val parkRow = parkLabel.parent as View
            assertEquals("Park brake", parkLabel.text.toString())
            assertEquals("Off", parkValue.text.toString())
            assertTrue(parkRow.top >= gauge.bottom)
            assertEquals(driveRows.height, parkRow.bottom)
            assertEquals(drive.paddingLeft, driveRows.left + parkRow.left + parkLabel.left)
            assertEquals(drive.height - drive.paddingBottom, driveRows.top + parkRow.top + parkLabel.bottom)
            assertTrue(parkValue.right < parkRow.width / 2)
            val peaks = driveRows.findViewById<TextView>(R.id.dashboardPedalPeaks)
            assertEquals("Today’s peaks\nAccel 68% · Brake 42%", peaks.text.toString())
            assertEquals(parkRow.width - parkRow.paddingRight, peaks.right)
            assertTrue(peaks.left > parkValue.right)

            val gearView = drive.findViewById<TextView>(R.id.infoBlockSubtitle)
            assertTrue("The gear readout should be larger than the Park brake value",
                gearView.textSize > parkValue.textSize)
            assertTrue("The gauge should use the space alongside the gear row",
                driveRows.top + gauge.top < gearView.bottom)
            val gear = gearView.text as android.text.Spanned
            assertEquals("Gear D", gear.toString())
            val bold = gear.getSpans(0, gear.length, android.text.style.StyleSpan::class.java).single()
            val color = gear.getSpans(0, gear.length, android.text.style.ForegroundColorSpan::class.java).single()
            assertEquals(android.graphics.Typeface.BOLD, bold.style)
            assertEquals(page.getColor(R.color.text_primary), color.foregroundColor)
            for (span in listOf(bold, color)) {
                assertEquals("D", gear.subSequence(gear.getSpanStart(span), gear.getSpanEnd(span)).toString())
            }
            assertEquals("Accelerate: 28%", gauge.contentDescription)
            gauge.set(0.0, 12.0)
            assertEquals("Brake: 12%", gauge.contentDescription)
            gauge.set(0.0, 0.0)
            assertEquals("Pedals: 0%", gauge.contentDescription)
            gauge.set(null, null)
            assertEquals("Pedals: —", gauge.contentDescription)
            gauge.set(28.0, 12.0)
            assertEquals("Brake: 12%", gauge.contentDescription)
            assertNull((gauge.parent as View).findViewById<View>(R.id.percentRowBar))
            val bitmap = Bitmap.createBitmap(gauge.width, gauge.height, Bitmap.Config.ARGB_8888)
            try {
                gauge.draw(Canvas(bitmap))
                val cx = gauge.width / 2f
                val size = minOf(gauge.width.toFloat(), gauge.height / 0.86f, 256 * density)
                val cy = size / 2f
                val stroke = minOf(9 * density, size * 0.065f)
                val radius = size / 2f - stroke
                listOf(
                    radius to Color.parseColor("#0090FF"),
                    radius - 1.5f * stroke to Color.parseColor("#FFCC00"),
                ).forEach { (r, color) ->
                    val angle = Math.toRadians(150.0)
                    assertEquals(color, bitmap.getPixel(
                        (cx + r * kotlin.math.cos(angle)).toInt(),
                        (cy + r * kotlin.math.sin(angle)).toInt(),
                    ))
                }
                listOf(
                    Triple(radius, 68, Color.parseColor("#0090FF")),
                    Triple(radius - 1.5f * stroke, 42, Color.parseColor("#FFCC00")),
                ).forEach { (r, percent, color) ->
                    val angle = Math.toRadians(135.0 + 270.0 * percent / 100)
                    assertEquals("Peak dot should remain visible beyond the live fill", color, bitmap.getPixel(
                        (cx + r * kotlin.math.cos(angle)).toInt(),
                        (cy + r * kotlin.math.sin(angle)).toInt(),
                    ))
                    for (edge in listOf(r - stroke * 0.65f, r + stroke * 0.65f)) {
                        assertNotEquals("The solid peak dot should fit within the arc width", color,
                            bitmap.getPixel(
                                (cx + edge * kotlin.math.cos(angle)).toInt(),
                                (cy + edge * kotlin.math.sin(angle)).toInt(),
                            ))
                    }
                }
                gauge.set(0.0, 0.0)
                assertEquals("Pedals: 0%", gauge.contentDescription.toString())
                bitmap.eraseColor(Color.TRANSPARENT)
                gauge.draw(Canvas(bitmap))
                val textPaint = android.graphics.Paint().apply {
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    textSize = size * 0.2024f
                }
                val gapX = (cx + textPaint.measureText("0") / 2 + textPaint.textSize * 0.04f).toInt()
                assertTrue("The percent sign must leave a clear gap after the centred value",
                    ((cy - size * 0.2f).toInt()..(cy + size * 0.04f).toInt()).all { y ->
                        Color.alpha(bitmap.getPixel(gapX, y)) == 0
                    })
                assertTrue("The percent sign should be drawn to the right of that gap",
                    (gapX + 1..(cx + size * 0.3f).toInt()).any { x ->
                        ((cy - size * 0.2f).toInt()..cy.toInt()).any { y ->
                            Color.alpha(bitmap.getPixel(x, y)) > 0
                        }
                    })
                gauge.set(0.0, 12.0)
                assertEquals("Brake: 12%", gauge.contentDescription.toString())
                bitmap.eraseColor(Color.TRANSPARENT)
                gauge.draw(Canvas(bitmap))
                val captionColor = page.getColor(R.color.text_muted)
                val captionPixels = ((cx - size * 0.15f).toInt()..(cx + size * 0.15f).toInt()).flatMap { x ->
                    ((cy + size * 0.21f).toInt()..(cy + size * 0.27f).toInt()).map { y ->
                        bitmap.getPixel(x, y)
                    }
                }
                assertTrue("The smaller Pedal caption should use the muted grey text colour",
                    captionPixels.any { it == captionColor })
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun dailyPedalPeaksSurviveReopeningAndResetOnTheNextDay() {
        val testContext = instrumentation.context
        val settings = AppSettings(testContext)
        val day = java.time.LocalDate.now().toEpochDay()
        settings.recordPedalPeaks(day - 1, null, null)
        assertEquals(AppSettings.PedalPeaks(65f, 20f), settings.recordPedalPeaks(day, 65.0, 20.0))
        assertEquals(AppSettings.PedalPeaks(65f, 33f),
            AppSettings(testContext).recordPedalPeaks(day, 40.0, 33.0))
        assertEquals(AppSettings.PedalPeaks(65f, 33f), settings.recordPedalPeaks(day, Double.NaN, 110.0))
        assertEquals(AppSettings.PedalPeaks(null, 10f), settings.recordPedalPeaks(day + 1, null, 10.0))
    }

    @Test fun driveCardShrinksWhenTheDashboardViewportGetsShorter() {
        AppSettings(context).dashboardCards = listOf(DashboardCard.DRIVE, DashboardCard.ATTITUDE)
        val page = open(samples = true)
        val viewport = page.findViewById<View>(R.id.dashboardSwipeHost)
        val gauge = page.findViewById<PedalGauge>(R.id.dashboardPedalGauge)
        val cardSlot = ((gauge.parent as View).parent as View).parent as View
        var initialGaugeHeight = 0
        instrumentation.runOnMainSync {
            initialGaugeHeight = gauge.height
            viewport.layoutParams = (viewport.layoutParams as LinearLayout.LayoutParams).apply {
                height = (320 * page.resources.displayMetrics.density).toInt()
                weight = 0f
            }
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue(gauge.height in 1 until initialGaugeHeight)
            assertTrue("A taller card in the same row must not stretch Drive",
                cardSlot.height <= viewport.height / 2)
        }
    }

    @Test fun powerFlowFillsCardWidthWithoutTheHalfDashboardHeightLimit() {
        AppSettings(context).dashboardCards = listOf(DashboardCard.POWER_FLOW, DashboardCard.ENGINE)
        val page = open(samples = true)
        val viewport = page.findViewById<View>(R.id.dashboardSwipeHost)
        val diagram = page.findViewById<View>(R.id.dashboardPowerFlowDiagram)
        val card = diagram.parent as View
        val slot = card.parent as View
        val row = slot.parent as ViewGroup
        var initialHeight = 0
        instrumentation.runOnMainSync {
            val engineCard = (row.getChildAt(1) as ViewGroup).getChildAt(0)
            assertEquals(engineCard.paddingTop, card.paddingTop)
            assertEquals(engineCard.paddingBottom, card.paddingBottom)
            assertEquals(engineCard.paddingStart, card.paddingStart)
            assertEquals(engineCard.paddingEnd, card.paddingEnd)
            assertEquals(card.width - card.paddingLeft - card.paddingRight, diagram.width)
            assertTrue(kotlin.math.abs(diagram.height - diagram.width * 378f / 500f * 0.9f) <= 1f)
            assertEquals("The header and footer must not add height beyond the image",
                diagram.height + card.paddingTop + card.paddingBottom, card.height)
            val header = card.findViewById<TextView>(R.id.infoBlockTitle).parent as View
            assertEquals(diagram.top, header.top)
            assertTrue("The header must draw above the image",
                (card as ViewGroup).indexOfChild(header) > card.indexOfChild(diagram))
            val footerRows = card.findViewById<View>(R.id.infoBlockRows)
            val stackedHeight = diagram.height + header.height + footerRows.height +
                card.paddingTop + card.paddingBottom
            assertEquals("Overlaying the readings must save the full header and footer height",
                header.height + footerRows.height, stackedHeight - card.height)
            initialHeight = diagram.height
            viewport.layoutParams = (viewport.layoutParams as LinearLayout.LayoutParams).apply {
                height = (320 * page.resources.displayMetrics.density).toInt()
                weight = 0f
            }
            val neighbor = row.getChildAt(1)
            neighbor.layoutParams = (neighbor.layoutParams as LinearLayout.LayoutParams).apply {
                height = (220 * page.resources.displayMetrics.density).toInt()
            }
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("A short viewport must not shrink the truck", initialHeight, diagram.height)
            assertTrue("Power Flow must be allowed to exceed half the viewport", slot.height > viewport.height / 2)
            val regen = card.findViewById<TextView>(R.id.dashboardRegenEnergy)
            val rpm = card.findViewById<TextView>(R.id.dashboardPowerFlowRpm)
            val footer = (regen.parent as View).parent as View
            assertTrue("Engine RPM should appear under the Power label in the card header",
                rpm.top >= card.findViewById<TextView>(R.id.infoBlockHeroLabel).bottom)
            val footerTop = footer.top + (footer.parent as View).top
            assertTrue("The footer should overlap the image instead of sitting below it",
                footerTop < diagram.bottom)
            assertEquals(card.height - card.paddingBottom, footer.bottom + (footer.parent as View).top)
        }
    }

    @Test fun powerFlowUsesAvailableWidthWithoutAPreferredWidthCap() {
        instrumentation.runOnMainSync {
            val view = PowerFlowView(context)
            val density = context.resources.displayMetrics.density
            view.measure(
                View.MeasureSpec.makeMeasureSpec((700 * density).toInt(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec((800 * density).toInt(), View.MeasureSpec.AT_MOST),
            )
            assertEquals((700 * density).toInt(), view.measuredWidth)
            assertTrue(kotlin.math.abs(view.measuredHeight - view.measuredWidth * 378f / 500f * 0.9f) <= 1f)
        }
    }

    @Test fun driveGaugeStaysAtTheSameHeightWhenTheHeaderGrows() {
        AppSettings(context).dashboardCards = listOf(DashboardCard.DRIVE)
        val page = open(samples = true)
        val gauge = page.findViewById<PedalGauge>(R.id.dashboardPedalGauge)
        val speed = page.findViewById<TextView>(R.id.infoBlockHero)
        val gear = page.findViewById<TextView>(R.id.infoBlockSubtitle)
        fun gaugeTop() = gauge.top + (gauge.parent as View).top
        var originalTop = 0
        var originalHeight = 0
        var originalGearTop = 0
        instrumentation.runOnMainSync {
            originalTop = gaugeTop()
            originalHeight = gauge.height
            originalGearTop = gear.top
            speed.text = "123\nkm/h"
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue("The taller speed reading should move the gear down", gear.top > originalGearTop)
            assertEquals("The gauge should not move with the gear", originalTop, gaugeTop())
            assertEquals(originalHeight, gauge.height)
        }
    }

    @Test fun swipesThroughFiveDashboardsAndHighlightsOnlyTheCurrentPage() {
        val page = open()
        fun assertPage(index: Int) = instrumentation.runOnMainSync {
            val dots = page.findViewById<LinearLayout>(R.id.dashboardPageDots)
            assertEquals(5, dots.childCount)
            val active = dots.getChildAt(index) as FrameLayout
            for (i in 0 until dots.childCount) {
                val dot = dots.getChildAt(i) as FrameLayout
                assertEquals(i == index, dot.isSelected)
                if (i != index) assertTrue(active.getChildAt(0).width > dot.getChildAt(0).width)
            }
            val grid = page.findViewById<DashboardGrid>(R.id.dashboardGrid)
            assertEquals(index != 0, grid.childCount == 0)
            assertFalse(grid.editing)
        }
        val surface = onView(withId(R.id.dashboardSwipeHost))
        assertPage(0)
        for (index in 1..4) {
            surface.perform(swipeLeft())
            awaitDashboardSettled(page)
            assertPage(index)
        }
        surface.perform(swipeLeft())
        awaitDashboardSettled(page)
        assertPage(4)
        surface.perform(swipeRight())
        awaitDashboardSettled(page)
        assertPage(3)
    }

    @Test fun bothPagesFollowTheDragAndShortSwipesSnapBack() {
        val settings = AppSettings(context)
        settings.dashboardCards = listOf(DashboardCard.BATTERY)
        settings.saveDashboardCardsForPage(1, listOf(DashboardCard.ATTITUDE))
        val page = open()
        val host = page.findViewById<ViewGroup>(R.id.dashboardSwipeHost)
        val dots = page.findViewById<LinearLayout>(R.id.dashboardPageDots)
        val point = centerOnScreen(host)
        val slop = ViewConfiguration.get(page).scaledTouchSlop
        val sampleX = host.width / 6
        val sampleY = (8 * page.resources.displayMetrics.density).toInt()

        fun samplePage(x: Int): Int {
            val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            return try {
                host.draw(Canvas(pixel).apply { translate(-x.toFloat(), -sampleY.toFloat()) })
                pixel.getPixel(0, 0)
            } finally {
                pixel.recycle()
            }
        }

        fun drag(distance: Int, outgoingColor: Int? = null) {
            val down = SystemClock.uptimeMillis()
            injectTouch(down, MotionEvent.ACTION_DOWN, point[0], point[1])
            try {
                injectTouch(down, MotionEvent.ACTION_MOVE, point[0] - distance, point[1])
                instrumentation.runOnMainSync {
                    assertEquals(host.width - distance.toFloat(), host.getChildAt(0).translationX, 1f)
                    assertNotNull(page.findViewById<View>(R.id.attitudePitchHorizon))
                    assertTrue("The dot should not change before release", dots.getChildAt(0).isSelected)
                    if (outgoingColor != null) {
                        assertEquals("The outgoing card should move with the drag",
                            outgoingColor, samplePage(sampleX - distance))
                    }
                }
            } finally {
                injectTouch(down, MotionEvent.ACTION_UP, point[0] - distance, point[1])
            }
            awaitDashboardSettled(page)
        }

        var outgoingColor = Color.TRANSPARENT
        instrumentation.runOnMainSync { outgoingColor = samplePage(sampleX) }
        assertNotEquals(Color.TRANSPARENT, outgoingColor)
        drag(2 * slop, outgoingColor)
        instrumentation.runOnMainSync {
            assertTrue(dots.getChildAt(0).isSelected)
            assertNull(page.findViewById<View>(R.id.attitudePitchHorizon))
        }
        drag(host.width / 3)
        instrumentation.runOnMainSync {
            assertTrue(dots.getChildAt(1).isSelected)
            assertNotNull(page.findViewById<View>(R.id.attitudePitchHorizon))
        }
    }

    private fun awaitDashboardSettled(page: VehicleInfoActivity) {
        val deadline = SystemClock.uptimeMillis() + 3_000
        val host = page.findViewById<ViewGroup>(R.id.dashboardSwipeHost)
        do {
            var settled = false
            instrumentation.runOnMainSync { settled = host.getChildAt(0).translationX == 0f }
            if (settled) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(16)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Dashboard swipe did not settle")
    }

    @Test fun releasingBeyondTheLastDragPositionContinuesTheSlideWithoutJumping() {
        val settings = AppSettings(context)
        settings.dashboardCards = listOf(DashboardCard.BATTERY)
        settings.saveDashboardCardsForPage(1, listOf(DashboardCard.ATTITUDE))
        val page = open()
        val host = page.findViewById<ViewGroup>(R.id.dashboardSwipeHost)
        val dots = page.findViewById<LinearLayout>(R.id.dashboardPageDots)
        var dragTranslation = 0f
        instrumentation.runOnMainSync {
            val down = SystemClock.uptimeMillis()
            val startX = host.width - 1f
            fun dispatch(action: Int, x: Float) {
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, host.height / 2f, 0)
                try { host.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            dispatch(MotionEvent.ACTION_DOWN, startX)
            dispatch(MotionEvent.ACTION_MOVE, startX - host.width / 3f)
            dragTranslation = host.getChildAt(0).translationX
            assertTrue(dragTranslation > host.width / 2f)
            dispatch(MotionEvent.ACTION_UP, 1f)
            assertEquals("Release should start from the last drag position",
                dragTranslation, host.getChildAt(0).translationX, 1f)
            assertTrue(dots.getChildAt(0).isSelected)
        }

        val deadline = SystemClock.uptimeMillis() + 1_000
        var sawIntermediateFrame = false
        do {
            instrumentation.runOnMainSync {
                val position = host.getChildAt(0).translationX
                sawIntermediateFrame = position > 0f && position < dragTranslation - 1f
            }
            if (!sawIntermediateFrame) SystemClock.sleep(16)
        } while (!sawIntermediateFrame && SystemClock.uptimeMillis() < deadline)
        assertTrue("The incoming cards should move through intermediate positions after release", sawIntermediateFrame)

        awaitDashboardSettled(page)
        instrumentation.runOnMainSync {
            assertTrue(dots.getChildAt(1).isSelected)
            assertNotNull(page.findViewById<View>(R.id.attitudePitchHorizon))
        }
    }

    @Test fun editingAnEmptyPageSavesOnlyThatDashboardAcrossReopening() {
        val original = listOf(DashboardCard.ATTITUDE)
        AppSettings(context).dashboardCards = original
        val page = open()
        val pageTwo = withContentDescription(page.getString(R.string.dashboard_page_number, 2, 5))
        onView(pageTwo).perform(click())
        onView(withId(R.id.dashboardEditEmpty)).perform(click())
        onView(withContentDescription(R.string.dashboard_add_card)).perform(click())
        onView(withContentDescription(page.getString(
            R.string.dashboard_add_named_card, page.getString(R.string.fridge_unknown_name),
        ))).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withId(R.id.dashboardDone)).perform(click())
        instrumentation.runOnMainSync { page.finish() }
        instrumentation.waitForIdleSync()

        open()
        onView(withId(R.id.attitudePitchHorizon)).check(matches(isDisplayed()))
        onView(pageTwo).perform(click())
        onView(withId(R.id.dashboardFridgeLogo)).check(matches(isDisplayed()))
        val settings = AppSettings(context)
        assertEquals(original, settings.dashboardCards)
        assertTrue(settings.dashboardCardsForPage(2).isEmpty())
    }

    @Test fun editRequiresOneSecondHoldAndCancelledHoldDoesNothing() {
        val page = open()
        val button = page.findViewById<View>(R.id.homeVehicleInfoButton)
        touch(button, MotionEvent.ACTION_DOWN)
        SystemClock.sleep(200)
        touch(button, MotionEvent.ACTION_CANCEL)
        SystemClock.sleep(1_100)
        instrumentation.runOnMainSync {
            assertFalse(page.findViewById<DashboardGrid>(R.id.dashboardGrid).editing)
        }
        touch(button, MotionEvent.ACTION_DOWN)
        SystemClock.sleep(700)
        instrumentation.runOnMainSync {
            assertFalse("A hold shorter than 1 second must not edit", page.findViewById<DashboardGrid>(R.id.dashboardGrid).editing)
        }
        SystemClock.sleep(500)
        touch(button, MotionEvent.ACTION_UP)
        instrumentation.runOnMainSync {
            assertTrue(page.findViewById<DashboardGrid>(R.id.dashboardGrid).editing)
            page.findViewById<View>(R.id.dashboardDone).performClick()
            assertFalse(page.findViewById<DashboardGrid>(R.id.dashboardGrid).editing)
        }
    }

    @Test fun holdingCardTitleForOneSecondEntersEditMode() {
        val page = open()
        val point = centerOnScreen(page.findViewById(R.id.infoBlockTitle))
        val down = SystemClock.uptimeMillis()
        injectTouch(down, MotionEvent.ACTION_DOWN, point[0], point[1])
        try {
            SystemClock.sleep(700)
            instrumentation.runOnMainSync {
                assertFalse(page.findViewById<DashboardGrid>(R.id.dashboardGrid).editing)
            }
            SystemClock.sleep(500)
            instrumentation.runOnMainSync {
                assertTrue(page.findViewById<DashboardGrid>(R.id.dashboardGrid).editing)
                assertEquals(View.VISIBLE, page.findViewById<View>(R.id.dashboardEditToolbar).visibility)
            }
        } finally {
            injectTouch(down, MotionEvent.ACTION_UP, point[0], point[1])
        }
        instrumentation.runOnMainSync {
            assertEquals(DashboardCard.defaultCards, AppSettings(context).dashboardCards)
        }
    }

    @Test fun swipingFromCardScrollsWithoutEnteringEditMode() {
        val page = open()
        lateinit var card: View
        instrumentation.runOnMainSync {
            val scroll = page.findViewById<ScrollView>(R.id.dashboardScroll)
            scroll.layoutParams = scroll.layoutParams.apply {
                height = (240 * page.resources.displayMetrics.density).toInt()
            }
            card = (page.findViewById<DashboardGrid>(R.id.dashboardGrid).getChildAt(0) as ViewGroup).getChildAt(0)
        }
        instrumentation.waitForIdleSync()
        val point = centerOnScreen(card)
        val distance = 120 * page.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis()
        injectTouch(down, MotionEvent.ACTION_DOWN, point[0], point[1])
        try {
            for (step in 1..6) {
                injectTouch(down, MotionEvent.ACTION_MOVE, point[0], point[1] - distance * step / 6)
                SystemClock.sleep(40)
            }
        } finally {
            injectTouch(down, MotionEvent.ACTION_UP, point[0], point[1] - distance)
        }
        SystemClock.sleep(1_100)
        instrumentation.runOnMainSync {
            assertFalse(page.findViewById<DashboardGrid>(R.id.dashboardGrid).editing)
            assertTrue("Cards must still scroll normally", page.findViewById<ScrollView>(R.id.dashboardScroll).scrollY > 0)
        }
    }

    @Test fun bothVehicleGaugesAreShownAndHoldingOneEntersEditMode() {
        AppSettings(context).dashboardCards = listOf(DashboardCard.ATTITUDE)
        val page = open()
        onView(withId(R.id.attitudeRollHorizon)).check(matches(isDisplayed()))
        onView(withId(R.id.attitudePitchHorizon)).check(matches(isDisplayed()))
        instrumentation.runOnMainSync {
            val roll = page.findViewById<AttitudeHorizonView>(R.id.attitudeRollHorizon)
            val pitch = page.findViewById<AttitudeHorizonView>(R.id.attitudePitchHorizon)
            assertEquals(AttitudeHorizonView.AxisMode.ROLL, roll.axisMode)
            assertEquals(AttitudeHorizonView.AxisMode.PITCH, pitch.axisMode)
            assertTrue(pitch.right < roll.left)
            fun assertAngle(gauge: AttitudeHorizonView, label: Int, angle: Double) {
                val value = String.format(page.resources.configuration.locales[0], "%.1f", angle)
                assertEquals(page.getString(label, value), gauge.contentDescription)
            }
            page.bindDashboardAttitude(ax = 0.0, ay = 1.0, az = 1.0)
            assertAngle(pitch, R.string.vehicle_info_pitch_fmt, 45.0)
            assertAngle(roll, R.string.vehicle_info_roll_fmt, -0.0)
            page.bindDashboardAttitude(ax = 1.0, ay = 0.0, az = 1.0)
            assertAngle(pitch, R.string.vehicle_info_pitch_fmt, 0.0)
            assertAngle(roll, R.string.vehicle_info_roll_fmt, -45.0)
        }
        val point = centerOnScreen(page.findViewById(R.id.attitudePitchHorizon))
        val down = SystemClock.uptimeMillis()
        injectTouch(down, MotionEvent.ACTION_DOWN, point[0], point[1])
        try {
            SystemClock.sleep(1_200)
        } finally {
            injectTouch(down, MotionEvent.ACTION_UP, point[0], point[1])
        }
        instrumentation.runOnMainSync {
            assertTrue(page.findViewById<DashboardGrid>(R.id.dashboardGrid).editing)
            assertEquals(listOf(DashboardCard.ATTITUDE), AppSettings(context).dashboardCards)
        }
    }

    @Test fun fridgeCardTapsOpenControlsButHoldingOnlyEntersEditMode() {
        AppSettings(context).dashboardCards = listOf(DashboardCard.EVAKOOL_FRIDGE)
        val page = open()
        onView(withId(R.id.dashboardFridgeCard)).perform(click())
        onView(withId(R.id.fridgeGraph)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withId(R.id.fridgeBack)).inRoot(isDialog()).perform(click())
        val point = centerOnScreen(page.findViewById(R.id.dashboardFridgeCard))
        val down = SystemClock.uptimeMillis()
        injectTouch(down, MotionEvent.ACTION_DOWN, point[0], point[1])
        try {
            SystemClock.sleep(1_200)
        } finally {
            injectTouch(down, MotionEvent.ACTION_UP, point[0], point[1])
        }
        instrumentation.runOnMainSync {
            assertTrue(page.findViewById<DashboardGrid>(R.id.dashboardGrid).editing)
            assertTrue("Holding a card must cancel its normal action",
                page.supportFragmentManager.fragments.none { it is FridgeControlsFragment })
        }
    }

    @Test fun removingAndAddingUsesOnlyAvailableCardsAndPersistsAcrossReopening() {
        var page = open(edit = true)
        instrumentation.runOnMainSync {
            val grid = page.findViewById<DashboardGrid>(R.id.dashboardGrid)
            val first = (grid.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
            assertEquals("Editing must not push the card content down", 0, first.getChildAt(0).top)
            val remove = first.getChildAt(first.childCount - 1)
            val density = page.resources.displayMetrics.density
            assertEquals("Keep the full touch target", (48 * density).toInt(), remove.width)
            val image = Bitmap.createBitmap(remove.width, remove.height, Bitmap.Config.ARGB_8888)
            remove.background.setBounds(0, 0, remove.width, remove.height)
            remove.background.draw(Canvas(image))
            assertEquals("Remove control should be red", ContextCompat.getColor(page, R.color.tyre_fault),
                image.getPixel(remove.width / 2, remove.height / 2))
            assertEquals("Round button corners should be transparent", 0,
                Color.alpha(image.getPixel((12 * density).toInt(), (12 * density).toInt())))
            val filledColumns = (0 until image.width).filter { x ->
                (0 until image.height).any { y -> Color.alpha(image.getPixel(x, y)) > 0 }
            }
            assertTrue("The visible remove control should be smaller than 30dp", filledColumns.size <= 30 * density)
            image.recycle()
            remove.performClick()
            val lastRow = grid.getChildAt(grid.childCount - 1) as ViewGroup
            val plus = lastRow.getChildAt(2) as ViewGroup
            plus.getChildAt(0).performClick()
        }
        onView(withText(R.string.dashboard_card_library)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText(R.string.vehicle_info_row_soc)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withContentDescription(page.getString(
            R.string.dashboard_add_named_card, page.getString(R.string.vehicle_info_block_battery),
        ))).inRoot(isDialog()).check { view, error ->
            if (error != null) throw error
            val preview = view.parent as View
            val root = view.rootView as ViewGroup
            val bounds = android.graphics.Rect()
            preview.getDrawingRect(bounds)
            root.offsetDescendantRectToMyCoords(preview, bounds)
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(image))
            val inset = (8 * page.resources.displayMetrics.density).toInt()
            val cardColor = image.getPixel(bounds.left + inset, bounds.centerY())
            val libraryColor = image.getPixel(bounds.left - inset, bounds.centerY())
            assertTrue("Card preview must stand out against the library background",
                ColorUtils.calculateContrast(cardColor, libraryColor) >= 1.4)
            image.recycle()
        }
        pressBack()
        instrumentation.runOnMainSync {
            val grid = page.findViewById<DashboardGrid>(R.id.dashboardGrid)
            val row = grid.getChildAt(grid.childCount - 1) as ViewGroup
            ((row.getChildAt(2) as ViewGroup).getChildAt(0)).performClick()
        }
        onView(withText(R.string.vehicle_info_row_soc)).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withContentDescription(page.getString(
            R.string.dashboard_add_named_card, page.getString(R.string.vehicle_info_block_battery),
        ))).inRoot(isDialog()).perform(click())
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(DashboardCard.BATTERY, AppSettings(context).dashboardCards.last())
        }
        onView(withContentDescription(R.string.dashboard_add_card)).perform(scrollTo(), click())
        onView(withContentDescription(page.getString(
            R.string.dashboard_add_named_card, page.getString(R.string.fridge_unknown_name),
        ))).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withContentDescription(R.string.dashboard_add_card)).perform(scrollTo(), click())
        onView(withText(R.string.dashboard_all_cards_added)).inRoot(isDialog()).check(matches(isDisplayed()))
        pressBack()
        instrumentation.runOnMainSync { page.finish() }
        instrumentation.waitForIdleSync()
        page = open()
        instrumentation.runOnMainSync {
            val grid = page.findViewById<DashboardGrid>(R.id.dashboardGrid)
            assertFalse(grid.editing)
            assertEquals(DashboardCard.BATTERY, (grid.getChildAt(2) as ViewGroup).getChildAt(2).tag)
            page.enterDashboardEditMode()
            for (card in DashboardCard.entries) {
                val first = (grid.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
                first.getChildAt(first.childCount - 1).performClick()
            }
            assertTrue(AppSettings(context).dashboardCards.isEmpty())
            page.finish()
        }
        instrumentation.waitForIdleSync()
        page = open(edit = true)
        instrumentation.runOnMainSync {
            val grid = page.findViewById<DashboardGrid>(R.id.dashboardGrid)
            assertEquals(1, grid.childCount)
            val row = grid.getChildAt(0) as ViewGroup
            assertNull(row.getChildAt(0).tag)
            assertEquals(1, (row.getChildAt(0) as ViewGroup).childCount)
            assertTrue(AppSettings(context).dashboardCards.isEmpty())
        }
    }

    @Test fun draggingIntoGapsAndOntoCardsInsertsRatherThanSwaps() {
        val page = open(edit = true)
        val original = DashboardCard.defaultCards
        dragCard(page, sourceIndex = 2, targetIndex = 1, gapBefore = true)
        instrumentation.runOnMainSync {
            assertEquals(original.toMutableList().apply { add(1, removeAt(2)) }, AppSettings(context).dashboardCards)
        }
        dragCard(page, sourceIndex = 0, targetIndex = 2)
        instrumentation.runOnMainSync {
            val expected = original.toMutableList().apply {
                this[0] = original[2]
                this[2] = original[0]
            }
            assertEquals(expected, AppSettings(context).dashboardCards)
            val row = page.findViewById<DashboardGrid>(R.id.dashboardGrid).getChildAt(0) as ViewGroup
            assertEquals(expected.take(3), (0..2).map { row.getChildAt(it).tag })
        }
    }

    @Test fun offlineFridgeCanBeAddedFromTheLibraryAndOpensTheFullControlsPopup() {
        AppSettings(context).dashboardCards = emptyList()
        var page = open(edit = true)
        assertNull("This case requires a disconnected fridge", OpenDiKeyApp.from(context).fridge.status)
        onView(withContentDescription(R.string.dashboard_add_card)).perform(click())
        onView(withId(R.id.dashboardFridgeLogo)).inRoot(isDialog()).perform(scrollTo())
            .check(matches(isDisplayed()))
        onView(withText(R.string.dashboard_fridge_disconnected)).inRoot(isDialog()).perform(scrollTo())
            .check(matches(isDisplayed()))
        onView(withContentDescription(page.getString(
            R.string.dashboard_add_named_card, page.getString(R.string.fridge_unknown_name),
        ))).inRoot(isDialog()).perform(scrollTo(), click())
        instrumentation.runOnMainSync {
            assertEquals(listOf(DashboardCard.EVAKOOL_FRIDGE), AppSettings(context).dashboardCards)
        }
        onView(withId(R.id.dashboardDone)).perform(click())
        onView(withId(R.id.dashboardFridgeCard)).perform(click())
        for (id in listOf(
            R.id.fridgeGraph, R.id.fridgePower, R.id.fridgeLeftCard, R.id.fridgeRightCard,
            R.id.fridgeRangeHour, R.id.fridgeRangeDay, R.id.fridgeRangeWeek,
        )) {
            onView(withId(id)).inRoot(isDialog()).check(matches(isDisplayed()))
        }
        for (id in listOf(R.id.fridgeModeEco, R.id.fridgeModeMax)) {
            onView(withId(id)).inRoot(isDialog()).perform(scrollTo()).check(matches(isDisplayed()))
        }
        instrumentation.runOnMainSync {
            val popup = page.supportFragmentManager.fragments.filterIsInstance<FridgeControlsFragment>().single()
            val decor = requireNotNull(popup.dialog).window!!.decorView
            assertTrue("Fridge controls must be a popup, not another full page",
                decor.width < page.window.decorView.width && decor.height < page.window.decorView.height)
            assertNull(popup.requireView().findViewById<View>(R.id.homeSidebar))
            assertFalse(page.findViewById<DashboardGrid>(R.id.dashboardGrid).editing)
        }
        onView(withId(R.id.fridgeMore)).inRoot(isDialog()).perform(scrollTo(), click())
        onView(withText(R.string.fridge_details)).inRoot(isDialog()).perform(click())
        onView(withText(android.R.string.ok)).inRoot(isDialog()).perform(click())
        onView(withId(R.id.fridgeBack)).inRoot(isDialog()).perform(click())
        instrumentation.runOnMainSync {
            assertTrue(page.supportFragmentManager.fragments.none { it is FridgeControlsFragment })
            page.finish()
        }
        instrumentation.waitForIdleSync()
        page = open()
        instrumentation.runOnMainSync {
            assertEquals(listOf(DashboardCard.EVAKOOL_FRIDGE), AppSettings(context).dashboardCards)
        }
        onView(withText(R.string.dashboard_fridge_disconnected)).check(matches(isDisplayed()))
        onView(withId(R.id.dashboardFridgeLogo)).check(matches(isDisplayed()))
    }

    @Test fun draggingAcrossRowsAndIntoTheNextFreeSlotKeepsCardsPacked() {
        val original = DashboardCard.entries.take(4)
        AppSettings(context).dashboardCards = original
        val page = open(edit = true)
        dragCard(page, sourceIndex = 3, targetIndex = 1, gapBefore = true)
        instrumentation.runOnMainSync {
            assertEquals(listOf(original[0], original[3], original[1], original[2]), AppSettings(context).dashboardCards)
        }
        dragCard(page, sourceIndex = 0, targetIndex = 4)
        instrumentation.runOnMainSync {
            val expected = listOf(original[3], original[1], original[2], original[0])
            assertEquals(expected, AppSettings(context).dashboardCards)
            val grid = page.findViewById<DashboardGrid>(R.id.dashboardGrid)
            assertEquals(2, grid.childCount)
            val lastRow = grid.getChildAt(1) as ViewGroup
            assertEquals(original[0], lastRow.getChildAt(0).tag)
            assertNull(lastRow.getChildAt(1).tag)
        }
    }

    @Test fun hoveringForHalfASecondOpensABlankSlotAndPreviewsTheActualDropLayout() {
        val original = DashboardCard.entries.take(4)
        AppSettings(context).dashboardCards = original
        val page = open(edit = true)
        val expected = listOf(original[0], original[3], original[1], original[2])
        var previewBounds = emptyMap<DashboardCard, android.graphics.Rect>()
        var sawAnimatedMovement = false
        val grid = page.findViewById<DashboardGrid>(R.id.dashboardGrid)
        val previewFrames = android.view.ViewTreeObserver.OnPreDrawListener {
            if (dashboardSlots(page).any {
                it.visibility == View.VISIBLE && (it.translationX != 0f || it.translationY != 0f)
            }) sawAnimatedMovement = true
            true
        }
        instrumentation.runOnMainSync { grid.viewTreeObserver.addOnPreDrawListener(previewFrames) }
        dragCard(page, sourceIndex = 3, targetIndex = 1, gapBefore = true, duringHover = {
            SystemClock.sleep(400)
            instrumentation.runOnMainSync {
                assertEquals("Do not shift cards before the hover delay", original, displayedCards(page))
                assertTrue(dashboardSlots(page).all { it.visibility == View.VISIBLE })
            }
            SystemClock.sleep(450)
            instrumentation.runOnMainSync {
                grid.viewTreeObserver.removeOnPreDrawListener(previewFrames)
                assertEquals(expected, displayedCards(page))
                assertTrue("Cards should animate rather than jump into position",
                    sawAnimatedMovement || !android.animation.ValueAnimator.areAnimatorsEnabled())
                assertEquals(View.INVISIBLE, dashboardSlots(page)[1].visibility)
                assertEquals("Hovering must not save a new order", original, AppSettings(context).dashboardCards)
                previewBounds = slotBounds(page)
                assertTrue("The right-hand card should move into the next column",
                    previewBounds.getValue(original[1]).left > previewBounds.getValue(original[3]).left)
                assertTrue("Following cards should wrap onto the next row",
                    previewBounds.getValue(original[2]).top > previewBounds.getValue(original[1]).top)
            }
        })
        instrumentation.runOnMainSync {
            assertEquals(expected, AppSettings(context).dashboardCards)
            assertEquals(previewBounds, slotBounds(page))
            assertTrue(dashboardSlots(page).all { it.visibility == View.VISIBLE })
        }
        val appended = expected.drop(1) + expected.first()
        dragCard(page, sourceIndex = 0, targetIndex = 4, duringHover = {
            SystemClock.sleep(850)
            instrumentation.runOnMainSync {
                assertEquals(appended, displayedCards(page))
                assertEquals(View.INVISIBLE, dashboardSlots(page).last().visibility)
                assertEquals(expected, AppSettings(context).dashboardCards)
                previewBounds = slotBounds(page)
            }
        })
        instrumentation.runOnMainSync {
            assertEquals(appended, AppSettings(context).dashboardCards)
            assertEquals(previewBounds, slotBounds(page))
            assertTrue(dashboardSlots(page).all { it.visibility == View.VISIBLE })
        }
    }

    @Test fun changingTheInsertionMarkerRestartsTheDelayAndCancellingRestoresCards() {
        val original = DashboardCard.entries.take(4)
        AppSettings(context).dashboardCards = original
        val page = open(edit = true)
        dragCard(page, sourceIndex = 3, targetIndex = 1, gapBefore = true, cancel = true, duringHover = {
            SystemClock.sleep(200)
            lateinit var firstGap: FloatArray
            lateinit var point: FloatArray
            instrumentation.runOnMainSync {
                val slots = dashboardSlots(page)
                val position = IntArray(2)
                slots[1].getLocationOnScreen(position)
                firstGap = floatArrayOf(
                    position[0] - 5 * page.resources.displayMetrics.density,
                    position[1] + slots[1].height / 2f,
                )
                val slot = slots[2]
                slot.getLocationOnScreen(position)
                point = floatArrayOf(
                    position[0] - 5 * page.resources.displayMetrics.density,
                    position[1] + slot.height / 2f,
                )
            }
            injectTouch(SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, point[0], point[1])
            SystemClock.sleep(350)
            instrumentation.runOnMainSync {
                assertEquals("Moving the marker must restart the half-second delay", original, displayedCards(page))
            }
            SystemClock.sleep(500)
            instrumentation.runOnMainSync {
                assertEquals(listOf(original[0], original[1], original[3], original[2]), displayedCards(page))
                assertEquals(View.INVISIBLE, dashboardSlots(page)[2].visibility)
                assertEquals(original, AppSettings(context).dashboardCards)
            }
            injectTouch(SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, firstGap[0], firstGap[1])
            SystemClock.sleep(850)
            instrumentation.runOnMainSync {
                assertEquals(listOf(original[0], original[3], original[1], original[2]), displayedCards(page))
                assertEquals(View.INVISIBLE, dashboardSlots(page)[1].visibility)
                assertEquals(original, AppSettings(context).dashboardCards)
            }
        })
        SystemClock.sleep(800)
        instrumentation.runOnMainSync {
            assertEquals(original, displayedCards(page))
            assertEquals(original, AppSettings(context).dashboardCards)
            dashboardSlots(page).forEach {
                assertEquals("Cancelled drag should restore ${it.tag}", View.VISIBLE, it.visibility)
                assertEquals("Cancelled drag should finish moving ${it.tag}", 0f, it.translationX, 0.1f)
                assertEquals("Cancelled drag should finish moving ${it.tag}", 0f, it.translationY, 0.1f)
            }
        }
    }

    private fun dashboardSlots(page: VehicleInfoActivity): List<View> {
        val grid = page.findViewById<DashboardGrid>(R.id.dashboardGrid)
        return (0 until grid.childCount).flatMap { rowIndex ->
            val row = grid.getChildAt(rowIndex) as ViewGroup
            (0 until row.childCount).map { row.getChildAt(it) }.filter { it.tag is DashboardCard }
        }
    }

    private fun displayedCards(page: VehicleInfoActivity): List<DashboardCard> =
        dashboardSlots(page).map { it.tag as DashboardCard }

    private fun slotBounds(page: VehicleInfoActivity): Map<DashboardCard, android.graphics.Rect> =
        dashboardSlots(page).associate { slot ->
            val row = slot.parent as View
            slot.tag as DashboardCard to android.graphics.Rect(
                slot.left, row.top + slot.top, slot.right, row.top + slot.bottom,
            )
        }

    private fun dragCard(
        page: VehicleInfoActivity,
        sourceIndex: Int,
        targetIndex: Int,
        gapBefore: Boolean = false,
        cancel: Boolean = false,
        duringHover: (() -> Unit)? = null,
    ) {
        lateinit var from: FloatArray
        lateinit var to: FloatArray
        instrumentation.runOnMainSync {
            page.findViewById<ScrollView>(R.id.dashboardScroll).scrollTo(0, 0)
            val grid = page.findViewById<DashboardGrid>(R.id.dashboardGrid)
            fun slot(index: Int): View = (grid.getChildAt(index / 3) as ViewGroup).getChildAt(index % 3)
            fun point(view: View, fraction: Float): FloatArray {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                return floatArrayOf(location[0] + view.width * fraction, location[1] + view.height / 2f)
            }
            from = point(slot(sourceIndex), 0.5f)
            to = point(slot(targetIndex), if (gapBefore) 0f else 0.75f)
            if (gapBefore) to[0] -= 5 * page.resources.displayMetrics.density
        }
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) = injectTouch(down, action, x, y)
        send(MotionEvent.ACTION_DOWN, from[0], from[1])
        try {
            SystemClock.sleep(800)
            val steps = if (duringHover == null) 15 else 1
            for (step in 1..steps) {
                send(MotionEvent.ACTION_MOVE,
                    from[0] + (to[0] - from[0]) * step / steps,
                    from[1] + (to[1] - from[1]) * step / steps)
                SystemClock.sleep(40)
            }
            instrumentation.waitForIdleSync()
            if (gapBefore) instrumentation.runOnMainSync {
                val grid = page.findViewById<DashboardGrid>(R.id.dashboardGrid)
                val location = IntArray(2)
                grid.getLocationOnScreen(location)
                val image = Bitmap.createBitmap(grid.width, grid.height, Bitmap.Config.ARGB_8888)
                grid.draw(Canvas(image))
                assertEquals("Show the insertion marker in the gap", ContextCompat.getColor(page, R.color.accent_orange),
                    image.getPixel((to[0] - location[0]).toInt(), (to[1] - location[1]).toInt()))
                image.recycle()
            }
            duringHover?.invoke()
        } finally {
            send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, to[0], to[1])
        }
        instrumentation.waitForIdleSync()
    }

    private fun centerOnScreen(view: View): FloatArray {
        val bounds = android.graphics.Rect()
        instrumentation.runOnMainSync { assertTrue(view.getGlobalVisibleRect(bounds)) }
        return floatArrayOf(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun injectTouch(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun open(edit: Boolean = false, samples: Boolean = false): VehicleInfoActivity {
        val page = instrumentation.startActivitySync(
            Intent(context, VehicleInfoActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(VehicleInfoActivity.EXTRA_EDIT_DASHBOARD, edit)
                .apply { if (!samples) putExtra(VehicleInfoActivity.EXTRA_LIVE_DASHBOARD, true) },
        ) as VehicleInfoActivity
        activity = page
        instrumentation.waitForIdleSync()
        return page
    }

    private fun touch(view: View, action: Int) {
        instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, view.width / 2f, view.height / 2f, 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
    }
}
