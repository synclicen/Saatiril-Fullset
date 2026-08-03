package com.saatiril.full

import android.app.Application
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Application class with global crash protection.
 * Prevents silent crashes from library conflicts (NoSuchMethodError, NoClassDefFoundError).
 */
class SaatirilApp : Application() {

    companion object {
        private const val TAG = "SaatirilApp"
    }

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "=== UNCAUGHT EXCEPTION on thread: ${thread.name} ===")
            Log.e(TAG, "Type: ${throwable.javaClass.simpleName}")
            Log.e(TAG, "Message: ${throwable.message}")
            Log.e(TAG, "Full stacktrace:", throwable)

            if (thread == Looper.getMainLooper().thread &&
                (throwable is NoSuchMethodError ||
                 throwable is NoClassDefFoundError ||
                 throwable is VerifyError)) {
                try {
                    Toast.makeText(
                        this,
                        "Saatiril: Library error — ${throwable.javaClass.simpleName}: ${throwable.message}",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (_: Exception) {}
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
