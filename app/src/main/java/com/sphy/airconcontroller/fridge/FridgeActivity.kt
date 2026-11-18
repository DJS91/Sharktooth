package com.sphy.airconcontroller.fridge

import android.os.Bundle
import com.sphy.airconcontroller.HomeActivity
import com.sphy.airconcontroller.R

class FridgeActivity : HomeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fridge_container)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fridgeControlsContainer, FridgeControlsFragment())
                .commitNow()
        }
    }
}
