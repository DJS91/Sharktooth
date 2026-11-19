package com.sphy.airconcontroller.ui

import com.sphy.airconcontroller.byd.BydTyreController
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.byd.TabletSocReader
import com.sphy.airconcontroller.fridge.EvakoolProtocol

internal object DashboardSamples {
    val tripUsage = TripUsageTracker.State(
        lastGear = 4, startCharge = 82.0, startFuel = 65.0,
        startedAtMs = System.currentTimeMillis() - 17 * 60_000L, startOdometerKm = 12_468.0,
    )

    val vehicle = BydVehicleInfoController.Snapshot(
        sdkInjected = false,
        bindNote = null,
        battery = BydVehicleInfoController.BatteryInfo(
            bound = true, socPct = 76.0, sohPct = 98.0, fuelPct = 61.0,
            remainingUsableKwh = 22.5, packEnergyKwh = 29.6, capacityAh = 110.0,
            evRangeKm = 64.0, fuelRangeKm = 430.0, combinedRangeKm = 494.0,
            odoKm = 12_480.0, evMilesKm = 7_200.0, hevMilesKm = 5_280.0,
            driveTimeH = 240.0, avgSpeedKmh = 52.0,
            lifetimeElecKwh = 2_160.0, lifetimeElecAvg = 30.0, lifetimeChargedKwh = 1_940.0,
            lifetimeFuelL = 480.0, lifetimeFuelAvg = 9.1,
        ),
        dynamics = BydVehicleInfoController.DynamicsInfo(
            speedKmh = 48.0, gearLabel = "D", parkBrake = 0,
            accelPedalPct = 28.0, brakePedalPct = 0.0,
            enginePowerKw = 35.0, engineRpm = 1_650.0, oilLevel = 85.0, displacementL = 1.5,
            outsideTempC = 23.0, steeringDeg = -7.0,
        ),
        imu = BydVehicleInfoController.ImuInfo(),
        tyres = BydTyreController.TyreSnapshot(
            sdkInjected = false, bound = true, bindError = null,
            corners = BydTyreController.Corner.entries.mapIndexed { index, corner ->
                BydTyreController.CornerReading(
                    corner = corner, area = corner.area, pressureKpa = 245 + index * 3,
                    pressureByType = 355f + index * 4, temperature = 40 + 25 + index,
                    pressureState = "normal", airLeakState = "ok", signalState = "ok",
                )
            },
            batteryState = null, systemState = null, temperatureState = null,
        ),
        devicesBound = emptyMap(),
    )
    val headUnit = TabletSocReader.Snapshot(
        cpuPct = 24.0, ramUsedMb = 3_120, ramTotalMb = 6_144,
        storageUsedGb = 28.4, storageTotalGb = 64.0,
    )
    val fridge = EvakoolProtocol.Status(
        on = true, celsius = true, left = 4, right = -12,
        leftTarget = 3, rightTarget = -14, voltage = "12.7",
    )
}
