package ai.champi.core

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/** Thin wrapper around [android.util.Log] that can be injected throughout the app. */
@Singleton
class Logger @Inject constructor() {

    fun d(tag: String, message: String) {
        Log.d(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
}
