package com.askmyscreenshots.skill.categories

import com.askmyscreenshots.skill.api.CategoryBucket
import com.askmyscreenshots.skill.api.CategoryBucketDetail
import com.askmyscreenshots.skill.api.CategoryBucketDetailRequest
import com.askmyscreenshots.skill.api.CategoryBucketType
import com.askmyscreenshots.skill.api.CategoryOverview
import com.askmyscreenshots.skill.api.CategoryOverviewRequest
import com.askmyscreenshots.skill.api.CategoryScreenshotPreview
import com.askmyscreenshots.skill.data.CategoryCount
import com.askmyscreenshots.skill.data.EntityTypeBucketCount
import com.askmyscreenshots.skill.data.ScreenshotEntity
import com.askmyscreenshots.skill.data.ScreenshotSkillDao
import com.askmyscreenshots.skill.ml.ScreenshotCategory
import java.security.MessageDigest
import java.util.Locale

class CategoryBrowser(
    private val dao: ScreenshotSkillDao,
) {
    suspend fun overview(request: CategoryOverviewRequest = CategoryOverviewRequest()): CategoryOverview {
        val range = request.dateRange
        val sectionLimit = request.maxBucketsPerSection.coerceIn(1, 48)
        val sampleSize = request.sampleSize.coerceIn(0, 6)
        val totalCount = dao.indexedScreenshotCount(
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
        )

        return CategoryOverview(
            dynamicCategories = dynamicCategoryBuckets(request, sectionLimit, sampleSize),
            appSources = appSourceBuckets(request, sectionLimit, sampleSize),
            visualLabels = visualLabelBuckets(request, sectionLimit, sampleSize),
            entityTypes = entityTypeBuckets(request, sectionLimit, sampleSize),
            totalScreenshotCount = totalCount,
            generatedAtMillis = System.currentTimeMillis(),
        )
    }

    suspend fun detail(request: CategoryBucketDetailRequest): CategoryBucketDetail {
        val limit = request.limit.coerceIn(1, 240)
        val screenshots = screenshotsForBucket(
            bucket = request.bucket,
            dateRange = request.dateRange,
            limit = limit,
        ).map { it.toCategoryPreview() }
        return CategoryBucketDetail(
            bucket = request.bucket.copy(
                sampleScreenshots = screenshots.take(DEFAULT_SAMPLE_SIZE),
            ),
            screenshots = screenshots,
        )
    }

    private suspend fun dynamicCategoryBuckets(
        request: CategoryOverviewRequest,
        sectionLimit: Int,
        sampleSize: Int,
    ): List<CategoryBucket> {
        val range = request.dateRange
        val rawCounts = dao.categoryCounts(
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
        )
        val hasKnownCategory = rawCounts.any { it.category != ScreenshotCategory.UNKNOWN.value }
        return rawCounts
            .filter { it.count > 0 }
            .filter { !hasKnownCategory || it.category != ScreenshotCategory.UNKNOWN.value }
            .take(sectionLimit)
            .map { row ->
                val category = row.category.normalizeToken()
                CategoryBucket(
                    id = stableId("bucket", "${CategoryBucketType.DYNAMIC_CATEGORY}:$category"),
                    title = category.toDisplayLabel(),
                    type = CategoryBucketType.DYNAMIC_CATEGORY,
                    queryValue = category,
                    count = row.count,
                    sampleScreenshots = sampleScreenshots(
                        bucketType = CategoryBucketType.DYNAMIC_CATEGORY,
                        queryValue = category,
                        dateRange = range,
                        limit = sampleSize,
                    ),
                    isSensitive = false,
                    description = "Auto category",
                )
            }
    }

    private suspend fun appSourceBuckets(
        request: CategoryOverviewRequest,
        sectionLimit: Int,
        sampleSize: Int,
    ): List<CategoryBucket> {
        val range = request.dateRange
        return dao.appSourceCounts(
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
            limit = sectionLimit * FETCH_MULTIPLIER,
        )
            .filter { it.count > 0 }
            .filter { it.source.normalizeToken().length >= 2 }
            .distinctBy { it.source.normalizeToken() }
            .take(sectionLimit)
            .map { row ->
                val source = row.source.normalizeToken()
                CategoryBucket(
                    id = stableId("bucket", "${CategoryBucketType.APP_SOURCE}:$source"),
                    title = source.toDisplayLabel(),
                    type = CategoryBucketType.APP_SOURCE,
                    queryValue = source,
                    count = row.count,
                    sampleScreenshots = sampleScreenshots(
                        bucketType = CategoryBucketType.APP_SOURCE,
                        queryValue = source,
                        dateRange = range,
                        limit = sampleSize,
                    ),
                    isSensitive = false,
                    description = "App or source",
                )
            }
    }

    private suspend fun visualLabelBuckets(
        request: CategoryOverviewRequest,
        sectionLimit: Int,
        sampleSize: Int,
    ): List<CategoryBucket> {
        val range = request.dateRange
        return dao.visualBucketCounts(
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
            minLabelConfidence = MIN_VISUAL_LABEL_CONFIDENCE,
            minObjectConfidence = MIN_OBJECT_LABEL_CONFIDENCE,
            minObjectAreaRatio = MIN_OBJECT_AREA_RATIO,
            limit = sectionLimit * FETCH_MULTIPLIER,
        )
            .filter { it.count > 0 }
            .map { it.copy(label = it.label.normalizeConcept()) }
            .filter { isUsefulVisualLabel(it.label) }
            .distinctBy { it.label }
            .take(sectionLimit)
            .map { row ->
                CategoryBucket(
                    id = stableId("bucket", "${CategoryBucketType.VISUAL_LABEL}:${row.label}"),
                    title = row.label.toDisplayLabel(),
                    type = CategoryBucketType.VISUAL_LABEL,
                    queryValue = row.label,
                    count = row.count,
                    sampleScreenshots = sampleScreenshots(
                        bucketType = CategoryBucketType.VISUAL_LABEL,
                        queryValue = row.label,
                        dateRange = range,
                        limit = sampleSize,
                    ),
                    isSensitive = row.label in SENSITIVE_VISUAL_LABELS,
                    description = "Visual label",
                )
            }
    }

    private suspend fun entityTypeBuckets(
        request: CategoryOverviewRequest,
        sectionLimit: Int,
        sampleSize: Int,
    ): List<CategoryBucket> {
        val range = request.dateRange
        return dao.entityTypeBucketCounts(
            startMillis = range?.startMillis,
            endMillisExclusive = range?.endMillisExclusive,
            limit = sectionLimit * FETCH_MULTIPLIER,
        )
            .filter { it.count > 0 }
            .filter { it.type in ENTITY_TYPE_TITLES }
            .take(sectionLimit)
            .map { row ->
                val type = row.type.normalizeToken()
                CategoryBucket(
                    id = stableId("bucket", "${CategoryBucketType.ENTITY_TYPE}:$type"),
                    title = ENTITY_TYPE_TITLES[type] ?: type.toDisplayLabel(),
                    type = CategoryBucketType.ENTITY_TYPE,
                    queryValue = type,
                    count = row.count,
                    sampleScreenshots = sampleScreenshots(
                        bucketType = CategoryBucketType.ENTITY_TYPE,
                        queryValue = type,
                        dateRange = range,
                        limit = sampleSize,
                    ),
                    isSensitive = row.sensitiveCount > 0 || type in SENSITIVE_ENTITY_TYPES,
                    description = entityDescription(row),
                )
            }
    }

    private fun entityDescription(row: EntityTypeBucketCount): String {
        return if (row.sensitiveCount > 0 || row.type in SENSITIVE_ENTITY_TYPES) {
            "Sensitive smart folder"
        } else {
            "Smart folder"
        }
    }

    private suspend fun sampleScreenshots(
        bucketType: CategoryBucketType,
        queryValue: String,
        dateRange: com.askmyscreenshots.skill.api.SkillDateRange?,
        limit: Int,
    ): List<CategoryScreenshotPreview> {
        if (limit <= 0) return emptyList()
        return screenshotsForBucket(
            bucket = CategoryBucket(
                id = "",
                title = "",
                type = bucketType,
                queryValue = queryValue,
                count = 0,
                sampleScreenshots = emptyList(),
                isSensitive = false,
                description = "",
            ),
            dateRange = dateRange,
            limit = limit,
        ).map { it.toCategoryPreview() }
    }

    private suspend fun screenshotsForBucket(
        bucket: CategoryBucket,
        dateRange: com.askmyscreenshots.skill.api.SkillDateRange?,
        limit: Int,
    ): List<ScreenshotEntity> {
        val start = dateRange?.startMillis
        val end = dateRange?.endMillisExclusive
        return when (bucket.type) {
            CategoryBucketType.DYNAMIC_CATEGORY -> dao.screenshotsForCategory(
                category = bucket.queryValue,
                startMillis = start,
                endMillisExclusive = end,
                limit = limit,
            )
            CategoryBucketType.APP_SOURCE -> dao.screenshotsForAppSource(
                appSource = bucket.queryValue.normalizeToken(),
                startMillis = start,
                endMillisExclusive = end,
                limit = limit,
            )
            CategoryBucketType.VISUAL_LABEL -> dao.screenshotsForVisualBucket(
                label = bucket.queryValue.normalizeConcept(),
                startMillis = start,
                endMillisExclusive = end,
                minLabelConfidence = MIN_VISUAL_LABEL_CONFIDENCE,
                minObjectConfidence = MIN_OBJECT_LABEL_CONFIDENCE,
                minObjectAreaRatio = MIN_OBJECT_AREA_RATIO,
                limit = limit,
            )
            CategoryBucketType.ENTITY_TYPE -> dao.screenshotsForEntityTypeBucket(
                entityType = bucket.queryValue.normalizeToken(),
                startMillis = start,
                endMillisExclusive = end,
                limit = limit,
            )
        }
    }

    private fun ScreenshotEntity.toCategoryPreview(): CategoryScreenshotPreview {
        return CategoryScreenshotPreview(
            id = id,
            uri = uri,
            mediaStoreId = mediaStoreId,
            title = displayName?.takeIf { it.isNotBlank() }
                ?: appHint?.toDisplayLabel()
                ?: "Screenshot $id",
            takenAtMillis = dateTakenMillis,
            appHint = appHint,
            category = category,
            snippet = ocrText.cleanWhitespace().take(MAX_SNIPPET_CHARS),
            width = width,
            height = height,
        )
    }

    private fun isUsefulVisualLabel(label: String): Boolean {
        return label.length >= 3 &&
            label !in NOISY_VISUAL_LABELS &&
            !label.all(Char::isDigit)
    }

    private fun String.normalizeToken(): String {
        return lowercase(Locale.US)
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.normalizeConcept(): String {
        return normalizeToken()
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun String.toDisplayLabel(): String {
        return replace('_', ' ')
            .cleanWhitespace()
            .split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                when {
                    word in DISPLAY_ACRONYMS -> word.uppercase(Locale.US)
                    word.equals("gpay", ignoreCase = true) -> "GPay"
                    word.equals("upi", ignoreCase = true) -> "UPI"
                    word.equals("gst", ignoreCase = true) -> "GST"
                    else -> word.replaceFirstChar { char ->
                        if (char.isLowerCase()) char.titlecase(Locale.US) else char.toString()
                    }
                }
            }
            .ifBlank { this }
    }

    private fun String.cleanWhitespace(): String {
        return replace(Regex("""\s+"""), " ").trim()
    }

    private fun stableId(prefix: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "$prefix-$digest"
    }

    companion object {
        private const val DEFAULT_SAMPLE_SIZE = 4
        private const val FETCH_MULTIPLIER = 4
        private const val MAX_SNIPPET_CHARS = 160
        private const val MIN_VISUAL_LABEL_CONFIDENCE = 0.55f
        private const val MIN_OBJECT_LABEL_CONFIDENCE = 0.45f
        private const val MIN_OBJECT_AREA_RATIO = 0.015f

        private val DISPLAY_ACRONYMS = setOf("ai", "id", "ids", "ifsc", "otp", "pan", "qr", "url", "wifi")

        private val NOISY_VISUAL_LABELS = setOf(
            "text",
            "font",
            "image",
            "screenshot",
            "snapshot",
            "photograph",
            "rectangle",
            "number",
            "product",
            "material property",
            "electronic device",
            "brand",
            "logo",
            "graphics",
            "pattern",
        )

        private val SENSITIVE_VISUAL_LABELS = setOf("person", "people", "face", "human", "child")

        private val SENSITIVE_ENTITY_TYPES = setOf(
            "phone",
            "email",
            "upi_id",
            "amount",
            "pan",
            "aadhaar",
            "passport",
            "account_number",
            "card_number",
            "ifsc",
            "order_id",
            "booking_id",
            "otp",
            "gstin",
            "invoice_id",
            "tracking_id",
            "pincode",
            "person_name",
            "counterparty",
        )

        private val ENTITY_TYPE_TITLES = mapOf(
            "phone" to "Phone Numbers",
            "email" to "Email Addresses",
            "url" to "Links",
            "domain" to "Web Domains",
            "upi_id" to "UPI IDs",
            "amount" to "Amounts",
            "pan" to "PAN Cards",
            "aadhaar" to "Aadhaar IDs",
            "passport" to "Passports",
            "account_number" to "Bank Accounts",
            "card_number" to "Cards",
            "ifsc" to "IFSC Codes",
            "order_id" to "Orders",
            "booking_id" to "Bookings",
            "flight_hint" to "Flights",
            "train_hint" to "Trains",
            "otp" to "OTP & Security Codes",
            "gstin" to "GST & Tax IDs",
            "invoice_id" to "Invoices & Bills",
            "tracking_id" to "Tracking IDs",
            "date" to "Dates",
            "time" to "Times",
            "person_name" to "Names",
            "counterparty" to "Payment Counterparties",
            "social_handle" to "Social Handles",
            "hashtag" to "Hashtags",
            "barcode_signal" to "QR Codes & Barcodes",
            "barcode_value_type" to "Barcode Contents",
            "pincode" to "PIN Codes",
        )
    }
}
