package com.samius.multicam

import android.app.Application

class MulticamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Config.init(this)
    }
}
