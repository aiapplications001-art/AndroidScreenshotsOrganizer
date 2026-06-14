package com.askmyscreenshots.app.domain

enum class PrimaryScreen {
    ORGANIZE,
    STARTED,
}

object SetupUiRules {
    fun primaryScreen(hasOrganizationState: Boolean): PrimaryScreen {
        return if (hasOrganizationState) PrimaryScreen.STARTED else PrimaryScreen.ORGANIZE
    }

    fun shouldShowSettingsCta(permissionDeniedPermanently: Boolean): Boolean {
        return permissionDeniedPermanently
    }
}
