package com.askmyscreenshots.app.ui

import android.net.Uri
import android.webkit.WebView
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.askmyscreenshots.app.domain.DateRangePreset
import com.askmyscreenshots.app.domain.PrimaryScreen
import com.askmyscreenshots.app.domain.toDisplayDate
import com.askmyscreenshots.app.domain.toUtcPickerMillis
import com.askmyscreenshots.skill.api.AskProgressStep
import com.askmyscreenshots.skill.api.AskResponse
import com.askmyscreenshots.skill.api.AskTrace
import com.askmyscreenshots.skill.api.CategoryBucket
import com.askmyscreenshots.skill.api.CategoryBucketDetail
import com.askmyscreenshots.skill.api.CategoryBucketType
import com.askmyscreenshots.skill.api.CategoryOverview
import com.askmyscreenshots.skill.api.CategoryScreenshotPreview
import com.askmyscreenshots.skill.api.EvidenceGroup
import com.askmyscreenshots.skill.api.EvidenceScreenshot
import com.askmyscreenshots.skill.api.MemoryCluster
import com.askmyscreenshots.skill.api.MemoryScreenshotPreview
import com.askmyscreenshots.skill.api.MemorySignal
import com.askmyscreenshots.skill.api.MindMapGraph
import com.askmyscreenshots.skill.api.RefineAction
import com.askmyscreenshots.skill.api.SuggestedAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun AskScreenshotsApp(
    state: MainUiState,
    onPresetSelected: (DateRangePreset) -> Unit,
    onCustomStartSelected: (Long) -> Unit,
    onCustomEndSelected: (Long) -> Unit,
    onOrganizeClick: () -> Unit,
    onPickImagesClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSearchClick: () -> Unit,
    onMindMapClick: () -> Unit,
    onMindMapAskClick: (String) -> Unit,
    onMindMapSearchClick: (String) -> Unit,
    onCategoriesVisible: () -> Unit,
    onCategoryBucketClick: (CategoryBucket) -> Unit,
    onCategoryBackClick: () -> Unit,
    onCategoryAskClick: (String) -> Unit,
    onCategorySearchClick: (String) -> Unit,
    onSuggestedActionClick: (SuggestedAction) -> Unit,
    onOnlineQueryHelpChanged: (Boolean) -> Unit,
    onDeleteMemoryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedTabName by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTab = selectedTabName?.let { name ->
        runCatching { AppTab.valueOf(name) }.getOrNull()
    } ?: initialAppTab(state)

    LaunchedEffect(selectedTab) {
        if (selectedTab == AppTab.CATEGORIES) {
            onCategoriesVisible()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 124.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    AppHeader()
                    when (selectedTab) {
                        AppTab.ASK -> {
                            AskPanel(
                                state = state,
                                notice = state.askNotice,
                                enabled = state.primaryScreen == PrimaryScreen.STARTED,
                                onSearchQueryChanged = onSearchQueryChanged,
                                onSearchClick = onSearchClick,
                                onMindMapClick = onMindMapClick,
                                onMindMapAskClick = onMindMapAskClick,
                                onMindMapSearchClick = onMindMapSearchClick,
                                onSuggestedActionClick = onSuggestedActionClick,
                            )
                            AskPrivacyPanel(
                                state = state,
                                onOnlineQueryHelpChanged = onOnlineQueryHelpChanged,
                            )
                        }

                        AppTab.ORGANIZE -> {
                            OrganizeTabContent(
                                state = state,
                                onPresetSelected = onPresetSelected,
                                onCustomStartSelected = onCustomStartSelected,
                                onCustomEndSelected = onCustomEndSelected,
                                onOrganizeClick = onOrganizeClick,
                                onPickImagesClick = onPickImagesClick,
                                onOpenSettingsClick = onOpenSettingsClick,
                            )
                            MemoryPanel(
                                state = state,
                                onDeleteMemoryClick = onDeleteMemoryClick,
                            )
                        }

                        AppTab.CATEGORIES -> CategoriesTabContent(
                            state = state,
                            enabled = state.primaryScreen == PrimaryScreen.STARTED,
                            onRefresh = onCategoriesVisible,
                            onBucketClick = onCategoryBucketClick,
                            onBackClick = onCategoryBackClick,
                            onAskClick = onCategoryAskClick,
                            onSearchClick = onCategorySearchClick,
                        )
                        AppTab.AGENTS -> ComingSoonScreen(title = "Agents")
                    }
                }
            }
            FloatingBottomNav(
                selectedTab = selectedTab,
                onTabSelected = { selectedTabName = it.name },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 20.dp, end = 20.dp, bottom = 18.dp),
            )
            if (state.isAskWorking) {
                AskLoadingOverlay(
                    onlineQueryHelpEnabled = state.onlineQueryHelpEnabled,
                    currentStep = state.askProgressStep,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private enum class AppTab(
    val label: String,
    val icon: ImageVector,
) {
    ASK("Ask", Icons.Rounded.Search),
    ORGANIZE("Organize", Icons.Rounded.FolderOpen),
    CATEGORIES("Categories", Icons.Rounded.Category),
    AGENTS("Agents", Icons.Rounded.SmartToy),
}

private fun initialAppTab(state: MainUiState): AppTab {
    return if (state.primaryScreen == PrimaryScreen.ORGANIZE) {
        AppTab.ORGANIZE
    } else {
        AppTab.ASK
    }
}

@Composable
private fun OrganizeTabContent(
    state: MainUiState,
    onPresetSelected: (DateRangePreset) -> Unit,
    onCustomStartSelected: (Long) -> Unit,
    onCustomEndSelected: (Long) -> Unit,
    onOrganizeClick: () -> Unit,
    onPickImagesClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
) {
    when (state.primaryScreen) {
        PrimaryScreen.ORGANIZE -> OrganizeScreen(
            state = state,
            notice = state.organizationNotice,
            onPresetSelected = onPresetSelected,
            onCustomStartSelected = onCustomStartSelected,
            onCustomEndSelected = onCustomEndSelected,
            onOrganizeClick = onOrganizeClick,
            onPickImagesClick = onPickImagesClick,
            onOpenSettingsClick = onOpenSettingsClick,
        )

        PrimaryScreen.STARTED -> StartedScreen(
            state = state,
            notice = state.organizationNotice,
            onPresetSelected = onPresetSelected,
            onCustomStartSelected = onCustomStartSelected,
            onCustomEndSelected = onCustomEndSelected,
            onOrganizeClick = onOrganizeClick,
            onPickImagesClick = onPickImagesClick,
            onOpenSettingsClick = onOpenSettingsClick,
        )
    }
}

@Composable
private fun ComingSoonScreen(title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 360.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Coming Soon",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CategoriesTabContent(
    state: MainUiState,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onBucketClick: (CategoryBucket) -> Unit,
    onBackClick: () -> Unit,
    onAskClick: (String) -> Unit,
    onSearchClick: (String) -> Unit,
) {
    val categories = state.categories
    var viewerItem by remember(categories.selectedBucket, categories.detail) {
        mutableStateOf<ScreenshotViewerItem?>(null)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Categories",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                categories.overview?.let { overview ->
                    Text(
                        text = "${overview.totalScreenshotCount} organized ${screenshotNoun(overview.totalScreenshotCount)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(
                onClick = onRefresh,
                enabled = enabled && !categories.isLoading,
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Refresh categories")
            }
        }

        if (!enabled) {
            CategoryEmptyState("Organize screenshots to browse categories.")
            return
        }

        val selectedBucket = categories.selectedBucket
        if (selectedBucket != null) {
            CategoryBucketDetailContent(
                bucket = selectedBucket,
                detail = categories.detail,
                isLoading = categories.isLoading,
                errorMessage = categories.errorMessage,
                onBackClick = onBackClick,
                onAskClick = onAskClick,
                onSearchClick = onSearchClick,
                onOpenScreenshot = { viewerItem = it.toViewerItem() },
            )
        } else {
            CategoryOverviewContent(
                overview = categories.overview,
                isLoading = categories.isLoading,
                errorMessage = categories.errorMessage,
                onBucketClick = onBucketClick,
            )
        }
    }

    viewerItem?.let { item ->
        ScreenshotViewerDialog(
            item = item,
            onDismiss = { viewerItem = null },
        )
    }
}

@Composable
private fun CategoryOverviewContent(
    overview: CategoryOverview?,
    isLoading: Boolean,
    errorMessage: String?,
    onBucketClick: (CategoryBucket) -> Unit,
) {
    if (isLoading && overview == null) {
        CategoryLoadingCard("Loading categories")
        return
    }
    errorMessage?.let {
        CategoryEmptyState(it)
    }
    if (overview == null) {
        CategoryEmptyState("No categories loaded yet.")
        return
    }
    if (overview.totalBucketCount() == 0) {
        CategoryEmptyState("No category signals found yet.")
        return
    }

    CategoryBucketSection(
        title = "Dynamic Categories",
        buckets = overview.dynamicCategories,
        onBucketClick = onBucketClick,
    )
    CategoryBucketSection(
        title = "Apps & Sources",
        buckets = overview.appSources,
        onBucketClick = onBucketClick,
    )
    CategoryBucketSection(
        title = "Visual Labels",
        buckets = overview.visualLabels,
        onBucketClick = onBucketClick,
    )
    CategoryBucketSection(
        title = "Smart Folders",
        buckets = overview.entityTypes,
        onBucketClick = onBucketClick,
    )
}

@Composable
private fun CategoryBucketSection(
    title: String,
    buckets: List<CategoryBucket>,
    onBucketClick: (CategoryBucket) -> Unit,
) {
    if (buckets.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        buckets.forEach { bucket ->
            CategoryBucketCard(
                bucket = bucket,
                onClick = { onBucketClick(bucket) },
            )
        }
    }
}

@Composable
private fun CategoryBucketCard(
    bucket: CategoryBucket,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = bucket.type.icon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = bucket.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${bucket.count} ${screenshotNoun(bucket.count)} · ${bucket.type.displayName()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (bucket.isSensitive) {
                    Text(
                        text = "Private",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            CategoryPreviewStrip(bucket.sampleScreenshots)
        }
    }
}

@Composable
private fun CategoryBucketDetailContent(
    bucket: CategoryBucket,
    detail: CategoryBucketDetail?,
    isLoading: Boolean,
    errorMessage: String?,
    onBackClick: () -> Unit,
    onAskClick: (String) -> Unit,
    onSearchClick: (String) -> Unit,
    onOpenScreenshot: (CategoryScreenshotPreview) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to categories")
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = bucket.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${bucket.count} ${screenshotNoun(bucket.count)} · ${bucket.type.displayName()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onSearchClick(bucket.toCategoryQuery()) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Search")
            }
            Button(
                onClick = { onAskClick(bucket.toCategoryQuery()) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Ask")
            }
        }

        if (isLoading && detail == null) {
            CategoryLoadingCard("Loading screenshots")
            return
        }
        errorMessage?.let {
            CategoryEmptyState(it)
        }

        val screenshots = detail?.screenshots.orEmpty()
        if (screenshots.isEmpty() && !isLoading) {
            CategoryEmptyState("No screenshots in this bucket.")
        } else {
            screenshots.forEach { screenshot ->
                CategoryScreenshotCard(
                    screenshot = screenshot,
                    onClick = { onOpenScreenshot(screenshot) },
                )
            }
            if (screenshots.size >= CATEGORY_DETAIL_DISPLAY_LIMIT) {
                Text(
                    text = "Showing latest $CATEGORY_DETAIL_DISPLAY_LIMIT screenshots.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CategoryPreviewStrip(previews: List<CategoryScreenshotPreview>) {
    if (previews.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        previews.take(4).forEach { preview ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EvidenceThumbnail(
                    uri = preview.uri,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                )
            }
        }
    }
}

@Composable
private fun CategoryScreenshotCard(
    screenshot: CategoryScreenshotPreview,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            EvidenceThumbnail(
                uri = screenshot.uri,
                modifier = Modifier.size(width = 82.dp, height = 104.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = screenshot.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                )
                val metadata = listOfNotNull(
                    screenshot.appHint,
                    screenshot.category.replace('_', ' ').takeIf { it.isNotBlank() },
                    screenshot.takenAtMillis?.toEvidenceDate(),
                ).joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                if (screenshot.snippet.isNotBlank()) {
                    Text(
                        text = screenshot.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryLoadingCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CategoryEmptyState(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FloatingBottomNav(
    selectedTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 8.dp,
        shadowElevation = 14.dp,
    ) {
        Row(
            modifier = Modifier
                .height(68.dp)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppTab.entries.forEach { tab ->
                FloatingBottomNavItem(
                    tab = tab,
                    selected = selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FloatingBottomNavItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    Color.Transparent
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AskLoadingOverlay(
    onlineQueryHelpEnabled: Boolean,
    currentStep: AskProgressStep?,
    modifier: Modifier = Modifier,
) {
    val step = currentStep ?: AskProgressStep.UNDERSTANDING_QUERY
    val steps = askProgressSequence(onlineQueryHelpEnabled, step)
    val stepIndex = steps.indexOf(step).takeIf { it >= 0 } ?: 0
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.28f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = "Working on your answer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = step.toAskProgressLabel(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "${stepIndex + 1}/${steps.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun askProgressSequence(
    onlineQueryHelpEnabled: Boolean,
    currentStep: AskProgressStep,
): List<AskProgressStep> {
    val planningStep = when (currentStep) {
        AskProgressStep.PLANNING_LOCAL_SEARCH -> AskProgressStep.PLANNING_LOCAL_SEARCH
        else -> if (onlineQueryHelpEnabled) AskProgressStep.PLANNING_WITH_GEMINI else AskProgressStep.PLANNING_LOCAL_SEARCH
    }
    val composingStep = when (currentStep) {
        AskProgressStep.COMPOSING_LOCALLY -> AskProgressStep.COMPOSING_LOCALLY
        else -> if (onlineQueryHelpEnabled) AskProgressStep.COMPOSING_WITH_GEMINI else AskProgressStep.COMPOSING_LOCALLY
    }
    return listOf(
        AskProgressStep.UNDERSTANDING_QUERY,
        planningStep,
        AskProgressStep.RETRIEVING_LOCAL_INDEX,
        AskProgressStep.RETRIEVING_SEMANTIC_INDEX,
        AskProgressStep.EXPANDING_LINKED_ENTITIES,
        AskProgressStep.RANKING_SCREENSHOTS,
        AskProgressStep.BUILDING_EVIDENCE,
        AskProgressStep.DEEP_ASK_WITH_GEMINI,
        composingStep,
        AskProgressStep.PREPARING_RESULTS,
    )
}

private fun AskProgressStep.toAskProgressLabel(): String {
    return when (this) {
        AskProgressStep.UNDERSTANDING_QUERY -> "Understanding your question"
        AskProgressStep.PLANNING_WITH_GEMINI -> "Asking Gemini to plan the search"
        AskProgressStep.PLANNING_LOCAL_SEARCH -> "Planning the local search"
        AskProgressStep.RETRIEVING_LOCAL_INDEX -> "Retrieving local screenshot index"
        AskProgressStep.RETRIEVING_SEMANTIC_INDEX -> "Checking semantic matches"
        AskProgressStep.EXPANDING_LINKED_ENTITIES -> "Following linked people and identifiers"
        AskProgressStep.RANKING_SCREENSHOTS -> "Ranking matching screenshots"
        AskProgressStep.BUILDING_EVIDENCE -> "Checking evidence used in the answer"
        AskProgressStep.DEEP_ASK_WITH_GEMINI -> "Giving Gemini richer evidence text"
        AskProgressStep.COMPOSING_WITH_GEMINI -> "Composing the answer with Gemini"
        AskProgressStep.COMPOSING_LOCALLY -> "Composing the answer locally"
        AskProgressStep.PREPARING_RESULTS -> "Preparing screenshots and filters"
    }
}

@Composable
private fun AppHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "AM",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Column {
            Text(
                text = "Ask My Screenshots",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Private screenshot memory",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrganizeScreen(
    state: MainUiState,
    notice: OperationNotice?,
    onPresetSelected: (DateRangePreset) -> Unit,
    onCustomStartSelected: (Long) -> Unit,
    onCustomEndSelected: (Long) -> Unit,
    onOrganizeClick: () -> Unit,
    onPickImagesClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
            text = "Organize screenshots",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DateRangePreset.entries.forEach { preset ->
                FilterChip(
                    selected = state.selectedPreset == preset,
                    onClick = { onPresetSelected(preset) },
                    label = { Text(preset.label) },
                )
            }
        }

        if (state.selectedPreset == DateRangePreset.CUSTOM) {
            CustomDateControls(
                state = state,
                onCustomStartSelected = onCustomStartSelected,
                onCustomEndSelected = onCustomEndSelected,
            )
        }

        AssistChip(
            onClick = {},
            label = { Text(state.currentRange.displayLabel) },
        )

        notice?.let {
            NoticeCard(
                notice = it,
                showSettingsCta = state.showSettingsCta,
                showPickerCta = state.showPickerCta,
                onPickImagesClick = onPickImagesClick,
                onOpenSettingsClick = onOpenSettingsClick,
            )
        }

        Button(
            onClick = onOrganizeClick,
            enabled = !state.isWorking,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(Icons.Rounded.ImageSearch, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.isWorking) "Starting..." else "Organize screenshots")
        }

        OutlinedButton(
            onClick = onPickImagesClick,
            enabled = !state.isWorking,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Select images manually")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomDateControls(
    state: MainUiState,
    onCustomStartSelected: (Long) -> Unit,
    onCustomEndSelected: (Long) -> Unit,
) {
    var pickerTarget by remember { mutableStateOf<PickerTarget?>(null) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = { pickerTarget = PickerTarget.START },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("From ${state.customStart.toDisplayDate()}")
        }
        OutlinedButton(
            onClick = { pickerTarget = PickerTarget.END },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("To ${state.customEnd.toDisplayDate()}")
        }
    }

    val target = pickerTarget
    if (target != null) {
        val initialMillis = when (target) {
            PickerTarget.START -> state.customStart.toUtcPickerMillis()
            PickerTarget.END -> state.customEnd.toUtcPickerMillis()
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { pickerTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selected ->
                            when (target) {
                                PickerTarget.START -> onCustomStartSelected(selected)
                                PickerTarget.END -> onCustomEndSelected(selected)
                            }
                        }
                        pickerTarget = null
                    },
                ) {
                    Text("Done")
                }
            },
            dismissButton = {
                TextButton(onClick = { pickerTarget = null }) {
                    Text("Cancel")
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun NoticeCard(
    notice: OperationNotice,
    showSettingsCta: Boolean,
    showPickerCta: Boolean = false,
    onPickImagesClick: (() -> Unit)? = null,
    onOpenSettingsClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (notice.type) {
                NoticeType.ERROR,
                NoticeType.PERMISSION_DENIED,
                NoticeType.PERMISSION_PERMANENTLY_DENIED,
                -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f)

                NoticeType.NO_SCREENSHOTS,
                NoticeType.EMPTY_PICKER,
                NoticeType.PARTIAL_PHOTO_ACCESS,
                -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f)

                NoticeType.MODEL_PREPARING,
                NoticeType.MODEL_DOWNLOADING,
                NoticeType.INFO,
                NoticeType.SEARCH_EMPTY,
                -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)
            },
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = notice.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = notice.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showSettingsCta) {
                OutlinedButton(
                    onClick = onOpenSettingsClick,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open settings")
                }
            }
            if (showPickerCta && onPickImagesClick != null) {
                Button(
                    onClick = onPickImagesClick,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Select screenshots")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartedScreen(
    state: MainUiState,
    notice: OperationNotice?,
    onPresetSelected: (DateRangePreset) -> Unit,
    onCustomStartSelected: (Long) -> Unit,
    onCustomEndSelected: (Long) -> Unit,
    onOrganizeClick: () -> Unit,
    onPickImagesClick: () -> Unit,
    onOpenSettingsClick: () -> Unit,
) {
    val run = state.latestRun
    val runState = run?.state ?: OrganizationRunState.IN_PROGRESS
    var editingOrganization by remember(run?.id, runState) {
        mutableStateOf(runState == OrganizationRunState.IN_PROGRESS || runState == OrganizationRunState.FAILED)
    }
    val successColor = Color(0xFF0F7A55)
    val successContainer = Color(0xFFDCFCE7)
    val progressColor = MaterialTheme.colorScheme.primary
    val progressContainer = MaterialTheme.colorScheme.primaryContainer
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(
                                when (runState) {
                                    OrganizationRunState.DONE,
                                    OrganizationRunState.UP_TO_DATE -> successContainer

                                    OrganizationRunState.IN_PROGRESS -> progressContainer
                                    OrganizationRunState.FAILED -> MaterialTheme.colorScheme.errorContainer
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (runState == OrganizationRunState.IN_PROGRESS) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(26.dp),
                                color = progressColor,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = if (runState == OrganizationRunState.FAILED) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    successColor
                                },
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = when (runState) {
                                OrganizationRunState.IN_PROGRESS -> "Organization in progress"
                                OrganizationRunState.DONE -> "Organization done"
                                OrganizationRunState.UP_TO_DATE -> "Organization already done"
                                OrganizationRunState.FAILED -> "Organization needs attention"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        run?.let {
                            Text(
                                text = it.statusCountText(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${it.dateRangeDates} · ${it.lastOrganizationEventText()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { editingOrganization = !editingOrganization }) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Edit organization")
                    }
                }

                notice?.let {
                    NoticeCard(
                        notice = it,
                        showSettingsCta = state.showSettingsCta,
                        showPickerCta = state.showPickerCta,
                        onPickImagesClick = onPickImagesClick,
                        onOpenSettingsClick = onOpenSettingsClick,
                    )
                }

                if (!editingOrganization) {
                    Button(
                        onClick = onOrganizeClick,
                        enabled = !state.isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Rounded.ImageSearch, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isWorking) "Updating..." else "Update organization")
                    }
                }

                if (editingOrganization) {
                    Text(
                        text = "Change date range",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        DateRangePreset.entries.forEach { preset ->
                            FilterChip(
                                selected = state.selectedPreset == preset,
                                onClick = { onPresetSelected(preset) },
                                label = { Text(preset.label) },
                            )
                        }
                    }

                    if (state.selectedPreset == DateRangePreset.CUSTOM) {
                        CustomDateControls(
                            state = state,
                            onCustomStartSelected = onCustomStartSelected,
                            onCustomEndSelected = onCustomEndSelected,
                        )
                    }

                    AssistChip(
                        onClick = {},
                        label = { Text(state.currentRange.displayLabel) },
                    )

                    Button(
                        onClick = onOrganizeClick,
                        enabled = !state.isWorking,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Rounded.ImageSearch, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isWorking) "Updating..." else "Update organization")
                    }
                }
            }
        }
    }
}

private fun RunSummary.statusCountText(): String {
    val totalNoun = screenshotNoun(totalCount)
    val failureText = if (failedCount > 0) " · $failedCount failed" else ""
    return when (state) {
        OrganizationRunState.IN_PROGRESS -> {
            if (totalCount > 0) {
                "$processedCount/$totalCount $totalNoun checked in $dateRangeName" +
                    if (skippedCount > 0) " · $skippedCount already indexed" else ""
            } else {
                "Preparing to organize screenshots in $dateRangeName"
            }
        }

        OrganizationRunState.DONE -> {
            val detailText = when {
                newlyIndexedCount > 0 && skippedCount > 0 ->
                    " · $newlyIndexedCount new · $skippedCount already indexed"

                newlyIndexedCount > 0 -> " · $newlyIndexedCount new"
                skippedCount > 0 -> " · $skippedCount already indexed"
                else -> ""
            }
            "$organizedCount/$totalCount $totalNoun organized in $dateRangeName$detailText$failureText"
        }

        OrganizationRunState.UP_TO_DATE ->
            "No new organization required · $organizedCount $totalNoun already organized in $dateRangeName$failureText"

        OrganizationRunState.FAILED ->
            "No completed organization for $dateRangeName"
    }
}

private fun RunSummary.lastOrganizationEventText(): String {
    return when (state) {
        OrganizationRunState.IN_PROGRESS -> "Started $startedAtText"
        OrganizationRunState.DONE -> "Updated ${completedAtText ?: startedAtText}"
        OrganizationRunState.UP_TO_DATE -> "Checked ${completedAtText ?: startedAtText}"
        OrganizationRunState.FAILED -> "Tried ${completedAtText ?: startedAtText}"
    }
}

private fun screenshotNoun(count: Int): String {
    return if (count == 1) "screenshot" else "screenshots"
}

private const val CATEGORY_DETAIL_DISPLAY_LIMIT = 120

private fun CategoryOverview.totalBucketCount(): Int {
    return dynamicCategories.size + appSources.size + visualLabels.size + entityTypes.size
}

private fun CategoryBucketType.displayName(): String {
    return when (this) {
        CategoryBucketType.DYNAMIC_CATEGORY -> "auto category"
        CategoryBucketType.APP_SOURCE -> "app/source"
        CategoryBucketType.VISUAL_LABEL -> "visual label"
        CategoryBucketType.ENTITY_TYPE -> "smart folder"
    }
}

private fun CategoryBucketType.icon(): ImageVector {
    return when (this) {
        CategoryBucketType.DYNAMIC_CATEGORY -> Icons.Rounded.Category
        CategoryBucketType.APP_SOURCE -> Icons.Rounded.FolderOpen
        CategoryBucketType.VISUAL_LABEL -> Icons.Rounded.ImageSearch
        CategoryBucketType.ENTITY_TYPE -> Icons.Rounded.Search
    }
}

private fun CategoryBucket.toCategoryQuery(): String {
    return when (type) {
        CategoryBucketType.DYNAMIC_CATEGORY -> "Show me ${title.lowercase(Locale.getDefault())} screenshots"
        CategoryBucketType.APP_SOURCE -> "Show me screenshots from $title"
        CategoryBucketType.VISUAL_LABEL -> "Show me screenshots with $title"
        CategoryBucketType.ENTITY_TYPE -> "Show me screenshots containing $title"
    }
}

private data class ScreenshotViewerItem(
    val uri: String,
    val title: String,
    val subtitle: String,
)

private fun CategoryScreenshotPreview.toViewerItem(): ScreenshotViewerItem {
    val subtitle = listOfNotNull(
        appHint,
        category.replace('_', ' ').takeIf { it.isNotBlank() },
        takenAtMillis?.toEvidenceDate(),
    ).joinToString(" · ")
    return ScreenshotViewerItem(
        uri = uri,
        title = title,
        subtitle = subtitle,
    )
}

@Composable
private fun AskPanel(
    state: MainUiState,
    notice: OperationNotice?,
    enabled: Boolean,
    onSearchQueryChanged: (String) -> Unit,
    onSearchClick: () -> Unit,
    onMindMapClick: () -> Unit,
    onMindMapAskClick: (String) -> Unit,
    onMindMapSearchClick: (String) -> Unit,
    onSuggestedActionClick: (SuggestedAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Ask",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchQueryChanged,
                    enabled = enabled && !state.isWorking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                    placeholder = {
                        Text(if (enabled) "Ask about PAN, Aadhaar, LinkedIn screenshots, reports, news, and more..." else "Available after organization")
                    },
                    shape = RoundedCornerShape(10.dp),
                    minLines = 3,
                    maxLines = 5,
                )
                Button(
                    onClick = onSearchClick,
                    enabled = enabled && !state.isWorking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Ask")
                }
            }
        }
        notice?.let {
            NoticeCard(
                notice = it,
                showSettingsCta = false,
                onOpenSettingsClick = {},
            )
        }
        state.searchResponse?.let { response ->
            SearchResultsSection(
                response = response,
                onRefineClick = onMindMapAskClick,
                onSuggestedActionClick = onSuggestedActionClick,
            )
        }
        state.mindMap?.let { graph ->
            MindMapSection(
                graph = graph,
                onAskClick = onMindMapAskClick,
                onSearchClick = onMindMapSearchClick,
            )
        }
        Spacer(Modifier.height(2.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchResultsSection(
    response: AskResponse,
    onRefineClick: (String) -> Unit,
    onSuggestedActionClick: (SuggestedAction) -> Unit,
) {
    val answerCard = response.answerCard
    val usedEvidence = response.usedEvidence.ifEmpty { response.flatEvidence.filter { it.isCited } }
    val usedEvidenceIds = usedEvidence.map { it.id }.toSet()
    val relatedEvidence = response.flatEvidence.filterNot { it.id in usedEvidenceIds }
    var expandedGroupId by remember(response.trace, response.evidenceGroups) {
        mutableStateOf<String?>(null)
    }
    var refineExpanded by remember(response.trace, response.refineActions) {
        mutableStateOf(false)
    }
    var relatedExpanded by remember(response.trace, response.flatEvidence) {
        mutableStateOf(false)
    }
    var viewerItem by remember(response.trace, response.usedEvidence, response.flatEvidence, response.evidenceGroups) {
        mutableStateOf<ScreenshotViewerItem?>(null)
    }
    var selectedResultTab by remember(response.trace, response.usedEvidence, response.flatEvidence, response.evidenceGroups) {
        mutableStateOf(0)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = answerCard.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = answerCard.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Confidence ${(answerCard.confidence * 100).toInt()}% · ${answerCard.answerType.replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (response.suggestedActions.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        response.suggestedActions.forEach { action ->
                            AssistChip(
                                onClick = { onSuggestedActionClick(action) },
                                label = { Text(action.label) },
                            )
                        }
                    }
                }
            }
        }

        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            ),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Why this answer",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = listOf(
                        response.trace.taskType.replace('_', ' '),
                        if (response.trace.grouping.by != "none") "grouped by ${response.trace.grouping.by.replace('_', ' ')}" else null,
                        if (response.trace.evidenceChannels.isNotEmpty()) {
                            "channels ${response.trace.evidenceChannels.take(4).joinToString("/") { it.channel.replace('_', ' ') }}"
                        } else {
                            null
                        },
                        "${response.trace.candidateCount} candidates",
                        "${usedEvidence.size} used ${screenshotNoun(usedEvidence.size)}",
                        if (response.trace.groupCount > 0) "${response.trace.groupCount} evidence groups" else null,
                        if (response.trace.semanticCandidateCount > 0) "${response.trace.semanticCandidateCount} semantic" else null,
                        if (response.trace.linkedCandidateCount > 0) "${response.trace.linkedCandidateCount} linked" else null,
                        if (response.trace.subQueryCount > 0) "${response.trace.subQueryCount} subqueries" else null,
                        if (response.trace.deepAskUsed) "Deep Ask" else null,
                        if (response.trace.remoteAnswerUsed) "Gemini phrased answer" else "local answer",
                    ).filterNotNull().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (response.refineActions.isNotEmpty()) {
            OutlinedButton(
                onClick = { refineExpanded = !refineExpanded },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(if (refineExpanded) "Hide refine search" else "Refine search")
            }
            if (refineExpanded) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    response.refineActions.take(8).forEach { action ->
                        RefineChip(
                            action = action,
                            onClick = { onRefineClick(action.query) },
                        )
                    }
                }
            }
        }

        if (usedEvidence.isEmpty() && relatedEvidence.isEmpty() && response.evidenceGroups.isEmpty()) {
            Text(
                text = "No evidence screenshots matched this ask.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val screenshotCount = usedEvidence.size + relatedEvidence.size
            val groupCount = response.evidenceGroups.size
            TabRow(selectedTabIndex = selectedResultTab) {
                Tab(
                    selected = selectedResultTab == 0,
                    onClick = { selectedResultTab = 0 },
                    text = { Text("Matching screenshots ($screenshotCount)") },
                )
                Tab(
                    selected = selectedResultTab == 1,
                    onClick = { selectedResultTab = 1 },
                    enabled = groupCount > 0,
                    text = { Text("Groups ($groupCount)") },
                )
            }
            when (selectedResultTab) {
                0 -> MatchingScreenshotsTab(
                    usedEvidence = usedEvidence,
                    relatedEvidence = relatedEvidence,
                    relatedExpanded = relatedExpanded,
                    onRelatedExpandedChange = { relatedExpanded = it },
                    onOpenScreenshot = { viewerItem = it.toViewerItem() },
                )

                else -> GroupsTab(
                    trace = response.trace,
                    groups = response.evidenceGroups,
                    expandedGroupId = expandedGroupId,
                    onExpandedGroupChange = { expandedGroupId = it },
                    onOpenScreenshot = { viewerItem = it.toViewerItem() },
                    onAskClick = onRefineClick,
                    onFilterClick = onRefineClick,
                )
            }
        }

        Text(
            text = if (response.privacyTrace.dataSentOffDevice.isNotEmpty()) {
                "Gemini helped plan and phrase this answer. Screenshot images stayed on this phone."
            } else {
                "Answered from local indexed screenshots only."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    viewerItem?.let { item ->
        ScreenshotViewerDialog(
            item = item,
            onDismiss = { viewerItem = null },
        )
    }
}

private fun evidenceGroupSectionTitle(trace: AskTrace): String {
    return when (trace.grouping.by) {
        "entity" -> "People and identifier evidence"
        "app" -> "App/source evidence"
        "date_bucket" -> "Timeline evidence"
        "theme" -> "Theme evidence"
        "sensitive_type" -> "Sensitive screenshot evidence"
        "comparison_option" -> "Comparison evidence"
        "analytics_category" -> "Pattern evidence"
        "issue" -> "Action evidence"
        else -> when (trace.taskType) {
            "lookup_value" -> "Best value evidence"
            "prove" -> "Proof evidence"
            "summarize" -> "Theme evidence"
            "compare" -> "Comparison evidence"
            "aggregate" -> "Pattern evidence"
            "cleanup" -> "Sensitive screenshot evidence"
            "timeline" -> "Timeline evidence"
            "action_items" -> "Action evidence"
            else -> "Evidence"
        }
    }
}

@Composable
private fun MatchingScreenshotsTab(
    usedEvidence: List<EvidenceScreenshot>,
    relatedEvidence: List<EvidenceScreenshot>,
    relatedExpanded: Boolean,
    onRelatedExpandedChange: (Boolean) -> Unit,
    onOpenScreenshot: (EvidenceScreenshot) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (usedEvidence.isNotEmpty()) {
            Text(
                text = "Used screenshots",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            usedEvidence.take(8).forEach { ref ->
                EvidenceScreenshotCard(
                    ref = ref.copy(isCited = true),
                    onOpenScreenshot = onOpenScreenshot,
                )
            }
            if (usedEvidence.size > 8) {
                Text(
                    text = "Showing 8 of ${usedEvidence.size} used ${screenshotNoun(usedEvidence.size)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (relatedEvidence.isNotEmpty()) {
            Text(
                text = "Best matching screenshots",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            relatedEvidence.take(5).forEach { ref ->
                EvidenceScreenshotCard(
                    ref = ref,
                    onOpenScreenshot = onOpenScreenshot,
                )
            }
        }
        if (relatedEvidence.isNotEmpty() && usedEvidence.isNotEmpty()) {
            OutlinedButton(
                onClick = { onRelatedExpandedChange(!relatedExpanded) },
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    if (relatedExpanded) {
                        "Hide related screenshots"
                    } else {
                        "Show more related screenshots (${relatedEvidence.size})"
                    },
                )
            }
            if (relatedExpanded) {
                Text(
                    text = "More related screenshots",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                relatedEvidence.take(8).forEach { ref ->
                    EvidenceScreenshotCard(
                        ref = ref,
                        onOpenScreenshot = onOpenScreenshot,
                    )
                }
                if (relatedEvidence.size > 8) {
                    Text(
                        text = "Showing 8 of ${relatedEvidence.size} related matches.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupsTab(
    trace: AskTrace,
    groups: List<EvidenceGroup>,
    expandedGroupId: String?,
    onExpandedGroupChange: (String?) -> Unit,
    onOpenScreenshot: (EvidenceScreenshot) -> Unit,
    onAskClick: (String) -> Unit,
    onFilterClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (groups.isEmpty()) {
            Text(
                text = "No groups for this answer.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        Text(
            text = evidenceGroupSectionTitle(trace),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        groups.take(10).forEach { group ->
            EvidenceGroupCard(
                group = group,
                expanded = expandedGroupId == group.id,
                onOpenScreenshot = onOpenScreenshot,
                onToggleExpanded = {
                    onExpandedGroupChange(if (expandedGroupId == group.id) null else group.id)
                },
                onAskClick = onAskClick,
                onFilterClick = onFilterClick,
            )
        }
    }
}

private fun EvidenceScreenshot.toViewerItem(): ScreenshotViewerItem {
    val subtitle = listOfNotNull(
        appHint,
        category.replace('_', ' '),
        takenAtMillis?.toEvidenceDate(),
    ).joinToString(" · ")
    return ScreenshotViewerItem(
        uri = uri,
        title = title,
        subtitle = subtitle,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EvidenceGroupCard(
    group: EvidenceGroup,
    expanded: Boolean,
    onOpenScreenshot: (EvidenceScreenshot) -> Unit,
    onToggleExpanded: () -> Unit,
    onAskClick: (String) -> Unit,
    onFilterClick: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (group.citedScreenshotIds.isNotEmpty()) {
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.42f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = group.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${group.screenshotCount} matching ${screenshotNoun(group.screenshotCount)} · ${group.type.replace('_', ' ')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (group.citedScreenshotIds.isNotEmpty()) {
                    Text(
                        text = "${group.citedScreenshotIds.size} used",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Text(
                text = group.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (group.topSignals.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    group.topSignals.take(7).forEach { signal ->
                        AssistChip(
                            onClick = { onFilterClick(signal.value) },
                            label = { Text("${signal.label} ${signal.count}") },
                        )
                    }
                }
            }

            val previews = if (expanded) {
                group.representativeScreenshots
            } else {
                group.representativeScreenshots.take(3)
            }
            if (previews.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    previews.forEach { screenshot ->
                        EvidencePreviewTile(
                            screenshot = screenshot,
                            onOpenScreenshot = onOpenScreenshot,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(if (expanded) "Show less" else "View all")
                }
                Button(
                    onClick = { onAskClick("tell me more about ${group.title}") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Ask about this")
                }
            }
            TextButton(onClick = { onFilterClick(group.title) }) {
                Text("Filter to this")
            }
        }
    }
}

@Composable
private fun EvidencePreviewTile(
    screenshot: EvidenceScreenshot,
    onOpenScreenshot: (EvidenceScreenshot) -> Unit,
) {
    Column(
        modifier = Modifier
            .widthIn(min = 118.dp, max = 136.dp)
            .clickable { onOpenScreenshot(screenshot) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        EvidenceThumbnail(
            uri = screenshot.uri,
            modifier = Modifier.size(width = 118.dp, height = 154.dp),
        )
        Text(
            text = screenshot.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (screenshot.isCited) {
            Text(
                text = "Used in answer",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RefineChip(
    action: RefineAction,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = { Text(action.label) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EvidenceScreenshotCard(
    ref: EvidenceScreenshot,
    onOpenScreenshot: (EvidenceScreenshot) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenScreenshot(ref) },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (ref.isCited) {
                MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EvidenceThumbnail(
                uri = ref.uri,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = ref.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    if (ref.isCited) {
                        Text(
                            text = "Used",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                val metadata = listOfNotNull(
                    ref.appHint,
                    ref.category.replace('_', ' '),
                    ref.takenAtMillis?.toEvidenceDate(),
                ).joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (ref.matchReason.isNotBlank()) {
                    Text(
                        text = ref.matchReason,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun EvidenceThumbnail(
    uri: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                }
            },
            update = { view ->
                runCatching { view.setImageURI(Uri.parse(uri)) }
            },
        )
    }
}

@Composable
private fun ScreenshotViewerDialog(
    item: ScreenshotViewerItem,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { context ->
                        WebView(context).apply {
                            setBackgroundColor(Color.Black.toArgb())
                            settings.allowContentAccess = true
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false
                        }
                    },
                    update = { webView ->
                        webView.loadDataWithBaseURL(
                            item.uri,
                            """
                            <html>
                              <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=8.0, user-scalable=yes">
                                <style>
                                  html, body { margin:0; padding:0; background:#000; width:100%; height:100%; }
                                  body { display:flex; align-items:center; justify-content:center; overflow:hidden; }
                                  img { max-width:100vw; max-height:100vh; width:auto; height:auto; object-fit:contain; }
                                </style>
                              </head>
                              <body><img src="${item.uri}"></body>
                            </html>
                            """.trimIndent(),
                            "text/html",
                            "UTF-8",
                            null,
                        )
                    },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.92f),
                            maxLines = 1,
                        )
                        if (item.subtitle.isNotBlank()) {
                            Text(
                                text = item.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.62f),
                                maxLines = 1,
                            )
                        }
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Close", color = Color.White)
                    }
                }
            }
        }
    }
}

private fun Long.toEvidenceDate(): String {
    val formatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    return Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(formatter)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MindMapSection(
    graph: MindMapGraph,
    onAskClick: (String) -> Unit,
    onSearchClick: (String) -> Unit,
) {
    var selectedClusterId by remember(graph.generatedAtMillis) {
        mutableStateOf(graph.clusters.firstOrNull()?.id)
    }
    var selectedSignalId by remember(graph.generatedAtMillis) {
        mutableStateOf<String?>(null)
    }
    val selectedCluster = graph.clusters.firstOrNull { it.id == selectedClusterId }
    val selectedSignal = graph.topSignals.firstOrNull { it.id == selectedSignalId }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Memory map",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${graph.summary.indexedScreenshotCount} screenshots · ${graph.clusters.size} clusters · ${graph.topSignals.size} signals",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (graph.clusters.isEmpty()) {
            Text(
                text = "No indexed screenshots in this range.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }

        Text(
            text = "Top memory clusters",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        graph.clusters.take(8).forEach { cluster ->
            MemoryClusterCard(
                cluster = cluster,
                selected = selectedClusterId == cluster.id,
                onSelect = {
                    selectedClusterId = cluster.id
                    selectedSignalId = null
                },
                onAskClick = onAskClick,
                onSearchClick = onSearchClick,
            )
        }

        if (graph.topSignals.isNotEmpty()) {
            Text(
                text = "Top signals",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                graph.topSignals.take(24).forEach { signal ->
                    AssistChip(
                        onClick = {
                            selectedSignalId = signal.id
                            selectedClusterId = null
                        },
                        label = { Text("${signal.label} ${signal.screenshotCount}") },
                    )
                }
            }
        }

        selectedCluster?.let { cluster ->
            MemoryClusterDetail(
                cluster = cluster,
                onAskClick = onAskClick,
                onSearchClick = onSearchClick,
            )
        }

        selectedSignal?.let { signal ->
            MemorySignalDetail(
                signal = signal,
                onAskClick = onAskClick,
                onSearchClick = onSearchClick,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryClusterCard(
    cluster: MemoryCluster,
    selected: Boolean,
    onSelect: () -> Unit,
    onAskClick: (String) -> Unit,
    onSearchClick: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.48f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = cluster.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${cluster.screenshotCount} screenshots",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = cluster.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                cluster.topSignals.take(5).forEach { signal ->
                    AssistChip(
                        onClick = onSelect,
                        label = { Text(signal.label) },
                    )
                }
            }
            ScreenshotPreviewRow(cluster.representativeScreenshots.take(5))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSelect,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("View screenshots")
                }
                Button(
                    onClick = { onAskClick(cluster.askQuery) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Ask about this")
                }
            }
            TextButton(onClick = { onSearchClick(cluster.askQuery) }) {
                Text("Search within this cluster")
            }
        }
    }
}

@Composable
private fun MemoryClusterDetail(
    cluster: MemoryCluster,
    onAskClick: (String) -> Unit,
    onSearchClick: (String) -> Unit,
) {
    DetailSurface(
        title = cluster.title,
        subtitle = "${cluster.screenshotCount} screenshots",
        query = cluster.askQuery,
        previews = cluster.representativeScreenshots,
        onAskClick = onAskClick,
        onSearchClick = onSearchClick,
    )
}

@Composable
private fun MemorySignalDetail(
    signal: MemorySignal,
    onAskClick: (String) -> Unit,
    onSearchClick: (String) -> Unit,
) {
    val query = "Show me screenshots with ${signal.label}"
    DetailSurface(
        title = signal.label,
        subtitle = "${signal.type.replace('_', ' ')} · ${signal.screenshotCount} screenshots",
        query = query,
        previews = signal.representativeScreenshots,
        onAskClick = onAskClick,
        onSearchClick = onSearchClick,
    )
}

@Composable
private fun DetailSurface(
    title: String,
    subtitle: String,
    query: String,
    previews: List<MemoryScreenshotPreview>,
    onAskClick: (String) -> Unit,
    onSearchClick: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            previews.take(18).forEach { preview ->
                ScreenshotPreviewListItem(preview)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onSearchClick(query) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Search")
                }
                Button(
                    onClick = { onAskClick(query) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Ask")
                }
            }
        }
    }
}

@Composable
private fun ScreenshotPreviewRow(previews: List<MemoryScreenshotPreview>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        previews.forEach { preview ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ScreenshotThumbnail(
                    preview = preview,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp),
                )
                Text(
                    text = preview.title,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScreenshotPreviewListItem(preview: MemoryScreenshotPreview) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ScreenshotThumbnail(
            preview = preview,
            modifier = Modifier.size(width = 70.dp, height = 86.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = preview.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (preview.snippet.isNotBlank()) {
                Text(
                    text = preview.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun ScreenshotThumbnail(
    preview: MemoryScreenshotPreview,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            update = { view ->
                runCatching { view.setImageURI(Uri.parse(preview.uri)) }
            },
        )
    }
}

@Composable
private fun AskPrivacyPanel(
    state: MainUiState,
    onOnlineQueryHelpChanged: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Ask privacy",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "Online query help",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (state.onlineQueryHelpAvailable) {
                        "Always on. Sends the raw query plus a planner schema to Gemini; screenshots stay local."
                    } else {
                        "Not configured in this APK."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.onlineQueryHelpEnabled,
                onCheckedChange = onOnlineQueryHelpChanged,
                enabled = false,
            )
        }
    }
}

@Composable
private fun MemoryPanel(
    state: MainUiState,
    onDeleteMemoryClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Memory",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedButton(
            onClick = onDeleteMemoryClick,
            enabled = state.latestRun != null && !state.isWorking,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Delete local memory")
        }
    }
}

private enum class PickerTarget {
    START,
    END,
}
