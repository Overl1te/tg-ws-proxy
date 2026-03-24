package com.flowseal.tgwsproxyandroid

import android.app.Application

class TgWsProxyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        UiPreferencesStore.applyThemeMode(UiPreferencesStore.load(this).themeMode)
    }
}
