package com.askmyscreenshots.skill.extract

import com.askmyscreenshots.skill.ml.BarcodeDraft
import com.askmyscreenshots.skill.ml.DetectedObjectDraft
import com.askmyscreenshots.skill.ml.DetectedObjectLabelDraft
import com.askmyscreenshots.skill.ml.DetectedEntityDraft
import com.askmyscreenshots.skill.ml.FaceDraft
import com.askmyscreenshots.skill.ml.VisualLabelDraft
import java.util.Locale

class LocalEntityExtractor {
    fun extract(
        ocrText: String,
        barcodes: List<BarcodeDraft> = emptyList(),
        displayName: String? = null,
        relativePath: String? = null,
        bucketName: String? = null,
        visualLabels: List<VisualLabelDraft> = emptyList(),
        detectedObjects: List<DetectedObjectDraft> = emptyList(),
        faces: List<FaceDraft> = emptyList(),
    ): List<DetectedEntityDraft> {
        val rawSources = buildList {
            add(TextSource("OCR", ocrText))
            displayName?.let { add(TextSource("FILENAME", it)) }
            relativePath?.let { add(TextSource("PATH", it)) }
            bucketName?.let { add(TextSource("BUCKET", it)) }
            barcodes.mapNotNull { it.rawValue ?: it.displayValue }
                .forEach { add(TextSource("BARCODE", it)) }
        }
        val found = linkedMapOf<String, DetectedEntityDraft>()
        fun putBest(entity: DetectedEntityDraft) {
            val key = "${entity.type}:${entity.normalizedValue}"
            val existing = found[key]
            if (existing == null || entity.confidence > existing.confidence) {
                found[key] = entity
            }
        }
        rawSources.forEach { source ->
            extractFromSource(source).forEach { entity ->
                putBest(entity)
            }
        }
        barcodeMetadataEntities(barcodes).forEach(::putBest)
        visualLabelEntities(visualLabels).forEach(::putBest)
        objectLabelEntities(detectedObjects).forEach(::putBest)
        faceEntities(faces).forEach(::putBest)
        metadataEntities(relativePath = relativePath, bucketName = bucketName).forEach(::putBest)
        return found.values.sortedWith(
            compareByDescending<DetectedEntityDraft> { it.isSensitive }
                .thenBy { it.type }
                .thenBy { it.normalizedValue },
        )
    }

    private fun extractFromSource(source: TextSource): List<DetectedEntityDraft> {
        val text = source.text
        return buildList {
            regexEntities(text, source.name, EntityPattern.EMAIL)
            regexEntities(text, source.name, EntityPattern.URL)
            regexEntities(text, source.name, EntityPattern.UPI_ID)
            regexEntities(text, source.name, EntityPattern.PAN)
            regexEntities(text, source.name, EntityPattern.IFSC)
            regexEntities(text, source.name, EntityPattern.AADHAAR)
            regexEntities(text, source.name, EntityPattern.PHONE)
            regexEntities(text, source.name, EntityPattern.AMOUNT)
            regexEntities(text, source.name, EntityPattern.DATE)
            regexEntities(text, source.name, EntityPattern.BOOKING_ID)
            regexEntities(text, source.name, EntityPattern.ORDER_ID)
            regexEntities(text, source.name, EntityPattern.FLIGHT_HINT)
            regexEntities(text, source.name, EntityPattern.TRAIN_HINT)
            regexEntities(text, source.name, EntityPattern.APP_HINT)
            regexEntities(text, source.name, EntityPattern.TOPIC)
            regexEntities(text, source.name, EntityPattern.OTP)
            regexEntities(text, source.name, EntityPattern.TRANSACTION_ID)
            regexEntities(text, source.name, EntityPattern.INVOICE_ID)
            regexEntities(text, source.name, EntityPattern.TRACKING_ID)
            regexEntities(text, source.name, EntityPattern.GSTIN)
            regexEntities(text, source.name, EntityPattern.PASSPORT)
            regexEntities(text, source.name, EntityPattern.PINCODE)
            regexEntities(text, source.name, EntityPattern.SOCIAL_HANDLE)
            regexEntities(text, source.name, EntityPattern.HASHTAG)
            regexEntities(text, source.name, EntityPattern.EVENT_TIME)
            addAll(cardCandidates(text, source.name))
            addAll(accountCandidates(text, source.name))
            addAll(nameCandidates(text, source.name))
            addAll(paymentCounterpartyCandidates(text, source.name))
            addAll(domainCandidates(text, source.name))
        }
    }

