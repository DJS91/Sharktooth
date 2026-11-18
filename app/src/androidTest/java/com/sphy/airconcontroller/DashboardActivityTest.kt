package com.sphy.airconcontroller

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.LinearLayout
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
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
import com.sphy.airconcontroller.ui.DashboardCard
import com.sphy.airconcontroller.ui.DashboardGrid
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
    private var previousCards: String? = null
    private var activity: VehicleInfoActivity? = null

    @Before fun preserveLayout() {
        previousCards = prefs.getString("dashboard_cards", null)
        prefs.edit().remove("dashboard_cards").commit()
    }

    @After fun restoreLayout() {
        activity?.let { page -> instrumentation.runOnMainSync { page.finish() } }
        instrumentation.waitForIdleSync()
        prefs.edit().putString("dashboard_cards", previousCards).commit()
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
            assertEquals(DashboardCard.entries, AppSettings(context).dashboardCards)
        }
    }

    @Test fun swipingFromCardScrollsWithoutEnteringEditMode() {
        val page = open()
        lateinit var card: View
        instrumentation.runOnMainSync {
            val scroll = page.findViewById<ScrollView>(R.id.dashboardScroll)
            scroll.layoutParams = (scroll.layoutParams as LinearLayout.LayoutParams).apply {
                height = (240 * page.resources.displayMetrics.density).toInt()
                weight = 0f
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

    @Test fun cardControlTapsStillWorkButHoldingDoesNotToggleTheControl() {
        val settings = AppSettings(context)
        val previous = settings.vehicleInfoAttitudeSplit
        try {
            val page = open()
            onView(withId(R.id.attitudeSplitSwitch)).perform(scrollTo(), click())
            SystemClock.sleep(1_100)
            instrumentation.runOnMainSync {
                assertEquals(!previous, settings.vehicleInfoAttitudeSplit)
                assertFalse(page.findViewById<DashboardGrid>(R.id.dashboardGrid).editing)
            }
            val point = centerOnScreen(page.findViewById(R.id.attitudeSplitSwitch))
            val down = SystemClock.uptimeMillis()
            injectTouch(down, MotionEvent.ACTION_DOWN, point[0], point[1])
            try {
                SystemClock.sleep(1_200)
            } finally {
                injectTouch(down, MotionEvent.ACTION_UP, point[0], point[1])
            }
            instrumentation.runOnMainSync {
                assertTrue(page.findViewById<DashboardGrid>(R.id.dashboardGrid).editing)
                assertEquals("Holding a control must cancel its normal click", !previous, settings.vehicleInfoAttitudeSplit)
            }
        } finally {
            settings.vehicleInfoAttitudeSplit = previous
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
        onView(withText(R.string.dashboard_card_library)).check(matches(isDisplayed()))
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
        onView(withText(android.R.string.cancel)).perform(click())
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
        onView(withText(R.string.dashboard_all_cards_added)).check(matches(isDisplayed()))
        onView(withText(android.R.string.cancel)).perform(click())
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
        val original = DashboardCard.entries
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

    private fun dragCard(page: VehicleInfoActivity, sourceIndex: Int, targetIndex: Int, gapBefore: Boolean = false) {
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
            for (step in 1..15) {
                send(MotionEvent.ACTION_MOVE,
                    from[0] + (to[0] - from[0]) * step / 15,
                    from[1] + (to[1] - from[1]) * step / 15)
                SystemClock.sleep(40)
            }
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
        } finally {
            send(MotionEvent.ACTION_UP, to[0], to[1])
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

    private fun open(edit: Boolean = false): VehicleInfoActivity {
        val page = instrumentation.startActivitySync(
            Intent(context, VehicleInfoActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(VehicleInfoActivity.EXTRA_EDIT_DASHBOARD, edit),
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
