package com.askmyscreenshots.app.ui

import com.askmyscreenshots.app.domain.DateRange
import com.askmyscreenshots.app.domain.DateRangeCalculator
import com.askmyscreenshots.app.domain.DateRangePreset
import com.askmyscreenshots.app.domain.PrimaryScreen
import com.askmyscreenshots.app.domain.SetupUiRules
import com.askmyscreenshots.skill.api.AskProgressStep
import com.askmyscreenshots.skill.api.AskResponse
import com.askmyscreenshots.skill.api.CategoryBucket
import com.askmyscreenshots.skill.api.CategoryBucketDetail
import com.askmyscreenshots.skill.api.CategoryOverview
import com.askmyscreenshots.skill.api.MindMapGraph
import java.time.LocalDate

data class MainUiState(
    val latestRun: RunSummary? = null,
    val selectedPreset: DateRangePreset = DateRangePreset.LAST_7_DAYS,
    val customStart: LocalDate = LocalDate.now(),
    val customEnd: LocalDate = LocalDate.now(),
    val currentRange: DateRange = DateRangeCalculator.calculate(DateRangePreset.LAST_7_DAYS),
    val isWorking: Boolean = false,
    val isAskWorking: Boolean = false,
    val askProgressStep: AskProgressStep? = null,
    val notice: OperationNotice? = null,
    val searchQuery: String = "",
    val searchResponse: AskResponse? = null,
    val mindMap: MindMapGraph? = null,
    val categories: CategoriesUiState = CategoriesUiState(),
    val onlineQueryHelpEnabled: Boolean = false,
    val onlineQueryHelpAvailable: Boolean = false,
) {
    val primaryScreen: PrimaryScreen = SetupUiRules.primaryScreen(
        latestRun?.state == OrganizationRunState.IN_PROGRESS ||
            latestRun?.state == OrganizationRunState.DONE ||
            latestRun?.state == OrganizationRunState.UP_TO_DATE,
    )
    val askNotice: OperationNotice? = notice?.takeIf { it.destination == NoticeDestination.ASK }
    val organizationNotice: OperationNotice? = notice?.takeIf {
        it.destination == NoticeDestination.ORGANIZATION
    }
    val showSettingsCta: Boolean = SetupUiRules.shouldShowSettingsCta(
        organizationNotice?.type == NoticeType.PERMISSION_PERMANENTLY_DENIED ||
            organizationNotice?.type == NoticeType.PARTIAL_PHOTO_ACCESS,
    )
    val showPickerCta: Boolean = organizationNotice?.type == NoticeType.PARTIAL_PHOTO_ACCESS
}

data class CategoriesUiState(
    val overview: CategoryOverview? = null,
    val selectedBucket: CategoryBucket? = null,
    val detail: CategoryBucketDetail? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

enum class OrganizationRunState {
    IN_PROGRESS,
    DONE,
    UP_TO_DATE,
    FAILED,
}

data class RunSummary(
    val id: Long,
    val state: OrganizationRunState,
    val totalCount: Int,
    val processedCount: Int,
    val organizedCount: Int,
    val newlyIndexedCount: Int,
    val skippedCount: Int,
    val failedCount: Int,
    val dateRangeName: String,
    val dateRangeDates: String,
    val startedAtText: String,
    val completedAtText: String?,
)

data class OperationNotice(
    val type: NoticeType,
    val title: String,
    val message: String,
    val destination: NoticeDestination = NoticeDestination.ORGANIZATION,
)

enum class NoticeDestination {
    ASK,
    ORGANIZATION,
}

enum class NoticeType {
    PERMISSION_DENIED,
    PERMISSION_PERMANENTLY_DENIED,
    PARTIAL_PHOTO_ACCESS,
    NO_SCREENSHOTS,
    EMPTY_PICKER,
    SEARCH_EMPTY,
    MODEL_PREPARING,
    MODEL_DOWNLOADING,
    INFO,
    ERROR,
}
