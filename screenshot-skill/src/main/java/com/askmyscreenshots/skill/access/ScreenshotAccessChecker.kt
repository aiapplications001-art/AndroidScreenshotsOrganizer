package com.askmyscreenshots.skill.access

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.askmyscreenshots.skill.api.ScreenshotAccessState
import com.askmyscreenshots.skill.api.ScreenshotAccessStatus

class ScreenshotAccessChecker(
    private val context: Context,
) {
    fun check(): ScreenshotAccessState {
        val readImages = has(Manifest.permission.READ_MEDIA_IMAGES)
        val readVisualSelected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            has(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else {
            false
        }
        val legacyRead = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            has(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            false
        }

        val status = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && legacyRead -> ScreenshotAccessStatus.FULL
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> ScreenshotAccessStatus.PICKER_ONLY
            readImages -> ScreenshotAccessStatus.FULL
            readVisualSelected -> ScreenshotAccessStatus.PARTIAL
            canUsePhotoPicker() -> ScreenshotAccessStatus.PICKER_ONLY
            else -> ScreenshotAccessStatus.DENIED
        }

        return ScreenshotAccessState(
            status = status,
            canReadMediaStore = status == ScreenshotAccessStatus.FULL ||
                status == ScreenshotAccessStatus.PARTIAL,
            canUsePhotoPicker = canUsePhotoPicker(),
            missingPermissions = requiredPermissions().filterNot(::has),
        )
    }

    private fun canUsePhotoPicker(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    }

    private fun requiredPermissions(): List<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
            )

            else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun has(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
