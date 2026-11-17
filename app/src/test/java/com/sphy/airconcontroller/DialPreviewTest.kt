package com.sphy.airconcontroller

import com.sphy.airconcontroller.dikey.DialDisplay
import com.sphy.airconcontroller.dikey.DialDisplayType
import com.sphy.airconcontroller.dikey.DialDisplays
import com.sphy.airconcontroller.dikey.DiKeyProtocol
import org.junit.Assert.assertEquals
import org.junit.Test

class DialPreviewTest {
    @Test fun disconnectedDebugBuildShowsExamples() {
        assertEquals(
            DialDisplays(
                DialDisplay(DialDisplayType.PASSENGER_TEMP.code, 24),
                DialDisplay(DialDisplayType.DRIVER_FAN.code, 3),
            ),
            previewDialDisplays(DialDisplays(), isConnected = false, isDebug = true),
        )
    }

    @Test fun connectedAndReleaseBuildsPreserveActualDisplays() {
        val actual = DialDisplays(DialDisplay(DiKeyProtocol.DISPLAY_TYPE_BLANK, 0), null)
        assertEquals(actual, previewDialDisplays(actual, isConnected = true, isDebug = true))
        assertEquals(actual, previewDialDisplays(actual, isConnected = false, isDebug = false))
    }
}
