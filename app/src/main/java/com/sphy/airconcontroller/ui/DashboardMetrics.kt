package com.sphy.airconcontroller.ui

import android.content.Context
import androidx.annotation.StringRes
import com.sphy.airconcontroller.R
import com.sphy.airconcontroller.byd.BydTyreController
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.byd.TabletSocReader

internal object DashboardMetrics {
    data class Metric(val key: String, @param:StringRes val label: Int, val unit: String) {
        fun title(context: Context): String = context.getString(
            R.string.dashboard_history_metric, context.getString(label), unit,
        )
    }

    fun forCard(card: DashboardCard): List<Metric> = when (card) {
        DashboardCard.BATTERY -> listOf(
            Metric("soc", R.string.vehicle_info_row_soc, "%"), Metric("fuel", R.string.vehicle_info_row_fuel, "%"),
            Metric("soh", R.string.vehicle_info_row_soh, "%"), Metric("evRange", R.string.vehicle_info_row_ev_range, "km"),
            Metric("fuelRange", R.string.vehicle_info_row_fuel_range, "km"),
            Metric("combinedRange", R.string.vehicle_info_row_combined, "km"),
            Metric("tripDistance", R.string.dashboard_distance_driven, "km"),
        )
        DashboardCard.DRIVE -> listOf(
            Metric("speed", R.string.dashboard_speed, "km/h"),
            Metric("accelerator", R.string.dashboard_pedal_accelerate, "%"),
            Metric("brake", R.string.vehicle_info_row_brake, "%"),
        )
        DashboardCard.CONSUMPTION -> listOf(
            Metric("fuelAvg", R.string.dashboard_fuel_economy, "L/100km"),
            Metric("electricUsed", R.string.vehicle_info_row_life_ev, "kWh"),
            Metric("electricAvg", R.string.dashboard_history_electric_average, "kWh/100km"),
            Metric("charged", R.string.vehicle_info_row_life_charged, "kWh"),
            Metric("fuelUsed", R.string.dashboard_lifetime_fuel_used, "L"),
        )
        DashboardCard.MILEAGE -> listOf(
            Metric("odo", R.string.vehicle_info_row_odo, "km"),
            Metric("evDistance", R.string.dashboard_history_ev_distance, "km"),
            Metric("hevDistance", R.string.dashboard_history_hev_distance, "km"),
            Metric("driveTime", R.string.vehicle_info_row_drive_time, "h"),
            Metric("avgSpeed", R.string.vehicle_info_row_avg_speed, "km/h"),
        )
        DashboardCard.ATTITUDE -> listOf(
            Metric("pitch", R.string.dashboard_history_pitch, "°"), Metric("roll", R.string.dashboard_history_roll, "°"),
        )
        DashboardCard.TABLET -> listOf(
            Metric("cpu", R.string.vehicle_info_row_cpu, "%"), Metric("ram", R.string.vehicle_info_row_ram, "MB"),
            Metric("storage", R.string.vehicle_info_row_storage, "GB"),
        )
        DashboardCard.CHASSIS -> listOf(
            Metric("outside", R.string.dashboard_outside_temperature, "°C"),
            Metric("steering", R.string.vehicle_info_row_steer, "°"),
        )
        DashboardCard.POWER_FLOW -> listOf(Metric("regen", R.string.dashboard_flow_regen_today, "kWh"))
        DashboardCard.TYRES, DashboardCard.ENGINE, DashboardCard.EVAKOOL_FRIDGE -> emptyList()
    }

    fun values(
        snapshot: BydVehicleInfoController.Snapshot,
        tablet: TabletSocReader.Snapshot,
        regen: Double,
        pitch: Double?,
        roll: Double?,
        tripDistance: Double?,
        drive: BydVehicleInfoController.DriveLive?,
    ): Map<String, Double?> {
        val b = snapshot.battery
        val d = snapshot.dynamics
        val result = linkedMapOf(
            "soc" to b.socPct, "fuel" to b.fuelPct, "soh" to b.sohPct,
            "evRange" to b.evRangeKm, "fuelRange" to b.fuelRangeKm, "combinedRange" to b.combinedRangeKm,
            "tripDistance" to tripDistance, "speed" to (drive?.speedKmh ?: d.speedKmh),
            "accelerator" to (if (drive != null) drive.accelPedalPct else d.accelPedalPct),
            "brake" to (if (drive != null) drive.brakePedalPct else d.brakePedalPct),
            "fuelAvg" to b.lifetimeFuelAvg, "electricUsed" to b.lifetimeElecKwh,
            "electricAvg" to b.lifetimeElecAvg, "charged" to b.lifetimeChargedKwh, "fuelUsed" to b.lifetimeFuelL,
            "odo" to b.odoKm, "evDistance" to b.evMilesKm, "hevDistance" to b.hevMilesKm,
            "driveTime" to b.driveTimeH, "avgSpeed" to b.avgSpeedKmh,
            "pitch" to pitch, "roll" to roll, "cpu" to tablet.cpuPct, "ram" to tablet.ramUsedMb?.toDouble(),
            "storage" to tablet.storageUsedGb, "outside" to d.outsideTempC, "steering" to d.steeringDeg,
            "regen" to regen,
        )
        BydTyreController.Corner.entries.forEach { corner ->
            val reading = snapshot.tyres.corners.find { it.corner == corner }
            result["pressure_${corner.name}"] = reading?.pressurePsi()?.toDouble()
            result["temperature_${corner.name}"] = reading?.temperatureC()?.toDouble()
        }
        return result
    }
}
