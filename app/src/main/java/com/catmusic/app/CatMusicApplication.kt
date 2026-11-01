package com.catmusic.app

import android.app.Application
import com.catmusic.app.data.MusicRepository

class CatMusicApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MusicRepository.initialize(this)
    }
}
