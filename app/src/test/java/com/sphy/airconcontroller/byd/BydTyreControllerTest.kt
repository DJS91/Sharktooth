package com.sphy.airconcontroller.byd

import org.junit.Assert.*
import org.junit.Test

class BydTyreControllerTest {
    private val zero = BydTyreController.CornerReading(
        BydTyreController.Corner.LEFT_FRONT, 1, 0, 0f, 60, "under", "ok", "ok",
    )

    @Test fun zeroPressureIsOnlyAFlatReadingWhenBothChannelsAndStatusConfirmIt() {
        assertEquals(0f, zero.pressurePsi())
        assertEquals(BydTyreController.StatusTone.UNDER, zero.statusTone())
        for (reading in listOf(
            zero.copy(signalState = "invalid"), zero.copy(pressureState = "ok"),
            zero.copy(pressureKpa = null), zero.copy(pressureByType = null),
            zero.copy(pressureByType = Float.POSITIVE_INFINITY),
        )) {
            assertNull(reading.pressurePsi())
            assertEquals(BydTyreController.StatusTone.FAULT, reading.statusTone())
        }
    }

    @Test fun keepsExistingKpaConversionAndTenthsPsiFallback() {
        assertEquals(68 * 0.1450377377f, zero.copy(pressureKpa = 68, pressureByType = 100f).pressurePsi()!!, 0.0001f)
        assertEquals(9.5f, zero.copy(pressureKpa = null, pressureByType = 95f).pressurePsi()!!, 0.0001f)
    }
}
