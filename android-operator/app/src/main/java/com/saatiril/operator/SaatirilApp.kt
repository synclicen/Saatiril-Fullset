package com.saatiril.operator

import android.app.Application
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Application class with global crash protection.
 *
 * CRITICAL: The uncaught exception handler prevents silent crashes.
 * Many Android crashes (NoSuchMethodError, NoClassDefFoundError, etc.)
 * can be caught here and turned into user-visible error messages
 * instead of killing the app process.
 */
class SaatirilApp : Application() {

    companion object {
        private const val TAG = "SaatirilApp"
    }

    override fun onCreate() {
        super.onCreate()

        // Set up global uncaught exception handler to prevent silent crashes
        // and log all errors for debugging. This catches library conflicts
        // (NoSuchMethodError, NoClassDefFoundError) that would otherwise crash.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "=== UNCAUGHT EXCEPTION on thread: ${thread.name} ===")
            Log.e(TAG, "Type: ${throwable.javaClass.simpleName}")
            Log.e(TAG, "Message: ${throwable.message}")
            Log.e(TAG, "Full stacktrace:", throwable)

            // For library conflicts that happen on the main thread,
            // show a Toast instead of crashing the entire app
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

            // Pass to default handler (which shows the crash dialog)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
