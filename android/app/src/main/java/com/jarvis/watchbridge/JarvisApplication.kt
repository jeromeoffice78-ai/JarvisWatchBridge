package com.jarvis.watchbridge

import android.app.Application
import com.jarvis.watchbridge.auth.AuthStore

class JarvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AuthStore.initialize(this)
    }
}
