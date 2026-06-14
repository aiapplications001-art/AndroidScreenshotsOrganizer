package com.askmyscreenshots.skill.debug

import android.util.Log

internal object SkillDebugLog {
    const val TAG = "AskScreenshotsSkill"

    fun d(event: String, message: String) {
        Log.d(TAG, format(event, message))
    }

    fun i(event: String, message: String) {
        Log.i(TAG, format(event, message))
    }

    fun w(event: String, message: String, throwable: Throwable? = null) {
        if (throwable == null) {
            Log.w(TAG, format(event, message))
        } else {
            Log.w(TAG, format(event, message), throwable)
        }
    }

    fun e(event: String, message: String, throwable: Throwable? = null) {
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
