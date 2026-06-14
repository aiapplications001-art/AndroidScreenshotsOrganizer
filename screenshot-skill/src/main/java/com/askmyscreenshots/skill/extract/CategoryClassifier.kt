package com.askmyscreenshots.skill.extract

import com.askmyscreenshots.skill.ml.CategoryAssignmentDraft
import com.askmyscreenshots.skill.ml.DetectedEntityDraft
import com.askmyscreenshots.skill.ml.ScreenshotCategory
import com.askmyscreenshots.skill.ml.VisualLabelDraft
import java.util.Locale

class CategoryClassifier {
    fun classify(
        text: String,
        entities: List<DetectedEntityDraft>,
        labels: List<VisualLabelDraft> = emptyList(),
    ): List<CategoryAssignmentDraft> {
        val context = CategoryContext(text, entities, labels)
        val scores = buildList {
            scoreChat(context)?.let(::add)
            scoreSocial(context)?.let(::add)
            scorePayments(context)?.let(::add)
            scoreFinance(context)?.let(::add)
            scoreIdentityDocs(context)?.let(::add)
            scoreBookingTravel(context)?.let(::add)
            scoreShopping(context)?.let(::add)
            scoreFood(context)?.let(::add)
            scoreMaps(context)?.let(::add)
            scoreAiNews(context)?.let(::add)
            scoreCodeErrors(context)?.let(::add)
            scoreEmails(context)?.let(::add)
            scoreMedia(context)?.let(::add)
            scoreDocuments(context)?.let(::add)
            scoreHealth(context)?.let(::add)
            scoreNews(context)?.let(::add)
            scoreReceipts(context)?.let(::add)
            scoreDelivery(context)?.let(::add)
            scoreSubscriptions(context)?.let(::add)
            scoreJobs(context)?.let(::add)
            scoreEducation(context)?.let(::add)
            scoreEvents(context)?.let(::add)
            scoreRealEstate(context)?.let(::add)
            addAll(dynamicCategoryScores(context))
        }

        return mergeScores(scores)
            .ifEmpty {
                listOf(
                    CategoryScore(
                        category = ScreenshotCategory.UNKNOWN.value,
                        confidence = 0.3f,
                        reason = "no strong local category signal",
                    ),
                )
            }
            .sortedWith(compareByDescending<CategoryScore> { it.confidence }.thenBy { it.category })
            .take(MAX_CATEGORIES_PER_SCREENSHOT)
            .map { score ->
                CategoryAssignmentDraft(
                    category = score.category,
                    confidence = score.confidence,
                    reason = score.reason,
                )
            }
    }

    private fun scoreChat(context: CategoryContext): CategoryScore? {
        val hasChatApp = context.hasTextAny("whatsapp", "telegram", "signal", "sms", "messages") ||
            context.hasEntityValue("app", "whatsapp", "telegram", "signal")
        val hasConversationUi = context.hasTextAny(
            "typing",
            "online",
            "last seen",
            "voice message",
            "video call",
            "missed call",
        )
        val hasMessageKeyword = context.hasTextAny("chat", "message", "reply")
        if (!hasChatApp && !(hasConversationUi && hasMessageKeyword)) return null

        return CategoryScore(
            category = ScreenshotCategory.CHAT.value,
            confidence = confidence(0.66f, hasChatApp, hasConversationUi, hasMessageKeyword),
            reason = reason(
                "chat app" to hasChatApp,
                "conversation UI" to hasConversationUi,
                "message keyword" to hasMessageKeyword,
            ),
        )
    }

