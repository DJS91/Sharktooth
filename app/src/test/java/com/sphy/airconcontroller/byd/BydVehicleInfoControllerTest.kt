package com.sphy.airconcontroller.byd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BydVehicleInfoControllerTest {
    @Test fun engineRpmPreservesStoppedAndRunningReadings() {
        for (rpm in listOf(0.0, 850.0, 1650.0, 8000.0)) {
            assertEquals(rpm, BydVehicleInfoController.validatedEngineRpm(rpm))
        }
    }

    @Test fun invalidEngineRpmIsUnavailableNotStoppedOrRunning() {
        for (rpm in listOf(null, -1.0, 8001.0, 8191.0, 8300.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertNull(BydVehicleInfoController.validatedEngineRpm(rpm))
        }
    }
}
