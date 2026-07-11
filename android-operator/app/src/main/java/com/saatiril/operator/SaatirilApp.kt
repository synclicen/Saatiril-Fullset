package com.saatiril.operator

import android.app.Application
import android.util.Log

class SaatirilApp : Application() {

    companion object {
        private const val TAG = "SaatirilApp"
    }

    override fun onCreate() {
        super.onCreate()

        // Set up global uncaught exception handler to prevent silent crashes
        // and log all errors for debugging
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            // Pass to default handler (which shows the crash dialog)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