    private fun scoreSocial(context: CategoryContext): CategoryScore? {
        val hasSocialApp = context.hasTextAny(
            "instagram",
            "insta",
            "facebook",
            "linkedin",
            "twitter",
            "x.com",
            "threads",
        ) || context.hasEntityValue("app", "instagram", "facebook", "linkedin", "twitter", "threads")
        val hasSocialUi = context.hasTextAny(
            "repost",
            "followers",
            "following",
            "connect",
            "connections",
            "comment",
            "shared a post",
        )
        if (!hasSocialApp && !hasSocialUi) return null

        return CategoryScore(
            category = ScreenshotCategory.SOCIAL.value,
            confidence = confidence(0.64f, hasSocialApp, hasSocialUi),
            reason = reason(
                "social app" to hasSocialApp,
                "social feed UI" to hasSocialUi,
            ),
        )
    }

    private fun scorePayments(context: CategoryContext): CategoryScore? {
        val hasPaymentApp = context.hasTextAny(
            "gpay",
            "google pay",
            "phonepe",
            "paytm",
            "bhim",
            "upi lite",
            "razorpay",
            "cashfree",
        ) || context.hasEntityValue("app", "gpay", "google pay", "phonepe", "paytm")
        val hasUpi = context.hasEntity("upi_id") || context.hasTextAny("upi")
        val hasAmount = context.hasEntity("amount")
        val hasPaymentAction = context.hasTextAny(
            "paid successfully",
            "payment successful",
            "payment done",
            "paid to",
            "sent money",
            "money sent",
            "received from",
            "money received",
            "debited",
            "credited",
            "transferred",
        )
        val hasTransactionRef = context.hasEntity("transaction_id") || context.hasTextAny(
            "transaction id",
            "transaction no",
            "utr",
            "upi ref",
            "reference id",
            "ref no",
            "bank transfer",
        )
        val hasPaymentBarcode = context.hasEntityValue("barcode_value_type", "url") &&
            (hasUpi || context.hasTextAny("upi", "pay", "payment"))
        val hasPaymentWord = context.hasTextAny("payment", "payments")
        val hasStrongEvidence = hasTransactionRef ||
            hasPaymentBarcode ||
            (hasPaymentApp && (hasUpi || hasAmount || hasPaymentAction || hasPaymentWord)) ||
            (hasUpi && (hasAmount || hasPaymentAction || hasTransactionRef)) ||
            (hasPaymentAction && hasAmount)

        if (!hasStrongEvidence) return null

        return CategoryScore(
            category = ScreenshotCategory.PAYMENTS.value,
            confidence = confidence(
                base = 0.7f,
                hasPaymentApp,
                hasUpi,
                hasAmount,
                hasPaymentAction,
                hasTransactionRef,
                hasPaymentBarcode,
            ),
            reason = reason(
                "payment app" to hasPaymentApp,
                "UPI signal" to hasUpi,
                "amount entity" to hasAmount,
                "payment action" to hasPaymentAction,
                "transaction reference" to hasTransactionRef,
                "payment QR/link" to hasPaymentBarcode,
            ),
        )
    }

    private fun scoreFinance(context: CategoryContext): CategoryScore? {
        val hasFinanceEntity = context.hasEntity("ifsc", "account_number", "card_number", "gstin")
        val hasBanking = context.hasTextAny("bank", "account", "statement", "ifsc", "balance", "salary", "tax", "gst")
        val hasInvesting = context.hasTextAny("mutual fund", "stock", "portfolio", "demat", "sip", "market")
        if (!hasFinanceEntity && !hasBanking && !hasInvesting) return null

        return CategoryScore(
            category = ScreenshotCategory.FINANCE.value,
            confidence = confidence(0.64f, hasFinanceEntity, hasBanking, hasInvesting),
            reason = reason(
                "finance entity" to hasFinanceEntity,
                "banking keyword" to hasBanking,
                "investment keyword" to hasInvesting,
            ),
        )
    }