    private fun MutableList<DetectedEntityDraft>.regexEntities(
        text: String,
        source: String,
        pattern: EntityPattern,
    ) {
        pattern.regex.findAll(text).forEach { match ->
            val value = match.value.trim().trim(',', '.', ':', ';')
            if (value.isNotBlank()) {
                add(
                    DetectedEntityDraft(
                        type = pattern.type,
                        value = value,
                        normalizedValue = pattern.normalize(value),
                        source = source,
                        confidence = pattern.confidence,
                        isSensitive = pattern.sensitive,
                        evidence = nearbyEvidence(text, match.range),
                    ),
                )
            }
        }
    }

    private fun cardCandidates(text: String, source: String): List<DetectedEntityDraft> {
        return DIGIT_SEQUENCE.findAll(text).mapNotNull { match ->
            val digits = match.value.filter(Char::isDigit)
            if (digits.length in 13..19 && luhnValid(digits)) {
                DetectedEntityDraft(
                    type = "card_number",
                    value = match.value.trim(),
                    normalizedValue = digits,
                    source = source,
                    confidence = 0.92f,
                    isSensitive = true,
                    evidence = nearbyEvidence(text, match.range),
                )
            } else {
                null
            }
        }.toList()
    }

    private fun accountCandidates(text: String, source: String): List<DetectedEntityDraft> {
        val accountWords = Regex("""(?i)\b(account|a/c|acct|bank)\b.{0,24}?\b([0-9][0-9\-\s]{7,20}[0-9])\b""")
        return accountWords.findAll(text).map { match ->
            val value = match.groupValues.getOrNull(2).orEmpty().trim()
            DetectedEntityDraft(
                type = "account_number",
                value = value,
                normalizedValue = value.filter(Char::isDigit),
                source = source,
                confidence = 0.76f,
                isSensitive = true,
                evidence = nearbyEvidence(text, match.range),
            )
        }.filter { it.normalizedValue.length >= 8 }.toList()
    }

    private fun nameCandidates(text: String, source: String): List<DetectedEntityDraft> {
        val explicitName = Regex("""(?im)\b(name|passenger|customer|traveller|traveler)\s*[:\-]\s*([A-Z][A-Za-z.'\-]+(?:\s+[A-Z][A-Za-z.'\-]+){0,3})""")
        return explicitName.findAll(text).map { match ->
            val value = match.groupValues.getOrNull(2).orEmpty().trim()
            DetectedEntityDraft(
                type = "person_name",
                value = value,
                normalizedValue = value.lowercase(),
                source = source,
                confidence = 0.68f,
                isSensitive = true,
                evidence = nearbyEvidence(text, match.range),
            )
        }.filter { it.value.isNotBlank() }.toList()
    }

    private fun paymentCounterpartyCandidates(text: String, source: String): List<DetectedEntityDraft> {
        val pattern = Regex("""(?im)\b(?:paid|sent|transferred|received)\s+(?:to|from)\s+([A-Z][A-Za-z0-9&.'\- ]{2,40})""")
        return pattern.findAll(text).mapNotNull { match ->
            val value = match.groupValues.getOrNull(1).orEmpty()
                .trim()
                .trim(',', '.', ':', ';')
            if (value.isBlank()) {
                null
            } else {
                DetectedEntityDraft(
                    type = "counterparty",
                    value = value,
                    normalizedValue = value.cleanConcept(),
                    source = source,
                    confidence = 0.68f,
                    isSensitive = true,
                    evidence = nearbyEvidence(text, match.range),
                )
            }
        }.toList()
    }

