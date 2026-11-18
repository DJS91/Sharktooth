package com.sphy.airconcontroller.ui

import androidx.annotation.StringRes
import com.sphy.airconcontroller.R

enum class DashboardCard(@param:StringRes val title: Int) {
    BATTERY(R.string.vehicle_info_block_battery),
    DRIVE(R.string.vehicle_info_block_drive),
    TYRES(R.string.vehicle_info_block_tyres),
    CONSUMPTION(R.string.vehicle_info_block_consumption),
    MILEAGE(R.string.vehicle_info_block_mileage),
    ATTITUDE(R.string.vehicle_info_block_attitude),
    TABLET(R.string.vehicle_info_block_tablet),
    ENGINE(R.string.vehicle_info_block_engine),
    CHASSIS(R.string.vehicle_info_block_chassis),
    EVAKOOL_FRIDGE(R.string.fridge_unknown_name);

    companion object {
        val defaultCards: List<DashboardCard> = entries.filterNot { it == EVAKOOL_FRIDGE }
    }
}

class DashboardLayout(initial: List<DashboardCard> = DashboardCard.defaultCards) {
    private val selected = initial.toMutableList()
    val cards: List<DashboardCard> get() = selected.toList()
    val available: List<DashboardCard> get() = DashboardCard.entries.filterNot { it in selected }

    init {
        require(initial.distinct().size == initial.size) { "Dashboard cards must be unique" }
    }

    fun add(card: DashboardCard) {
        require(card !in selected) { "Card is already on the dashboard: $card" }
        selected.add(card)
    }

    fun remove(card: DashboardCard) {
        require(selected.remove(card)) { "Card is not on the dashboard: $card" }
    }

    fun move(card: DashboardCard, insertionIndex: Int) {
        val reordered = previewMove(card, insertionIndex)
        selected.clear()
        selected.addAll(reordered)
    }

    fun previewMove(card: DashboardCard, insertionIndex: Int): List<DashboardCard> {
        val from = selected.indexOf(card)
        require(from >= 0) { "Card must be on the dashboard" }
        require(insertionIndex in 0..selected.size) { "Invalid dashboard insertion index" }
        return selected.toMutableList().apply {
            removeAt(from)
            add(if (insertionIndex > from) insertionIndex - 1 else insertionIndex, card)
        }
    }
}