    private fun scoreIdentityDocs(context: CategoryContext): CategoryScore? {
        val hasIdentityEntity = context.hasEntity("aadhaar", "pan", "passport")
        val hasIdentityPhrase = context.hasTextAny(
            "aadhaar",
            "aadhar",
            "uidai",
            "pan card",
            "permanent account number",
            "passport",
            "driving licence",
            "driving license",
            "voter id",
            "government id",
        )
        if (!hasIdentityEntity && !hasIdentityPhrase) return null

        return CategoryScore(
            category = ScreenshotCategory.IDENTITY_DOCS.value,
            confidence = confidence(0.68f, hasIdentityEntity, hasIdentityPhrase),
            reason = reason(
                "identity entity" to hasIdentityEntity,
                "identity phrase" to hasIdentityPhrase,
            ),
        )
    }

    private fun scoreBookingTravel(context: CategoryContext): CategoryScore? {
        val hasBookingEntity = context.hasEntity("booking_id", "flight_hint", "train_hint", "passport")
        val hasBookingPhrase = context.hasTextAny(
            "booking",
            "voucher",
            "reservation",
            "confirmation",
            "pnr",
            "boarding pass",
            "itinerary",
        )
        val hasTravelPhrase = context.hasTextAny("flight", "hotel", "train", "airline", "terminal", "gate", "visa")
        if (!hasBookingEntity && !hasBookingPhrase && !hasTravelPhrase) return null

        return CategoryScore(
            category = ScreenshotCategory.BOOKING_TRAVEL.value,
            confidence = confidence(0.64f, hasBookingEntity, hasBookingPhrase, hasTravelPhrase),
            reason = reason(
                "booking entity" to hasBookingEntity,
                "booking phrase" to hasBookingPhrase,
                "travel phrase" to hasTravelPhrase,
            ),
        )
    }

    private fun scoreShopping(context: CategoryContext): CategoryScore? {
        val hasShoppingApp = context.hasTextAny("amazon", "flipkart", "myntra", "meesho", "ajio")
        val hasOrderEntity = context.hasEntity("order_id", "invoice_id", "tracking_id")
        val hasCommercePhrase = context.hasTextAny("cart", "delivery", "shipped", "out for delivery", "return", "refund")
        val hasOrderPhrase = context.hasTextAny("order", "invoice")
        val hasProductVisual = context.hasVisualConcept("fashion good", "home good", "product", "clothing", "shoe")
        if (!hasShoppingApp && !hasOrderEntity && !hasCommercePhrase && !hasOrderPhrase && !hasProductVisual) return null

        return CategoryScore(
            category = ScreenshotCategory.SHOPPING.value,
            confidence = confidence(0.62f, hasShoppingApp, hasOrderEntity, hasCommercePhrase, hasOrderPhrase, hasProductVisual),
            reason = reason(
                "shopping app" to hasShoppingApp,
                "order entity" to hasOrderEntity,
                "commerce phrase" to hasCommercePhrase,
                "order phrase" to hasOrderPhrase,
                "product visual" to hasProductVisual,
            ),
        )
    }

    private fun scoreFood(context: CategoryContext): CategoryScore? {
        val hasFoodApp = context.hasTextAny("swiggy", "zomato")
        val hasFoodPhrase = context.hasTextAny("restaurant", "food", "delivery partner", "menu", "dish", "meal")
        val hasFoodVisual = context.hasVisualConcept("food", "restaurant", "dish", "meal")
        if (!hasFoodApp && !hasFoodPhrase && !hasFoodVisual) return null

        return CategoryScore(
            category = ScreenshotCategory.FOOD.value,
            confidence = confidence(0.62f, hasFoodApp, hasFoodPhrase, hasFoodVisual),
            reason = reason(
                "food app" to hasFoodApp,
                "food phrase" to hasFoodPhrase,
                "food visual" to hasFoodVisual,
            ),
        )
    }