    private fun domainCandidates(text: String, source: String): List<DetectedEntityDraft> {
        return DOMAIN.findAll(text).mapNotNull { match ->
            val value = match.value.lowercase(Locale.US).trimEnd('/', '.', ',')
            if (value.count { it == '.' } < 1) {
                null
            } else {
                DetectedEntityDraft(
                    type = "domain",
                    value = value,
                    normalizedValue = value.removePrefix("www."),
                    source = source,
                    confidence = 0.76f,
                    isSensitive = false,
                    evidence = nearbyEvidence(text, match.range),
                )
            }
        }.toList()
    }

    private fun barcodeMetadataEntities(barcodes: List<BarcodeDraft>): List<DetectedEntityDraft> {
        return barcodes.flatMapIndexed { index, barcode ->
            buildList {
                add(
                    DetectedEntityDraft(
                        type = "barcode_signal",
                        value = "barcode ${index + 1}",
                        normalizedValue = "barcode",
                        source = "MLKIT_BARCODE",
                        confidence = 0.64f,
                        isSensitive = true,
                        evidence = "format=${barcode.format} valueType=${barcode.valueType}",
                    ),
                )
                add(
                    DetectedEntityDraft(
                        type = "barcode_format",
                        value = barcodeFormatLabel(barcode.format),
                        normalizedValue = "barcode_format:${barcode.format}",
                        source = "MLKIT_BARCODE",
                        confidence = 0.7f,
                        isSensitive = false,
                        evidence = "format=${barcode.format}",
                    ),
                )
                add(
                    DetectedEntityDraft(
                        type = "barcode_value_type",
                        value = barcodeValueTypeLabel(barcode.valueType),
                        normalizedValue = "barcode_value_type:${barcode.valueType}",
                        source = "MLKIT_BARCODE",
                        confidence = 0.7f,
                        isSensitive = false,
                        evidence = "valueType=${barcode.valueType}",
                    ),
                )
            }
        }
    }

    private fun visualLabelEntities(labels: List<VisualLabelDraft>): List<DetectedEntityDraft> {
        return labels
            .filter { it.confidence >= VISUAL_LABEL_ENTITY_MIN_CONFIDENCE }
            .sortedByDescending { it.confidence }
            .mapNotNull { label ->
                val cleaned = label.label.cleanConcept()
                if (!isUsefulVisualConcept(cleaned)) {
                    null
                } else {
                    DetectedEntityDraft(
                        type = "visual_label",
                        value = label.label,
                        normalizedValue = cleaned,
                        source = "MLKIT_IMAGE_LABEL",
                        confidence = label.confidence.coerceIn(0f, 1f),
                        isSensitive = cleaned in SENSITIVE_VISUAL_CONCEPTS,
                        evidence = "labelIndex=${label.labelIndex ?: -1}",
                    )
                }
            }
            .take(16)
    }

    private fun objectLabelEntities(objects: List<DetectedObjectDraft>): List<DetectedEntityDraft> {
        return objects
            .flatMap { detectedObject ->
                detectedObject.labels.map { label -> detectedObject to label }
            }
            .filter { (detectedObject, label) ->
                label.confidence >= OBJECT_LABEL_ENTITY_MIN_CONFIDENCE || detectedObject.areaRatio >= LARGE_OBJECT_AREA_RATIO
            }
            .sortedWith(
                compareByDescending<Pair<DetectedObjectDraft, DetectedObjectLabelDraft>> { it.second.confidence }
                    .thenByDescending { it.first.areaRatio },
            )
            .mapNotNull { (detectedObject, label) ->
                val cleaned = label.label.cleanConcept()
                if (!isUsefulVisualConcept(cleaned)) {
                    null
                } else {
                    DetectedEntityDraft(
                        type = "visual_object",
                        value = label.label,
                        normalizedValue = cleaned,
                        source = "MLKIT_OBJECT_DETECTION",
                        confidence = (label.confidence + detectedObject.areaRatio.coerceIn(0f, 1f) * 0.18f).coerceIn(0f, 1f),
                        isSensitive = cleaned in SENSITIVE_VISUAL_CONCEPTS,
                        evidence = "objectIndex=${detectedObject.objectIndex} labelIndex=${label.labelIndex ?: -1} area=${String.format(Locale.US, "%.3f", detectedObject.areaRatio)}",
                    )
                }
            }
            .take(16)
    }

