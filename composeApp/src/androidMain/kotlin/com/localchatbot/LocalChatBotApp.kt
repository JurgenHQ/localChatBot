package com.localchatbot

import android.app.Application

class LocalChatBotApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContextHolder.context = applicationContext
    }
}