    private fun scoreMaps(context: CategoryContext): CategoryScore? {
        val hasMapSource = context.hasTextAny("maps", "google maps")
        val hasRoutePhrase = context.hasTextAny(
            "route",
            "directions",
            "near me",
            "eta",
            "kilometres",
            "kilometers",
            "km away",
        )
        val hasPlaceSignal = context.hasVisualConcept("place", "map") || context.hasEntityValue("barcode_value_type", "geo")
        if (!hasMapSource && !hasRoutePhrase && !hasPlaceSignal) return null

        return CategoryScore(
            category = ScreenshotCategory.MAPS.value,
            confidence = confidence(0.6f, hasMapSource, hasRoutePhrase, hasPlaceSignal),
            reason = reason(
                "map source" to hasMapSource,
                "route phrase" to hasRoutePhrase,
                "place signal" to hasPlaceSignal,
            ),
        )
    }

    private fun scoreAiNews(context: CategoryContext): CategoryScore? {
        val hasAiPhrase = context.hasTextAny(
            "ai",
            "llm",
            "openai",
            "anthropic",
            "gemini",
            "machine learning",
            "artificial intelligence",
            "language model",
        )
        val hasModelContext = context.hasTextAny("model", "models") &&
            context.hasTextAny("startup", "funding", "training", "inference", "agent")
        if (!hasAiPhrase && !hasModelContext) return null

        return CategoryScore(
            category = ScreenshotCategory.AI_NEWS.value,
            confidence = confidence(0.58f, hasAiPhrase, hasModelContext),
            reason = reason(
                "AI phrase" to hasAiPhrase,
                "model context" to hasModelContext,
            ),
        )
    }

    private fun scoreCodeErrors(context: CategoryContext): CategoryScore? {
        val hasCodeSource = context.hasTextAny("github", "stacktrace", "exception", "error", "failed")
        val hasLanguage = context.hasTextAny("kotlin", "python", "java", "javascript", "typescript", "gradle")
        val hasCodeShape = context.hasTextAny("class", "function", "import", "nullpointer", "traceback")
        if (!hasCodeSource && !(hasLanguage && hasCodeShape)) return null

        return CategoryScore(
            category = ScreenshotCategory.CODE_ERRORS.value,
            confidence = confidence(0.62f, hasCodeSource, hasLanguage, hasCodeShape),
            reason = reason(
                "code/error phrase" to hasCodeSource,
                "programming language" to hasLanguage,
                "code-shaped text" to hasCodeShape,
            ),
        )
    }

    private fun scoreEmails(context: CategoryContext): CategoryScore? {
        val hasEmailApp = context.hasTextAny("gmail", "inbox", "outlook")
        val hasEmailHeader = context.hasTextAny("subject", "from:", "to:", "cc:", "unsubscribe")
        val hasEmailEntity = context.hasEntity("email") || context.hasEntityValue("barcode_value_type", "email")
        if (!hasEmailApp && !hasEmailHeader && !hasEmailEntity) return null

        return CategoryScore(
            category = ScreenshotCategory.EMAILS.value,
            confidence = confidence(0.6f, hasEmailApp, hasEmailHeader, hasEmailEntity),
            reason = reason(
                "email app" to hasEmailApp,
                "email header" to hasEmailHeader,
                "email entity" to hasEmailEntity,
            ),
        )
    }

    private fun scoreMedia(context: CategoryContext): CategoryScore? {
        val hasMediaApp = context.hasTextAny("youtube", "netflix", "spotify")
        val hasMediaPhrase = context.hasTextAny("photo", "video", "album", "music", "playlist", "watch")
        val hasMediaVisual = context.hasVisualConcept("photograph", "video", "music", "poster")
        if (!hasMediaApp && !hasMediaPhrase && !hasMediaVisual) return null

        return CategoryScore(
            category = ScreenshotCategory.MEDIA.value,
            confidence = confidence(0.58f, hasMediaApp, hasMediaPhrase, hasMediaVisual),
            reason = reason(
                "media app" to hasMediaApp,
                "media phrase" to hasMediaPhrase,
                "media visual" to hasMediaVisual,
            ),
        )
    }

