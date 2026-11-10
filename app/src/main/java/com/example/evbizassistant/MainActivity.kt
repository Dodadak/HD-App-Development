package com.example.evbizassistant

import android.app.Activity
import android.os.Bundle
import com.example.evbizassistant.charge.ChargeApp
import com.example.evbizassistant.charge.ChargeHost
import com.example.evbizassistant.charge.ui.ChargeDashboardView

class MainActivity : Activity() {

    private lateinit var charge: ChargeApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        charge = ChargeHost.app(applicationContext)
        setContentView(ChargeDashboardView(this, charge))
        charge.start()
    }

    override fun onDestroy() {
        charge.release()
        super.onDestroy()
    }
}