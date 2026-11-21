package com.sphy.airconcontroller.ui

import android.content.Context
import android.database.sqlite.SQLiteException
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import com.sphy.airconcontroller.byd.BydVehicleInfoController
import com.sphy.airconcontroller.byd.TabletSocReader
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.storage.DashboardHistoryStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONException
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.sqrt

internal class DashboardRecorder(context: Context) {
    data class Attitude(val pitch: Double, val roll: Double) {
        companion object {
            fun fromAcceleration(ax: Double, ay: Double, az: Double): Attitude =
                // The installed head unit's accelerometer axes are swapped relative to the vehicle.
                Attitude(Math.toDegrees(atan2(ay, az)),
                    Math.toDegrees(atan2(-ax, sqrt(ay * ay + az * az))))
        }
    }

    data class Snapshot(
        val time: Long,
        val vehicle: BydVehicleInfoController.Snapshot,
        val tablet: TabletSocReader.Snapshot,
        val regen: Double,
        val history: List<DashboardHistory.Sample>,
        val historyError: Exception?,
    )

    data class DriveSample(val reading: BydVehicleInfoController.DriveLive, val peaks: AppSettings.PedalPeaks)

    private val appContext = context.applicationContext
    private val info = BydVehicleInfoController(appContext)
    private val tabletSoc = TabletSocReader(appContext)
    private val settings = AppSettings(appContext)
    private val storage = DashboardHistoryStorage(appContext)
    private val dailyHistory = DashboardHistory(storage)
    private val regenSampler = RegenEnergySampler()
    private val fuelFillTracker = FuelFillTracker(settings.fuelFillState)
    val rpmHistory = EngineRpmHistory()
    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshot = _snapshot.asStateFlow()
    private val _drive = MutableStateFlow<DriveSample?>(null)
    val drive = _drive.asStateFlow()
    private val _power = MutableStateFlow<Double?>(null)
    val power = _power.asStateFlow()
    private val _rpm = MutableStateFlow<EngineRpmHistory.Sample?>(null)
    val rpm = _rpm.asStateFlow()
    private val _attitude = MutableStateFlow<Attitude?>(null)
    val attitude = _attitude.asStateFlow()
    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            val sensors = appContext.getSystemService(SensorManager::class.java)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    _attitude.value = Attitude.fromAcceleration(
                        event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble(),
                    )
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) sensors.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
            regenSampler.reset()
            try {
                coroutineScope {
                    launch {
                        while (isActive) {
                            val vehicle = info.snapshot(includeImu = false)
                            val day = LocalDate.now().toEpochDay()
                            val recovered = regenSampler.sample(day, SystemClock.elapsedRealtime(),
                                vehicle.dynamics.enginePowerKw)
                            val regen = settings.addDailyRegenKwh(day, recovered)
                            val tablet = tabletSoc.snapshot()
                            val now = System.currentTimeMillis()
                            val previousFuelFill = fuelFillTracker.state
                            fuelFillTracker.sample(vehicle.battery.fuelPct, now)
                            if (fuelFillTracker.state != previousFuelFill) settings.fuelFillState = fuelFillTracker.state
                            val tilt = attitude.value
                            val values = DashboardMetrics.values(vehicle, tablet, regen, tilt?.pitch, tilt?.roll,
                                settings.tripUsageState.distanceKm(vehicle.battery.odoKm), drive.value?.reading)
                            var history = snapshot.value?.history.orEmpty()
                            var error: Exception? = null
                            try {
                                dailyHistory.record(now, values)
                                history = dailyHistory.samples(now)
                            } catch (e: SQLiteException) {
                                error = e
                            } catch (e: JSONException) {
                                error = e
                            }
                            if (error != null) Log.e("DashboardHistory", "Cannot record or load daily history", error)
                            _snapshot.value = Snapshot(now, vehicle, tablet, regen, history, error)
                            delay(2_000L)
                        }
                    }
                    launch {
                        while (isActive) {
                            val reading = info.readDriveLive()
                            val peaks = settings.recordPedalPeaks(LocalDate.now().toEpochDay(),
                                reading.accelPedalPct, reading.brakePedalPct)
                            _drive.value = DriveSample(reading, peaks)
                            delay(50L)
                        }
                    }
                    launch {
                        while (isActive) {
                            _power.value = info.readEnginePowerKw()
                            delay(20L)
                        }
                    }
                    launch {
                        while (isActive) {
                            val reading = info.readEngineRpm()
                            val now = SystemClock.elapsedRealtime()
                            rpmHistory.record(now, reading)
                            _rpm.value = EngineRpmHistory.Sample(now, reading)
                            delay(50L)
                        }
                    }
                }
            } finally {
                sensors?.unregisterListener(listener)
                storage.close()
            }
        }
    }
}