    private fun scoreDocuments(context: CategoryContext): CategoryScore? {
        val hasDocumentPhrase = context.hasTextAny(
            "pdf",
            "document",
            "spreadsheet",
            "slide",
            "contract",
            "agreement",
            "certificate",
            "form",
        )
        val hasDocumentUi = context.hasTextAny("page 1", "page 2", "download pdf", "open with")
        val hasBarcodeDocument = context.hasEntityValue("barcode_value_type", "driver_license")
        if (!hasDocumentPhrase && !hasDocumentUi && !hasBarcodeDocument) return null

        return CategoryScore(
            category = ScreenshotCategory.DOCUMENTS.value,
            confidence = confidence(0.58f, hasDocumentPhrase, hasDocumentUi, hasBarcodeDocument),
            reason = reason(
                "document phrase" to hasDocumentPhrase,
                "document UI" to hasDocumentUi,
                "document barcode" to hasBarcodeDocument,
            ),
        )
    }

    private fun scoreHealth(context: CategoryContext): CategoryScore? {
        val hasHealthText = context.hasTextAny(
            "doctor",
            "hospital",
            "clinic",
            "appointment",
            "medicine",
            "prescription",
            "medical",
            "health",
            "fitness",
            "lab report",
        )
        val hasHealthTopic = context.hasEntityValue("topic", "health", "fitness", "medicine", "doctor", "hospital")
        if (!hasHealthText && !hasHealthTopic) return null
        return CategoryScore(
            category = "health",
            confidence = confidence(0.58f, hasHealthText, hasHealthTopic),
            reason = reason("health text" to hasHealthText, "health topic" to hasHealthTopic),
        )
    }

    private fun scoreNews(context: CategoryContext): CategoryScore? {
        val hasNewsText = context.hasTextAny("news", "headline", "article", "reported", "breaking", "publisher")
        val hasNewsTopic = context.hasEntityValue("topic", "news")
        if (!hasNewsText && !hasNewsTopic) return null
        return CategoryScore(
            category = "news",
            confidence = confidence(0.54f, hasNewsText, hasNewsTopic),
            reason = reason("news text" to hasNewsText, "news topic" to hasNewsTopic),
        )
    }

    private fun scoreReceipts(context: CategoryContext): CategoryScore? {
        val hasReceiptText = context.hasTextAny("receipt", "invoice", "bill", "tax invoice", "paid", "total")
        val hasReceiptEntity = context.hasEntity("amount", "invoice_id", "transaction_id", "gstin")
        if (!(hasReceiptText && hasReceiptEntity)) return null
        return CategoryScore(
            category = "receipt",
            confidence = confidence(0.62f, hasReceiptText, hasReceiptEntity),
            reason = reason("receipt text" to hasReceiptText, "receipt entity" to hasReceiptEntity),
        )
    }

    private fun scoreDelivery(context: CategoryContext): CategoryScore? {
        val hasDeliveryText = context.hasTextAny("delivery", "out for delivery", "shipped", "tracking", "shipment", "awb")
        val hasTracking = context.hasEntity("tracking_id")
        if (!hasDeliveryText && !hasTracking) return null
        return CategoryScore(
            category = "delivery",
            confidence = confidence(0.58f, hasDeliveryText, hasTracking),
            reason = reason("delivery text" to hasDeliveryText, "tracking entity" to hasTracking),
        )
    }

    private fun scoreSubscriptions(context: CategoryContext): CategoryScore? {
        val hasSubscriptionText = context.hasTextAny("subscription", "renewal", "auto renew", "monthly", "annual", "expires", "due")
        if (!hasSubscriptionText) return null
        return CategoryScore(
            category = "subscription",
            confidence = 0.58f,
            reason = "subscription/renewal signal",
        )
    }

    private fun scoreJobs(context: CategoryContext): CategoryScore? {
        val hasJobText = context.hasTextAny("job", "career", "hiring", "interview", "recruiter", "resume", "cv", "candidate")
        if (!hasJobText) return null
        return CategoryScore(
            category = "jobs",
            confidence = 0.56f,
            reason = "job/career signal",
        )
    }

