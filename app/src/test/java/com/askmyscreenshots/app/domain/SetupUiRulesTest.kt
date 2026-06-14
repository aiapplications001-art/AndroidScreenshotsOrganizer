package com.askmyscreenshots.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupUiRulesTest {
    @Test
    fun organizeScreenShowsUntilAnOrganizationStateExists() {
        assertEquals(PrimaryScreen.ORGANIZE, SetupUiRules.primaryScreen(hasOrganizationState = false))
    }

    @Test
    fun startedScreenShowsForAnActiveOrganizationState() {
        assertEquals(PrimaryScreen.STARTED, SetupUiRules.primaryScreen(hasOrganizationState = true))
    }

    @Test
    fun settingsCtaOnlyShowsForPermanentPermissionDenial() {
        assertFalse(SetupUiRules.shouldShowSettingsCta(permissionDeniedPermanently = false))
        assertTrue(SetupUiRules.shouldShowSettingsCta(permissionDeniedPermanently = true))
    }
}
