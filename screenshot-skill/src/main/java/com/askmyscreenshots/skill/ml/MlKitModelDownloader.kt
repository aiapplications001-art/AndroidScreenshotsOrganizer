package com.askmyscreenshots.skill.ml

import android.content.Context
import com.askmyscreenshots.skill.debug.SkillDebugLog
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.OptionalModuleApi
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_CANCELED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState.STATE_FAILED
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class MlKitModelDownloader(
    private val context: Context,
    private val optionalApisProvider: () -> List<OptionalModuleApi>,
) {
    @Volatile
    private var modulesReady = false

    suspend fun requestInstallIfNeeded(onDownloadRequired: suspend () -> Unit = {}) {
        if (modulesReady) {
            SkillDebugLog.d("mlkit_models", "status=already_ready")
            return
        }

        val optionalApis = optionalApisProvider()
        SkillDebugLog.i("mlkit_models", "optionalApis=${optionalApis.size}")
        if (optionalApis.isEmpty()) {
            modulesReady = true
            SkillDebugLog.i("mlkit_models", "status=no_optional_apis")
            return
        }

        val playServicesStatus = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context)
        SkillDebugLog.i("mlkit_models", "playServicesStatus=$playServicesStatus")
        if (playServicesStatus != ConnectionResult.SUCCESS) {
            throw MlKitModelInstallException(
                "Google Play Services is required to prepare local vision models on this phone.",
            )
        }

        runCatching {
            val moduleInstallClient = ModuleInstall.getClient(context)
            val areAvailable = moduleInstallClient
                .areModulesAvailable(*optionalApis.toTypedArray())
                .await()
                .areModulesAvailable()
            SkillDebugLog.i("mlkit_models", "available=$areAvailable")

            if (!areAvailable) {
                SkillDebugLog.i("mlkit_models", "download=required")
                onDownloadRequired()
                val completed = withTimeoutOrNull(MODEL_INSTALL_TIMEOUT_MILLIS) {
                    requestUrgentInstall(moduleInstallClient, optionalApis)
                }
                if (completed == null) {
                    throw MlKitModelInstallException(
                        "Local vision components are still downloading. Keep internet on and try again.",
                    )
                }
            }

            modulesReady = true
            SkillDebugLog.i("mlkit_models", "status=ready")
        }.getOrElse { error ->
            SkillDebugLog.e("mlkit_models", "status=failed", error)
            throw error.toModelInstallException()
        }
    }

    private suspend fun requestUrgentInstall(
        moduleInstallClient: ModuleInstallClient,
        optionalApis: List<OptionalModuleApi>,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        lateinit var listener: InstallStatusListener

        fun succeed() {
            SkillDebugLog.i("mlkit_models", "download=completed")
            moduleInstallClient.unregisterListener(listener)
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
        }

        fun fail(message: String, cause: Throwable? = null) {
            SkillDebugLog.e("mlkit_models", message, cause)
            moduleInstallClient.unregisterListener(listener)
            if (continuation.isActive) {
                continuation.resumeWithException(MlKitModelInstallException(message, cause))
            }
        }

        listener = InstallStatusListener { update ->
            SkillDebugLog.d("mlkit_models", "installState=${update.installState}")
            when (update.installState) {
                STATE_COMPLETED -> succeed()
                STATE_CANCELED -> fail("Local vision component download was cancelled. Try again when internet is available.")
                STATE_FAILED -> fail("Could not download local vision components. Check internet and try again.")
            }
        }

        val requestBuilder = ModuleInstallRequest.newBuilder()
            .setListener(listener)
        optionalApis.forEach { requestBuilder.addApi(it) }

        moduleInstallClient.installModules(requestBuilder.build())
            .addOnSuccessListener { response ->
                SkillDebugLog.i(
                    event = "mlkit_models",
                    message = "installRequestSuccess alreadyInstalled=${response.areModulesAlreadyInstalled()}",
                )
                if (response.areModulesAlreadyInstalled()) {
                    succeed()
                }
            }
            .addOnFailureListener { error ->
                SkillDebugLog.e("mlkit_models", "installRequestFailure", error)
                fail(
                    message = "Could not prepare local vision components. Check Google Play Services and internet, then try again.",
                    cause = error,
                )
            }

        continuation.invokeOnCancellation {
            moduleInstallClient.unregisterListener(listener)
        }
    }

    companion object {
        private const val MODEL_INSTALL_TIMEOUT_MILLIS = 120_000L
    }
}

internal class MlKitModelInstallException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

private fun Throwable.toModelInstallException(): MlKitModelInstallException {
    return this as? MlKitModelInstallException
        ?: MlKitModelInstallException(
            message = "Could not prepare local vision components. Check Google Play Services and internet, then try again.",
            cause = this,
        )
}