    private fun scoreEducation(context: CategoryContext): CategoryScore? {
        val hasEducationText = context.hasTextAny("course", "class", "exam", "assignment", "lecture", "university", "school", "college")
        if (!hasEducationText) return null
        return CategoryScore(
            category = "education",
            confidence = 0.54f,
            reason = "education signal",
        )
    }

    private fun scoreEvents(context: CategoryContext): CategoryScore? {
        val hasEventText = context.hasTextAny("event", "calendar", "meeting", "webinar", "concert", "ticket", "schedule")
        val hasTime = context.hasEntity("time")
        val hasCalendarBarcode = context.hasEntityValue("barcode_value_type", "calendar_event")
        if (!hasEventText && !(hasTime && context.hasEntity("date")) && !hasCalendarBarcode) return null
        return CategoryScore(
            category = "event",
            confidence = confidence(0.54f, hasEventText, hasTime, hasCalendarBarcode),
            reason = reason("event text" to hasEventText, "time entity" to hasTime, "calendar barcode" to hasCalendarBarcode),
        )
    }

    private fun scoreRealEstate(context: CategoryContext): CategoryScore? {
        val hasRealEstateText = context.hasTextAny("real estate", "property", "rent", "lease", "apartment", "flat", "broker")
        if (!hasRealEstateText) return null
        return CategoryScore(
            category = "real_estate",
            confidence = 0.54f,
            reason = "real estate signal",
        )
    }

    private fun dynamicCategoryScores(context: CategoryContext): List<CategoryScore> {
        val scores = mutableListOf<CategoryScore>()
        context.entities.forEach { entity ->
            val normalizedValue = normalizeConcept(entity.normalizedValue.ifBlank { entity.value })
            when (entity.type) {
                "topic" -> categoryFromTopic(normalizedValue)?.let {
                    scores += CategoryScore(it, (0.48f + entity.confidence * 0.32f).coerceAtMost(0.78f), "topic $normalizedValue")
                }
                "visual_label", "visual_object" -> categoryFromVisualConcept(normalizedValue)?.let {
                    scores += CategoryScore(it, (0.42f + entity.confidence * 0.38f).coerceAtMost(0.82f), "${entity.type} $normalizedValue")
                }
                "barcode_signal" -> scores += CategoryScore("qr_code", 0.56f, "barcode detected")
                "barcode_value_type" -> categoryFromBarcodeValue(entity.value)?.let {
                    scores += CategoryScore(it, 0.58f, "barcode value type ${entity.value}")
                }
                "face_count", "people_presence" -> scores += CategoryScore("people", entity.confidence.coerceAtLeast(0.55f), "face/people signal")
                "gstin" -> scores += CategoryScore("tax", 0.64f, "GSTIN entity")
                "otp" -> scores += CategoryScore("security", 0.62f, "OTP entity")
                "tracking_id" -> scores += CategoryScore("delivery", 0.66f, "tracking entity")
                "invoice_id" -> scores += CategoryScore("receipt", 0.64f, "invoice entity")
                "domain", "url" -> scores += CategoryScore("web", 0.5f, "web link")
                "social_handle", "hashtag" -> scores += CategoryScore(ScreenshotCategory.SOCIAL.value, 0.5f, "social handle/tag")
            }
        }
        context.labels.forEach { label ->
            val concept = normalizeConcept(label.label)
            categoryFromVisualConcept(concept)?.let {
                scores += CategoryScore(it, (0.4f + label.confidence * 0.36f).coerceAtMost(0.78f), "image label $concept")
            }
        }
        return scores
    }