    private fun faceEntities(faces: List<FaceDraft>): List<DetectedEntityDraft> {
        if (faces.isEmpty()) return emptyList()
        val count = faces.size
        return listOf(
            DetectedEntityDraft(
                type = "face_count",
                value = "$count face${if (count == 1) "" else "s"}",
                normalizedValue = "face_count:$count",
                source = "MLKIT_FACE",
                confidence = (0.55f + count.coerceAtMost(5) * 0.05f).coerceAtMost(0.85f),
                isSensitive = true,
                evidence = "faces=$count",
            ),
            DetectedEntityDraft(
                type = "people_presence",
                value = "people visible",
                normalizedValue = "people",
                source = "MLKIT_FACE",
                confidence = 0.68f,
                isSensitive = true,
                evidence = "faces=$count",
            ),
        )
    }

    private fun metadataEntities(
        relativePath: String?,
        bucketName: String?,
    ): List<DetectedEntityDraft> {
        return buildList {
            bucketName
                ?.takeIf { it.isNotBlank() }
                ?.let { bucket ->
                    add(
                        DetectedEntityDraft(
                            type = "source_bucket",
                            value = bucket,
                            normalizedValue = bucket.cleanConcept(),
                            source = "METADATA",
                            confidence = 0.48f,
                            isSensitive = false,
                            evidence = "bucketName",
                        ),
                    )
                }
            relativePath
                ?.split('/', '\\')
                ?.map { it.cleanConcept() }
                ?.filter { it.length >= 3 && it !in NOISY_PATH_PARTS }
                ?.distinct()
                ?.take(4)
                ?.forEach { part ->
                    add(
                        DetectedEntityDraft(
                            type = "path_hint",
                            value = part,
                            normalizedValue = part,
                            source = "METADATA",
                            confidence = 0.42f,
                            isSensitive = false,
                            evidence = "relativePath",
                        ),
                    )
                }
        }
    }

