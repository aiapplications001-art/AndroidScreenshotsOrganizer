package com.askmyscreenshots.app.debug

import android.util.Log
import com.askmyscreenshots.app.BuildConfig

object AppDebugLog {
    const val TAG = "AskScreenshots"

    fun d(event: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, format(event, message))
    }

    fun i(event: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(TAG, format(event, message))
    }

    fun w(event: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (throwable == null) {
            Log.w(TAG, format(event, message))
        } else {
            Log.w(TAG, format(event, message), throwable)
        }
    }

    fun e(event: String, message: String, throwable: Throwable? = null) {
        if (!BuildConfig.DEBUG) return
        if (throwable == null) {
            Log.e(TAG, format(event, message))
        } else {
            Log.e(TAG, format(event, message), throwable)
        }
    }

    private fun format(event: String, message: String): String {
        return "$event | $message"
    }
}