    private fun mergeScores(scores: List<CategoryScore>): List<CategoryScore> {
        return scores
            .filter { it.category.isNotBlank() }
            .groupBy { normalizeCategory(it.category) }
            .map { (category, categoryScores) ->
                val best = categoryScores.maxBy { it.confidence }
                val reason = categoryScores
                    .sortedByDescending { it.confidence }
                    .map { it.reason }
                    .distinct()
                    .take(3)
                    .joinToString(" + ")
                best.copy(category = category, reason = reason)
            }
    }

    private fun confidence(base: Float, vararg signals: Boolean): Float {
        val count = signals.count { it }
        return (base + count * 0.06f).coerceIn(0.3f, 0.96f)
    }

    private fun reason(vararg parts: Pair<String, Boolean>): String {
        return parts
            .filter { it.second }
            .joinToString(" + ") { it.first }
            .ifBlank { "local category signal" }
    }

    private data class CategoryScore(
        val category: String,
        val confidence: Float,
        val reason: String,
    )

    private class CategoryContext(
        text: String,
        val entities: List<DetectedEntityDraft>,
        val labels: List<VisualLabelDraft>,
    ) {
        private val textHaystack = normalize(text)
        private val labelHaystack = normalize(labels.joinToString(" ") { it.label })
        private val entityValueHaystack = normalize(entities.joinToString(" ") { "${it.value} ${it.normalizedValue}" })

        fun hasEntity(vararg types: String): Boolean {
            val typeSet = types.toSet()
            return entities.any { it.type in typeSet }
        }

        fun hasEntityValue(type: String, vararg phrases: String): Boolean {
            return entities
                .filter { it.type == type }
                .any { entity ->
                    val haystack = normalize("${entity.value} ${entity.normalizedValue}")
                    phrases.any { phrase -> containsPhrase(haystack, phrase) }
                }
        }

        fun hasVisualConcept(vararg phrases: String): Boolean {
            return entities
                .filter { it.type == "visual_label" || it.type == "visual_object" }
                .any { entity ->
                    val haystack = normalize("${entity.value} ${entity.normalizedValue}")
                    phrases.any { phrase -> containsPhrase(haystack, phrase) }
                } ||
                phrases.any { phrase -> containsPhrase(labelHaystack, phrase) }
        }

        fun hasTextAny(vararg phrases: String): Boolean {
            return phrases.any { phrase ->
                containsPhrase(textHaystack, phrase) ||
                    containsPhrase(labelHaystack, phrase) ||
                    containsPhrase(entityValueHaystack, phrase)
            }
        }
    }

