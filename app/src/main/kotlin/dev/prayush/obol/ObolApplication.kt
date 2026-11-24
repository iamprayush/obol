package dev.prayush.obol

import android.app.Application
import dev.prayush.obol.di.AppContainer

class ObolApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        if (!::container.isInitialized) {
            container = AppContainer(this)
        }
    }
}
