package com.pdfsecuredrive.app

import android.app.Application
import com.pdfsecuredrive.app.notification.AppNotificationManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppNotificationManager.createChannels(this)
        PDFBoxResourceLoader.init(this)
    }
}
