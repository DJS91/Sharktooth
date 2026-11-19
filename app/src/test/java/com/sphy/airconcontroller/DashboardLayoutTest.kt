package com.sphy.airconcontroller

import com.sphy.airconcontroller.ui.DashboardCard
import com.sphy.airconcontroller.ui.DashboardLayout
import org.junit.Assert.*
import org.junit.Test

class DashboardLayoutTest {
    private val optionalCards = listOf(DashboardCard.EVAKOOL_FRIDGE, DashboardCard.POWER_FLOW)

    @Test fun removingAndReaddingReturnsCardToLibraryAndAppendsIt() {
        val layout = DashboardLayout()
        val first = layout.cards.first()
        layout.remove(first)
        assertEquals(listOf(first) + optionalCards, layout.available)
        layout.add(first)
        assertEquals(first, layout.cards.last())
        assertEquals(optionalCards, layout.available)
    }

    @Test fun optionalLibraryCardsAreNotAddedByDefaultAndTheirSelectionPersists() {
        val layout = DashboardLayout()
        assertEquals(DashboardCard.defaultCards, layout.cards)
        assertEquals(optionalCards, layout.available)
        optionalCards.forEach(layout::add)
        assertTrue(layout.available.isEmpty())
        assertEquals(layout.cards, DashboardLayout(layout.cards).cards)
        optionalCards.forEach(layout::remove)
        assertEquals(optionalCards, layout.available)
    }

    @Test fun movingForwardShiftsInterveningCardsLeftAndPreservesSavedOrder() {
        val layout = DashboardLayout()
        val original = layout.cards
        layout.move(original[0], 7)
        val expected = original.toMutableList().apply {
            add(6, removeAt(0))
        }
        assertEquals(expected, layout.cards)
        assertEquals(expected, DashboardLayout(layout.cards).cards)
    }

    @Test fun movingBackwardShiftsCardsRightAndCanAppendAtTheEnd() {
        val layout = DashboardLayout()
        val original = layout.cards
        layout.move(original.last(), 1)
        val expected = original.toMutableList().apply { add(1, removeAt(lastIndex)) }
        assertEquals(expected, layout.cards)
        assertEquals(original.size, layout.cards.distinct().size)
        layout.move(original.last(), original.size)
        assertEquals(original, layout.cards)
    }

    @Test fun droppingOnEitherSideOfTheSourceKeepsItsPosition() {
        val layout = DashboardLayout()
        val original = layout.cards
        layout.move(original[3], 3)
        assertEquals(original, layout.cards)
        layout.move(original[3], 4)
        assertEquals(original, layout.cards)
    }

    @Test fun previewShowsTheDropOrderWithoutChangingTheSavedLayout() {
        val layout = DashboardLayout()
        val original = layout.cards
        val preview = layout.previewMove(original[3], 1)
        assertEquals(original.toMutableList().apply { add(1, removeAt(3)) }, preview)
        assertEquals(original, layout.cards)
        layout.move(original[3], 1)
        assertEquals(preview, layout.cards)
    }

    @Test fun emptyDashboardStaysEmptyAndCanBeRepopulated() {
        val layout = DashboardLayout(emptyList())
        assertTrue(layout.cards.isEmpty())
        assertEquals(DashboardCard.entries, layout.available)
        layout.add(DashboardCard.TYRES)
        layout.remove(DashboardCard.TYRES)
        assertTrue(DashboardLayout(layout.cards).cards.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateCardCannotBeAdded() {
        DashboardLayout().add(DashboardCard.BATTERY)
    }
}
