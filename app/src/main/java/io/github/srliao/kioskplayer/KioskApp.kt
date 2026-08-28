package io.github.srliao.kioskplayer

import android.app.Application

class KioskApp : Application() {

    override fun onCreate() {
        super.onCreate()
        kiosk = Kiosk(applicationContext)
    }

    companion object {
        lateinit var kiosk: Kiosk
            private set
    }
}