    companion object {
        private const val MAX_CATEGORIES_PER_SCREENSHOT = 10
        private val WHITESPACE = Regex("""\s+""")
        private val CATEGORY_SEPARATOR = Regex("""[^a-z0-9]+""")
        private val CATEGORY_TRIM = Regex("""^_+|_+$""")
        private val LOW_VALUE_DYNAMIC_CATEGORIES = setOf(
            "text",
            "font",
            "image",
            "screenshot",
            "rectangle",
            "number",
            "product",
            "material_property",
            "electronic_device",
        )
        private val VISUAL_CATEGORY_MAP = mapOf(
            "food" to "food",
            "restaurant" to "food",
            "dish" to "food",
            "meal" to "food",
            "fashion good" to "fashion",
            "clothing" to "fashion",
            "shoe" to "fashion",
            "home good" to "home",
            "furniture" to "home",
            "place" to "places",
            "plant" to "plants",
            "flower" to "plants",
            "person" to "people",
            "people" to "people",
            "face" to "people",
            "document" to ScreenshotCategory.DOCUMENTS.value,
            "receipt" to "receipt",
            "invoice" to "receipt",
            "map" to ScreenshotCategory.MAPS.value,
            "vehicle" to "transport",
            "car" to "transport",
            "train" to "transport",
            "airplane" to "transport",
            "medicine" to "health",
            "hospital" to "health",
            "doctor" to "health",
            "fitness" to "health",
            "news" to "news",
            "music" to ScreenshotCategory.MEDIA.value,
            "video" to ScreenshotCategory.MEDIA.value,
            "poster" to ScreenshotCategory.MEDIA.value,
        )
        private val TOPIC_CATEGORY_MAP = mapOf(
            "ai" to ScreenshotCategory.AI_NEWS.value,
            "artificial intelligence" to ScreenshotCategory.AI_NEWS.value,
            "llm" to ScreenshotCategory.AI_NEWS.value,
            "openai" to ScreenshotCategory.AI_NEWS.value,
            "machine learning" to ScreenshotCategory.AI_NEWS.value,
            "startup" to "startup",
            "funding" to "funding",
            "crypto" to "crypto",
            "stock" to "investing",
            "market" to "investing",
            "health" to "health",
            "fitness" to "health",
            "medicine" to "health",
            "doctor" to "health",
            "hospital" to "health",
            "travel" to ScreenshotCategory.BOOKING_TRAVEL.value,
            "visa" to ScreenshotCategory.BOOKING_TRAVEL.value,
            "news" to "news",
            "job" to "jobs",
            "career" to "jobs",
            "education" to "education",
            "course" to "education",
            "exam" to "education",
            "event" to "event",
            "subscription" to "subscription",
            "renewal" to "subscription",
            "refund" to "refund",
            "deadline" to "pending_action",
        )

        private fun categoryFromVisualConcept(value: String): String? {
            val normalized = normalizeConcept(value)
            val mapped = VISUAL_CATEGORY_MAP[normalized]
            val category = mapped ?: normalized.takeIf {
                it.length in 3..32 &&
                    it !in LOW_VALUE_DYNAMIC_CATEGORIES &&
                    !it.contains(" ")
            }
            return category?.let(::normalizeCategory)
        }

        private fun categoryFromTopic(value: String): String? {
            val normalized = normalizeConcept(value)
            return (TOPIC_CATEGORY_MAP[normalized] ?: normalized.takeIf { it.length in 3..32 })
                ?.let(::normalizeCategory)
        }

        private fun categoryFromBarcodeValue(value: String): String? {
            return when (normalizeConcept(value)) {
                "url" -> "qr_link"
                "wifi" -> "wifi"
                "contact info" -> "contact"
                "calendar event" -> "event"
                "driver license" -> ScreenshotCategory.IDENTITY_DOCS.value
                else -> null
            }
        }

        private fun normalize(value: String): String {
            return value
                .lowercase(Locale.US)
                .replace(WHITESPACE, " ")
                .trim()
        }

        private fun normalizeConcept(value: String): String {
            return normalize(value)
                .replace('_', ' ')
                .replace(WHITESPACE, " ")
                .trim()
        }

        private fun normalizeCategory(value: String): String {
            return normalizeConcept(value)
                .replace(CATEGORY_SEPARATOR, "_")
                .replace(CATEGORY_TRIM, "")
                .ifBlank { ScreenshotCategory.UNKNOWN.value }
        }

        private fun containsPhrase(haystack: String, phrase: String): Boolean {
            if (haystack.isBlank() || phrase.isBlank()) return false
            val normalizedPhrase = normalize(phrase)
            val pattern = phraseRegex(normalizedPhrase)
            return pattern.containsMatchIn(haystack)
        }

        private fun phraseRegex(phrase: String): Regex {
            val escaped = phrase
                .split(WHITESPACE)
                .joinToString("""\s+""") { Regex.escape(it) }
            val prefix = if (phrase.firstOrNull()?.isLetterOrDigit() == true) {
                """(?<![\p{L}\p{Nd}])"""
            } else {
                ""
            }
            val suffix = if (phrase.lastOrNull()?.isLetterOrDigit() == true) {
                """(?![\p{L}\p{Nd}])"""
            } else {
                ""
            }
            return Regex(prefix + escaped + suffix, RegexOption.IGNORE_CASE)
        }
    }
}
