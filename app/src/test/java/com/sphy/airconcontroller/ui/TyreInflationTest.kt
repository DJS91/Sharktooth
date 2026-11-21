package com.sphy.airconcontroller.ui

import org.junit.Assert.*
import org.junit.Test

class TyreInflationTest {
    @Test fun switchesAtEachTenPsiBoundaryAndClampsToFullAboveForty() {
        val cases = listOf(
            0f to TyreInflation.FLAT, 9.999f to TyreInflation.FLAT,
            10f to TyreInflation.TEN, 19.999f to TyreInflation.TEN,
            20f to TyreInflation.TWENTY, 29.999f to TyreInflation.TWENTY,
            30f to TyreInflation.THIRTY, 39.999f to TyreInflation.THIRTY,
            40f to TyreInflation.FULL, 65f to TyreInflation.FULL,
        )
        cases.forEach { (psi, expected) -> assertEquals("$psi PSI", expected, TyreInflation.fromPsi(psi)) }
    }

    @Test fun unknownAndInvalidPressuresDoNotLookFlatOrFull() {
        for (psi in listOf(null, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(TyreInflation.fromPsi(psi))
        }
    }
}
