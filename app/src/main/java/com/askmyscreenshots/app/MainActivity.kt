package com.askmyscreenshots.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.askmyscreenshots.app.debug.AppDebugLog
import com.askmyscreenshots.app.ui.AskScreenshotsApp
import com.askmyscreenshots.app.ui.MainViewModel
import com.askmyscreenshots.app.ui.theme.AskMyScreenshotsTheme
import com.askmyscreenshots.skill.api.ScreenshotAccessStatus

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDebugLog.i("activity_create", "sdk=${Build.VERSION.SDK_INT}")
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                AppDebugLog.i(
                    event = "permission_result",
                    message = "granted=${it.count { entry -> entry.value }}/${it.size} " +
                        "access=${screenshotAccessStatus()} " +
                        "permanentlyDenied=${imagePermissionsPermanentlyDenied()}",
                )
                when (screenshotAccessStatus()) {
                    ScreenshotAccessStatus.FULL -> viewModel.onFullPhotoAccessGranted()
                    ScreenshotAccessStatus.PARTIAL -> viewModel.onPartialPhotoAccess()
                    ScreenshotAccessStatus.PICKER_ONLY -> viewModel.onPickerOnlyAccess()
                    ScreenshotAccessStatus.DENIED -> {
                        viewModel.onPermissionDenied(permanently = imagePermissionsPermanentlyDenied())
                    }
                }
            }
            val photoPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickMultipleVisualMedia(),
            ) { uris ->
                viewModel.organizeFromPickedImages(uris)
            }

            AskMyScreenshotsTheme {
                AskScreenshotsApp(
                    state = state,
                    onPresetSelected = viewModel::onPresetSelected,
                    onCustomStartSelected = viewModel::onCustomStartSelected,
                    onCustomEndSelected = viewModel::onCustomEndSelected,
                    onOrganizeClick = {
                        val accessStatus = screenshotAccessStatus()
                        AppDebugLog.i(
                            event = "organize_cta",
                            message = "access=$accessStatus",
                        )
                        when (accessStatus) {
                            ScreenshotAccessStatus.FULL -> viewModel.organizeFromMediaStore()
                            ScreenshotAccessStatus.PARTIAL -> viewModel.onPartialPhotoAccess()
                            ScreenshotAccessStatus.PICKER_ONLY,
                            ScreenshotAccessStatus.DENIED,
                            -> permissionLauncher.launch(requiredImagePermissions())
                        }
                    },
                    onPickImagesClick = {
                        AppDebugLog.i("photo_picker_cta", "launching=true")
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    onOpenSettingsClick = ::openAppSettings,
                    onSearchQueryChanged = viewModel::onSearchQueryChanged,
                    onSearchClick = viewModel::searchScreenshots,
                    onMindMapClick = viewModel::buildMindMap,
                    onMindMapAskClick = viewModel::askAboutMindMap,
                    onMindMapSearchClick = viewModel::fillMindMapSearch,
                    onCategoriesVisible = viewModel::loadCategories,
                    onCategoryBucketClick = viewModel::selectCategoryBucket,
                    onCategoryBackClick = viewModel::clearSelectedCategoryBucket,
                    onCategoryAskClick = viewModel::askAboutMindMap,
                    onCategorySearchClick = viewModel::fillMindMapSearch,
                    onSuggestedActionClick = viewModel::executeSuggestedAction,
                    onOnlineQueryHelpChanged = viewModel::onOnlineQueryHelpChanged,
                    onDeleteMemoryClick = viewModel::deleteLocalMemory,
                )
            }
        }
    }

    private fun screenshotAccessStatus(): ScreenshotAccessStatus {
        val readImages = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_MEDIA_IMAGES,
        ) == PackageManager.PERMISSION_GRANTED
        val readSelected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        val legacyRead = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && legacyRead -> ScreenshotAccessStatus.FULL
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> ScreenshotAccessStatus.PICKER_ONLY
            readImages -> ScreenshotAccessStatus.FULL
            readSelected -> ScreenshotAccessStatus.PARTIAL
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> ScreenshotAccessStatus.PICKER_ONLY
            else -> ScreenshotAccessStatus.DENIED
        }
    }

    private fun requiredImagePermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
            )

            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun imagePermissionsPermanentlyDenied(): Boolean {
        return requiredImagePermissions().all { permission ->
            !shouldShowRequestPermissionRationale(permission)
        }
    }

    private fun openAppSettings() {
        AppDebugLog.i("settings_cta", "opening_app_settings=true")
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        startActivity(intent)
    }
}
