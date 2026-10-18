package com.vocalis.recorder

import android.annotation.SuppressLint
import android.app.Application
import com.vocalis.recorder.data.RecordingRepository
import com.vocalis.recorder.data.SettingsRepository

class VocalisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

/**
 * Minimal manual dependency container. Repositories only ever hold the Application context,
 * so keeping them in a process-wide object can't leak an Activity.
 */
@SuppressLint("StaticFieldLeak")
object Graph {
    lateinit var settings: SettingsRepository
        private set
    lateinit var recordings: RecordingRepository
        private set

    fun init(app: Application) {
        settings = SettingsRepository(app)
        recordings = RecordingRepository(app)
    }
}
