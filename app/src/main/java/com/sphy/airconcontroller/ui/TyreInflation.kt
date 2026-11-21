package com.sphy.airconcontroller.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.sphy.airconcontroller.R

internal enum class TyreInflation(@param:DrawableRes val image: Int, @param:StringRes val description: Int) {
    FLAT(R.drawable.tyre_flat, R.string.tyre_inflation_flat),
    TEN(R.drawable.tyre_10_psi, R.string.tyre_inflation_ten),
    TWENTY(R.drawable.tyre_20_psi, R.string.tyre_inflation_twenty),
    THIRTY(R.drawable.tyre_30_psi, R.string.tyre_inflation_thirty),
    FULL(R.drawable.tyre_40_psi, R.string.tyre_inflation_full);

    companion object {
        fun fromPsi(psi: Float?): TyreInflation? = when {
            psi == null || !psi.isFinite() || psi < 0f -> null
            psi < 10f -> FLAT
            psi < 20f -> TEN
            psi < 30f -> TWENTY
            psi < 40f -> THIRTY
            else -> FULL
        }
    }
}
