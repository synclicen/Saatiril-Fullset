package com.saatiril.fullsystem

import android.app.Application
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Application class with global crash protection.
 * Same pattern as the Operator APK — catches library conflicts
 * (NoSuchMethodError, NoClassDefFoundError) and shows a Toast
 * instead of silently crashing.
 */
class SaatirilFullApp : Application() {

    companion object {
        private const val TAG = "SaatirilFullApp"
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
                } catch (_: Exception) {
                    // Can't show toast either — just log
                }
            }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