    private fun nearbyEvidence(text: String, range: IntRange): String {
        val start = (range.first - 36).coerceAtLeast(0)
        val end = (range.last + 36).coerceAtMost(text.lastIndex)
        if (start > end) return ""
        return text.substring(start..end)
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun luhnValid(digits: String): Boolean {
        var sum = 0
        var alternate = false
        for (index in digits.length - 1 downTo 0) {
            var n = digits[index].digitToInt()
            if (alternate) {
                n *= 2
                if (n > 9) n -= 9
            }
            sum += n
            alternate = !alternate
        }
        return sum > 0 && sum % 10 == 0
    }

    private data class TextSource(
        val name: String,
        val text: String,
    )

    private enum class EntityPattern(
        val type: String,
        val regex: Regex,
        val confidence: Float,
        val sensitive: Boolean,
        val normalize: (String) -> String,
    ) {
        EMAIL(
            "email",
            Regex("""(?i)\b[A-Z0-9._%+\-]+@[A-Z0-9.\-]+\.[A-Z]{2,}\b"""),
            0.95f,
            true,
            { it.lowercase() },
        ),
        URL(
            "url",
            Regex("""(?i)\b(https?://|www\.)[^\s<>()]+"""),
            0.9f,
            false,
            { it.lowercase().trimEnd('/') },
        ),
        UPI_ID(
            "upi_id",
            Regex("""(?i)\b[a-z0-9.\-_]{2,256}@[a-z][a-z0-9.\-_]{2,64}\b"""),
            0.94f,
            true,
            { it.lowercase() },
        ),
        PAN(
            "pan",
            Regex("""\b[A-Z]{5}[0-9]{4}[A-Z]\b"""),
            0.96f,
            true,
            { it.uppercase() },
        ),
        IFSC(
            "ifsc",
            Regex("""\b[A-Z]{4}0[A-Z0-9]{6}\b"""),
            0.94f,
            true,
            { it.uppercase() },
        ),
        AADHAAR(
            "aadhaar",
            Regex("""\b[2-9][0-9]{3}\s?[0-9]{4}\s?[0-9]{4}\b"""),
            0.82f,
            true,
            { it.filter(Char::isDigit) },
        ),
        PHONE(
            "phone",
            Regex("""(?i)(?<!\d)(?:\+?91[\-\s]?)?[6-9][0-9]{4}[\-\s]?[0-9]{5}(?!\d)"""),
            0.82f,
            true,
            { it.filter(Char::isDigit).takeLast(10) },
        ),
        AMOUNT(
            "amount",
            Regex("""(?i)(?:rs\.?|inr|₹|\$|usd)\s?[0-9][0-9,]*(?:\.[0-9]{1,2})?"""),
            0.86f,
            true,
            { it.lowercase().replace(",", "").replace(" ", "") },
        ),
        DATE(
            "date",
            Regex("""(?i)\b(?:[0-3]?\d[\/\-.][01]?\d[\/\-.](?:20)?\d{2}|(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+[0-3]?\d,?\s+(?:20)?\d{2})\b"""),
            0.72f,
            false,
            { it.lowercase() },
        ),
        BOOKING_ID(
            "booking_id",
            Regex("""(?i)\b(?:booking|pnr|voucher|reservation|confirmation|trip|ticket)\s*(?:id|no|number|#)?\s*[:\-]?\s*([A-Z0-9][A-Z0-9\-]{5,20})\b"""),
            0.76f,
            true,
            { it.substringAfterLast(' ').uppercase() },
        ),
        ORDER_ID(
            "order_id",
            Regex("""(?i)\b(?:order|invoice)\s*(?:id|no|number|#)?\s*[:\-]?\s*([A-Z0-9][A-Z0-9\-]{5,24})\b"""),
            0.74f,
            true,
            { it.substringAfterLast(' ').uppercase() },
        ),
        FLIGHT_HINT(
            "flight_hint",
            Regex("""(?i)\b(?:flight|boarding|gate|terminal|pnr|airline|indigo|air india|vistara|spicejet)\b"""),
            0.7f,
            false,
            { it.lowercase() },
        ),
        TRAIN_HINT(
            "train_hint",
            Regex("""(?i)\b(?:train|irctc|coach|berth|platform|pnr|railway)\b"""),
            0.7f,
            false,
            { it.lowercase() },
        ),
        APP_HINT(
            "app",
            Regex("""(?i)\b(?:whatsapp|telegram|signal|instagram|insta|facebook|linkedin|threads|x|twitter|gmail|outlook|paytm|phonepe|gpay|google pay|amazon|flipkart|myntra|meesho|ajio|swiggy|zomato|uber|ola|maps|google maps|chrome|youtube|spotify|netflix|bookmyshow|makemytrip|airbnb|irctc|slack|notion|github)\b"""),
            0.76f,
            false,
            { it.lowercase() },
        ),
        TOPIC(
            "topic",
            Regex("""(?i)\b(?:ai|artificial intelligence|llm|openai|machine learning|startup|funding|crypto|stock|market|health|fitness|travel|visa|medicine|doctor|hospital|news|job|career|real estate|property|education|course|exam|event|subscription|renewal|refund|deadline)\b"""),
            0.64f,
            false,
            { it.lowercase() },
        ),
        OTP(
            "otp",
            Regex("""(?i)\b(?:otp|one[ -]?time password|verification code|login code)\s*(?:is|:|\-)?\s*([0-9]{4,8})\b"""),
            0.88f,
            true,
            { it.filter(Char::isDigit) },
        ),
        TRANSACTION_ID(
            "transaction_id",
            Regex("""(?i)\b(?:utr|transaction|txn|reference|ref)\s*(?:id|no|number|#)?\s*[:\-]?\s*([A-Z0-9][A-Z0-9\-]{5,32})\b"""),
            0.82f,
            true,
            { it.substringAfterLast(' ').uppercase() },
        ),
        INVOICE_ID(
            "invoice_id",
            Regex("""(?i)\b(?:invoice|bill)\s*(?:id|no|number|#)?\s*[:\-]?\s*([A-Z0-9][A-Z0-9\-\/]{4,28})\b"""),
            0.78f,
            true,
            { it.substringAfterLast(' ').uppercase() },
        ),
        TRACKING_ID(
            "tracking_id",
            Regex("""(?i)\b(?:tracking|awb|shipment|waybill)\s*(?:id|no|number|#)?\s*[:\-]?\s*([A-Z0-9][A-Z0-9\-]{6,32})\b"""),
            0.78f,
            true,
            { it.substringAfterLast(' ').uppercase() },
        ),
        GSTIN(
            "gstin",
            Regex("""\b[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]\b"""),
            0.9f,
            true,
            { it.uppercase() },
        ),
        PASSPORT(
            "passport",
            Regex("""(?i)\b(?:passport\s*(?:no|number)?\s*[:\-]?\s*)?([A-Z][0-9]{7})\b"""),
            0.78f,
            true,
            { it.substringAfterLast(' ').uppercase() },
        ),
        PINCODE(
            "pincode",
            Regex("""(?i)\b(?:pin|pincode|postal code|zip)\s*[:\-]?\s*([1-9][0-9]{5})\b"""),
            0.72f,
            true,
            { it.filter(Char::isDigit) },
        ),
        SOCIAL_HANDLE(
            "social_handle",
            Regex("""(?i)(?<![\w.])@[a-z0-9_][a-z0-9_.]{2,29}\b"""),
            0.66f,
            false,
            { it.lowercase() },
        ),
        HASHTAG(
            "hashtag",
            Regex("""(?i)(?<![\w])#[a-z0-9_]{2,48}\b"""),
            0.62f,
            false,
            { it.lowercase() },
        ),
        EVENT_TIME(
            "time",
            Regex("""(?i)\b(?:[01]?\d|2[0-3])[:.][0-5]\d\s?(?:am|pm)?\b|\b(?:[1-9]|1[0-2])\s?(?:am|pm)\b"""),
            0.58f,
            false,
            { it.lowercase().replace(" ", "") },
        ),
    }

    companion object {
        private val DIGIT_SEQUENCE = Regex("""\b[0-9][0-9\-\s]{11,25}[0-9]\b""")
        private val DOMAIN = Regex("""(?i)\b(?:[a-z0-9-]+\.)+[a-z]{2,}\b""")
        private const val VISUAL_LABEL_ENTITY_MIN_CONFIDENCE = 0.55f
        private const val OBJECT_LABEL_ENTITY_MIN_CONFIDENCE = 0.45f
        private const val LARGE_OBJECT_AREA_RATIO = 0.18f
        private val NOISY_VISUAL_CONCEPTS = setOf(
            "text",
            "font",
            "screenshot",
            "image",
            "photograph",
            "snapshot",
            "rectangle",
            "pattern",
            "graphics",
            "product",
            "brand",
            "logo",
            "number",
            "material property",
            "electronic device",
        )
        private val SENSITIVE_VISUAL_CONCEPTS = setOf("person", "people", "face", "human", "child")
        private val NOISY_PATH_PARTS = setOf(
            "pictures",
            "screenshots",
            "screenshot",
            "dcim",
            "camera",
            "images",
        )

        private fun isUsefulVisualConcept(value: String): Boolean {
            return value.length >= 3 && value !in NOISY_VISUAL_CONCEPTS
        }

        private fun barcodeFormatLabel(format: Int): String {
            return when (format) {
                256 -> "qr_code"
                1 -> "code_128"
                2 -> "code_39"
                4 -> "code_93"
                8 -> "codabar"
                16 -> "data_matrix"
                32 -> "ean_13"
                64 -> "ean_8"
                128 -> "itf"
                512 -> "upc_a"
                1024 -> "upc_e"
                2048 -> "pdf417"
                4096 -> "aztec"
                else -> "barcode_format_$format"
            }
        }

        private fun barcodeValueTypeLabel(valueType: Int): String {
            return when (valueType) {
                1 -> "contact_info"
                2 -> "email"
                3 -> "isbn"
                4 -> "phone"
                5 -> "product"
                6 -> "sms"
                7 -> "text"
                8 -> "url"
                9 -> "wifi"
                10 -> "geo"
                11 -> "calendar_event"
                12 -> "driver_license"
                else -> "barcode_value_type_$valueType"
            }
        }
    }
}

private fun String.cleanConcept(): String {
    return lowercase(Locale.US)
        .replace('_', ' ')
        .replace(Regex("""\s+"""), " ")
        .trim()
}
