package com.askmyscreenshots.skill.search

import com.askmyscreenshots.skill.api.SearchRequest
import com.askmyscreenshots.skill.api.SkillDateRange
import com.askmyscreenshots.skill.ml.ScreenshotCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class LocalQueryPlan(
    val normalizedQuery: String,
    val ftsQuery: String?,
    val intent: String,
    val searchTerms: List<String>,
    val categories: List<String>,
    val entityTypes: List<String>,
    val appHints: List<String> = emptyList(),
    val dateRange: SkillDateRange?,
)

class QueryPlanner(
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    fun plan(request: SearchRequest): LocalQueryPlan {
        val query = request.query.trim()
        val lowered = query.lowercase()
        return LocalQueryPlan(
            normalizedQuery = lowered,
            ftsQuery = toFtsQuery(lowered),
            intent = intentFor(lowered),
            searchTerms = searchTermsFor(lowered),
            categories = categoriesFor(lowered),
            entityTypes = entityTypesFor(lowered),
            appHints = appHintsFor(lowered),
            dateRange = request.dateRange ?: dateRangeFor(lowered),
        )
    }

    fun toFtsQuery(query: String): String? {
        val tokens = query
            .split(Regex("""[^\p{L}\p{Nd}]+"""))
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 }
            .map { it.filter { char -> char.isLetterOrDigit() || char == '_' } }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" OR ") { "$it*" }
    }

    private fun categoriesFor(query: String): List<String> {
        val categories = mutableSetOf<String>()
        fun add(category: ScreenshotCategory) {
            categories += category.value
        }
        fun addDynamic(vararg dynamicCategories: String) {
            categories += dynamicCategories
        }
        if (hasAny(query, "whatsapp", "telegram", "chat", "message")) add(ScreenshotCategory.CHAT)
        if (hasAny(query, "instagram", "insta", "facebook", "linkedin", "twitter", "social")) add(ScreenshotCategory.SOCIAL)
        if (hasAny(query, "paid", "pay", "payment", "upi", "transaction", "sent money", "received money")) add(ScreenshotCategory.PAYMENTS)
        if (hasAny(query, "bank", "statement", "card", "account", "finance")) add(ScreenshotCategory.FINANCE)
        if (hasAny(query, "aadhaar", "aadhar", "pan", "passport", "id", "govt", "government")) add(ScreenshotCategory.IDENTITY_DOCS)
        if (hasAny(query, "booking", "voucher", "ticket", "pnr", "flight", "hotel", "train", "travel")) add(ScreenshotCategory.BOOKING_TRAVEL)
        if (hasAny(query, "amazon", "flipkart", "shopping", "order", "invoice")) add(ScreenshotCategory.SHOPPING)
        if (hasAny(query, "swiggy", "zomato", "food", "restaurant")) add(ScreenshotCategory.FOOD)
        if (hasAny(query, "map", "maps", "route", "directions", "location")) add(ScreenshotCategory.MAPS)
        if (hasAny(query, "ai", "llm", "openai", "machine learning", "artificial intelligence")) add(ScreenshotCategory.AI_NEWS)
        if (hasAny(query, "error", "code", "stacktrace", "github", "bug")) add(ScreenshotCategory.CODE_ERRORS)
        if (hasAny(query, "email", "gmail", "inbox", "mail")) add(ScreenshotCategory.EMAILS)
        if (hasAny(query, "photo", "video", "youtube", "music", "media")) add(ScreenshotCategory.MEDIA)
        if (hasAny(query, "pdf", "doc", "document", "contract")) add(ScreenshotCategory.DOCUMENTS)
        if (hasAny(query, "doctor", "hospital", "health", "medicine", "prescription", "fitness")) addDynamic("health")
        if (hasAny(query, "news", "headline", "article", "publisher")) addDynamic("news")
        if (hasAny(query, "receipt", "invoice", "bill")) addDynamic("receipt")
        if (hasAny(query, "delivery", "shipment", "tracking", "awb")) addDynamic("delivery")
        if (hasAny(query, "qr", "barcode", "scan")) addDynamic("qr_code")
        if (hasAny(query, "job", "career", "hiring", "interview")) addDynamic("jobs")
        if (hasAny(query, "course", "exam", "school", "college", "education")) addDynamic("education")
        if (hasAny(query, "event", "meeting", "webinar", "calendar")) addDynamic("event")
        if (hasAny(query, "rent", "property", "flat", "apartment", "real estate")) addDynamic("real_estate")
        if (hasAny(query, "person", "people", "face", "faces")) addDynamic("people")
        return categories.toList()
    }

    private fun intentFor(query: String): String {
        return when {
            hasAny(query, "how much", "total", "sum", "spent", "expense") -> "aggregate"
            hasAny(query, "what is", "what was", "which", "who", "when", "where") -> "answer"
            hasAny(query, "show", "find", "search", "list") -> "find"
            else -> "answer"
        }
    }

    private fun searchTermsFor(query: String): List<String> {
        return query
            .split(Regex("""[^\p{L}\p{Nd}@._+-]+"""))
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 }
            .distinct()
            .take(12)
    }

    internal fun searchTermsForInternal(query: String): List<String> {
        return searchTermsFor(query)
    }

    private fun entityTypesFor(query: String): List<String> {
        val types = mutableSetOf<String>()
        fun add(vararg entityTypes: String) {
            types += entityTypes
        }
        if (hasAny(query, "aadhaar", "aadhar")) add("aadhaar")
        if (hasAny(query, "pan")) add("pan")
        if (hasAny(query, "phone", "mobile", "number", "contact")) add("phone")
        if (hasAny(query, "upi", "vpa")) add("upi_id")
        if (hasAny(query, "amount", "money", "paid", "pay", "spent", "payment")) add("amount", "upi_id", "person_name")
        if (hasAny(query, "account", "bank")) add("account_number", "ifsc")
        if (hasAny(query, "card")) add("card_number")
        if (hasAny(query, "booking", "voucher", "pnr", "ticket")) add("booking_id", "flight_hint", "train_hint")
        if (hasAny(query, "order", "invoice")) add("order_id")
        if (hasAny(query, "invoice", "bill")) add("invoice_id")
        if (hasAny(query, "tracking", "shipment", "awb", "waybill")) add("tracking_id")
        if (hasAny(query, "transaction", "txn", "utr", "reference")) add("transaction_id")
        if (hasAny(query, "otp", "verification code", "login code")) add("otp")
        if (hasAny(query, "gst", "gstin", "tax")) add("gstin")
        if (hasAny(query, "passport", "visa")) add("passport")
        if (hasAny(query, "qr", "barcode")) add("barcode_signal", "barcode_value_type")
        if (hasAny(query, "face", "faces", "people", "person")) add("face_count", "people_presence")
        if (hasAny(query, "hashtag")) add("hashtag")
        if (hasAny(query, "handle", "username")) add("social_handle")
        if (hasAny(query, "email", "mail")) add("email")
        if (hasAny(query, "website", "link", "url")) add("url")
        if (hasAny(query, "who", "whom", "person", "name")) add("person_name", "phone", "email", "upi_id")
        return types.toList()
    }

    private fun appHintsFor(query: String): List<String> {
        val hints = mutableSetOf<String>()
        fun addIfPresent(hint: String, vararg aliases: String) {
            if (aliases.any { query.contains(it) }) hints += hint
        }
        addIfPresent("whatsapp", "whatsapp", "wa chat")
        addIfPresent("telegram", "telegram")
        addIfPresent("linkedin", "linkedin")
        addIfPresent("instagram", "instagram", "insta")
        addIfPresent("facebook", "facebook")
        addIfPresent("gpay", "gpay", "google pay")
        addIfPresent("phonepe", "phonepe")
        addIfPresent("paytm", "paytm")
        addIfPresent("amazon", "amazon")
        addIfPresent("flipkart", "flipkart")
        addIfPresent("swiggy", "swiggy")
        addIfPresent("zomato", "zomato")
        addIfPresent("gmail", "gmail")
        addIfPresent("teams", "teams", "microsoft teams")
        addIfPresent("slack", "slack")
        addIfPresent("jira", "jira")
        addIfPresent("github", "github")
        return hints.toList()
    }

    private fun dateRangeFor(query: String): SkillDateRange? {
        val today = LocalDate.now(zoneId)
        return when {
            query.contains("today") -> today.toRange()
            query.contains("yesterday") -> today.minusDays(1).toRange()
            query.contains("last week") || query.contains("last 7 days") -> {
                val start = today.minusDays(6)
                SkillDateRange(
                    startMillis = start.startMillis(),
                    endMillisExclusive = today.plusDays(1).startMillis(),
                )
            }

            query.contains("last month") -> {
                val firstThisMonth = today.withDayOfMonth(1)
                val firstLastMonth = firstThisMonth.minusMonths(1)
                SkillDateRange(
                    startMillis = firstLastMonth.startMillis(),
                    endMillisExclusive = firstThisMonth.startMillis(),
                )
            }

            query.contains("this month") -> {
                val firstThisMonth = today.withDayOfMonth(1)
                SkillDateRange(
                    startMillis = firstThisMonth.startMillis(),
                    endMillisExclusive = today.plusDays(1).startMillis(),
                )
            }

            else -> null
        }
    }

    private fun LocalDate.toRange(): SkillDateRange {
        return SkillDateRange(
            startMillis = startMillis(),
            endMillisExclusive = plusDays(1).startMillis(),
        )
    }

    private fun LocalDate.startMillis(): Long {
        return atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    private fun hasAny(text: String, vararg needles: String): Boolean {
        return needles.any { text.contains(it) }
    }
}
