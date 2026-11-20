package com.sphy.airconcontroller.camp

import com.sphy.airconcontroller.byd.BydAcController
import kotlinx.coroutines.delay

/** In-memory debug simulation with no vehicle or device dependencies. */
internal class CampModePreviewActions : CampActions {
    override suspend fun save() = CampSavedState(
        climate = BydAcController.AcSnapshot(
            sdkInjected = false, bound = false, bindError = null, powerOn = true,
            driverTempC = 22, passengerTempC = 22, outsideTempC = 23, fanLevel = 3,
            controlMode = null, cycleMode = null, windMode = null, compressorMode = null,
            maxCool = false, ventilation = false, frontDefrost = false, rearDefrost = false,
            tempSynced = true,
        ),
        mediaVolume = 7, mediaMuted = false, drlOn = true,
        manageHeadunitBluetooth = true, clusterBrightness = 7, hudState = 1,
    )

    private suspend fun simulate(): CampResult {
        delay(100)
        return CampResult(true, "Debug simulation; no vehicle actions performed")
    }

    override suspend fun dikeyLightsOff(saved: CampSavedState) = simulate()
    override suspend fun disconnectCarPlay() = simulate()
    override suspend fun headunitBluetoothOff() = simulate()
    override suspend fun climateOff() = simulate()
    override suspend fun closeWindows() = simulate()
    override suspend fun drlOff() = simulate()
    override suspend fun dimCluster() = simulate()
    override suspend fun hudOff() = simulate()
    override suspend fun muteMedia() = simulate()
    override suspend fun screenOff() = simulate()
    override suspend fun stopWindows() = simulate()
    override suspend fun restoreClimate(saved: CampSavedState) = simulate()
    override suspend fun restoreMedia(saved: CampSavedState) = simulate()
    override suspend fun restoreDrl(saved: CampSavedState) = simulate()
    override suspend fun restoreCluster(saved: CampSavedState) = simulate()
    override suspend fun restoreHud(saved: CampSavedState) = simulate()
    override suspend fun enableHeadunitBluetooth(saved: CampSavedState) = simulate()
    override suspend fun enableDikeyLights(saved: CampSavedState) = simulate()

    override suspend fun persist(
        saved: CampSavedState?, report: String, completedSteps: Set<CampStep>, remindHeadlights: Boolean,
        failedSteps: Map<CampStep, CampActionError>, sessionError: String?,
    ) {
        // Preview sessions must never overwrite live restoration data.
    }
}
