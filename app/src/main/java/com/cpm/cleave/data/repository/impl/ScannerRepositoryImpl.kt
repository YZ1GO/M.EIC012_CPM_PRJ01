package com.cpm.cleave.data.repository.impl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.cpm.cleave.domain.repository.contracts.IScannerRepository
import com.cpm.cleave.model.ReceiptItem
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class ScannerRepositoryImpl : IScannerRepository {

    override fun extractJoinCode(rawValue: String): String? {
        if (rawValue.isBlank()) return null

        val normalized = rawValue.trim().uppercase(Locale.ROOT)

        val queryCode = Regex("[?&]JOINCODE=([A-Z0-9]+)")
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)

        val candidate = queryCode ?: normalized

        val codeMatch = JOIN_CODE_REGEX.find(candidate)
        return codeMatch?.value
    }

    override suspend fun extractReceiptTotal(imageBytes: ByteArray): Result<Double?> {
        return runCatching {
            if (imageBytes.isEmpty()) return@runCatching null

            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: throw IllegalArgumentException("Could not decode receipt image")

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val candidates = buildOcrCandidates(bitmap)
                val mergedText = buildString {
                    candidates.forEach { candidate ->
                        appendLine(recognizer.process(InputImage.fromBitmap(candidate, 0)).awaitTaskResult().text)
                    }
                }
                parseLikelyTotal(mergedText)
            } finally {
                recognizer.close()
            }
        }
    }

    override suspend fun extractReceiptItems(imageBytes: ByteArray): Result<List<ReceiptItem>> {
        return runCatching {
            if (imageBytes.isEmpty()) return@runCatching emptyList()

            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: throw IllegalArgumentException("Could not decode receipt image")

            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val candidates = buildOcrCandidates(bitmap)
                val parsedPerCandidate = mutableListOf<List<ReceiptItem>>()

                if (OCR_DEBUG_ENABLED) {
                    Log.d(OCR_DEBUG_TAG, "extractReceiptItems: candidates=${candidates.size}, base=${bitmap.width}x${bitmap.height}, bytes=${imageBytes.size}")
                }

                // Candidate 0 is always the full image; trust it first for item completeness.
                val fullVisionText = recognizer.process(InputImage.fromBitmap(candidates.first(), 0)).awaitTaskResult()
                val fullGeometryItems = parseReceiptItemsFromVisionText(fullVisionText)
                val fullTextItems = parseReceiptItems(fullVisionText.text)
                val fullExpectedCount = extractExpectedItemCount(
                    fullVisionText.text.lines().map { it.trim() }.filter { it.isNotBlank() }
                )
                val fullItems = chooseBestCandidateItems(
                    geometryItems = fullGeometryItems,
                    textItems = fullTextItems,
                    expectedCount = fullExpectedCount
                ).withDefaultQuantity()
                if (OCR_DEBUG_ENABLED) {
                    Log.d(
                        OCR_DEBUG_TAG,
                        "candidate[0]=full lines=${fullVisionText.text.lines().size} chars=${fullVisionText.text.length} geometry=${fullGeometryItems.size} text=${fullTextItems.size} expected=${fullExpectedCount ?: -1} items=${fullItems.size} sample=${summarizeItems(fullItems)}"
                    )
                    Log.d(OCR_DEBUG_TAG, "candidate[0] preview:\n${previewText(fullVisionText.text)}")
                }
                parsedPerCandidate += fullItems

                candidates.drop(1).forEachIndexed { idx, candidate ->
                    val visionText = recognizer.process(InputImage.fromBitmap(candidate, 0)).awaitTaskResult()
                    val itemsFromGeometry = parseReceiptItemsFromVisionText(visionText)
                    val itemsFromText = parseReceiptItems(visionText.text)
                    val expectedCount = extractExpectedItemCount(
                        visionText.text.lines().map { it.trim() }.filter { it.isNotBlank() }
                    )
                    val selected = chooseBestCandidateItems(
                        geometryItems = itemsFromGeometry,
                        textItems = itemsFromText,
                        expectedCount = expectedCount
                    ).withDefaultQuantity()
                    parsedPerCandidate += selected
                    if (OCR_DEBUG_ENABLED) {
                        Log.d(
                            OCR_DEBUG_TAG,
                            "candidate[${idx + 1}] size=${candidate.width}x${candidate.height} lines=${visionText.text.lines().size} chars=${visionText.text.length} geometry=${itemsFromGeometry.size} text=${itemsFromText.size} expected=${expectedCount ?: -1} selected=${selected.size} sample=${summarizeItems(selected)}"
                        )
                    }
                }

                val nonEmpty = parsedPerCandidate.filter { it.isNotEmpty() }
                if (nonEmpty.isEmpty()) {
                    // Fallback only when every candidate failed.
                    val mergedText = buildString {
                        candidates.forEach { candidate ->
                            appendLine(recognizer.process(InputImage.fromBitmap(candidate, 0)).awaitTaskResult().text)
                        }
                    }
                    val mergedItems = parseReceiptItems(mergedText)
                    if (OCR_DEBUG_ENABLED) {
                        Log.d(OCR_DEBUG_TAG, "all-candidates-empty -> merged fallback items=${mergedItems.size} sample=${summarizeItems(mergedItems)}")
                        Log.d(OCR_DEBUG_TAG, "merged preview:\n${previewText(mergedText)}")
                    }
                    return@runCatching mergedItems.withDefaultQuantity()
                }

                val bestSingle = nonEmpty.maxByOrNull { scoreSelectedCandidate(it) }.orEmpty()

                if (bestSingle.size >= 2) {
                    if (OCR_DEBUG_ENABLED) {
                        Log.d(
                            OCR_DEBUG_TAG,
                            "selected bestSingle size=${bestSingle.size} score=${scoreSelectedCandidate(bestSingle)} sample=${summarizeItems(bestSingle)}"
                        )
                    }
                    return@runCatching bestSingle.withDefaultQuantity().take(MAX_RECEIPT_ITEMS)
                }

                // Merge unique items across candidates when each candidate alone is sparse.
                val merged = nonEmpty
                    .flatten()
                    .distinctBy { item -> item.name.lowercase(Locale.ROOT) to "%.2f".format(Locale.US, item.amount) }
                    .take(MAX_RECEIPT_ITEMS)
                if (OCR_DEBUG_ENABLED) {
                    Log.d(
                        OCR_DEBUG_TAG,
                        "selected merged sparse candidates size=${merged.size} score=${scoreSelectedCandidate(merged)} sample=${summarizeItems(merged)}"
                    )
                }
                merged.withDefaultQuantity().take(MAX_RECEIPT_ITEMS)
            } finally {
                recognizer.close()
            }
        }
    }

    private fun List<ReceiptItem>.withDefaultQuantity(): List<ReceiptItem> {
        return map { item ->
            val qty = item.quantity?.takeIf { it > 0.0 } ?: 1.0
            item.copy(quantity = qty, unitPrice = item.unitPrice?.takeIf { it > 0.0 })
        }
    }

    private fun parseReceiptItemsFromVisionText(visionText: Text): List<ReceiptItem> {
        val lines = collectVisionSpatialRows(visionText).ifEmpty { collectVisionLineRecords(visionText) }
        if (lines.isEmpty()) return emptyList()

        val header = detectHeaderFromVisionLines(lines)
        if (header == null) {
            return parseVisionRowsWithoutHeader(lines)
        }
        val items = mutableListOf<ReceiptItem>()
        var nonItemStreak = 0

        lines.drop(header.lineIndex + 1).forEach { line ->
            val lower = line.text.lowercase(Locale.ROOT)
            if (isFooterLine(lower)) {
                if (items.isNotEmpty()) return@forEach
                nonItemStreak++
                return@forEach
            }

            if (isDiscountLine(lower)) {
                nonItemStreak++
                return@forEach
            }

            val amountTokens = line.tokens.mapNotNull { token ->
                val amount = extractLastAmount(token.text) ?: return@mapNotNull null
                token.centerX to amount
            }
            if (amountTokens.isEmpty()) {
                nonItemStreak++
                return@forEach
            }

            val total = when {
                header.totalX != null -> amountTokens.minByOrNull { kotlin.math.abs(it.first - header.totalX) }?.second
                else -> amountTokens.maxByOrNull { it.first }?.second
            } ?: run {
                nonItemStreak++
                return@forEach
            }

            val qtyFromHeader = header.qtyX?.let { qx ->
                line.tokens
                    .minByOrNull { token -> kotlin.math.abs(token.centerX - qx) }
                    ?.let { parseQuantity(it.text) }
            }

            val qty = qtyFromHeader ?: inferQuantityFromTokens(
                tokens = line.tokens,
                cutoffX = header.totalX ?: Float.MAX_VALUE,
                targetX = header.qtyX
            )

            val unit = header.unitX?.let { ux ->
                line.tokens
                    .minByOrNull { token -> kotlin.math.abs(token.centerX - ux) }
                    ?.let { extractFirstAmount(it.text) }
            }

            val cutoffX = listOfNotNull(header.totalX, header.unitX).minOrNull() ?: Float.MAX_VALUE
            val description = line.tokens
                .filter { token ->
                    token.centerX < cutoffX &&
                        token.text.any { ch -> ch.isLetter() } &&
                        extractAllAmounts(token.text).isEmpty()
                }
                .joinToString(" ") { it.text }
                .replace(Regex("""\s+"""), " ")
                .trim()

            if (description.count { it.isLetter() } < 2 || total <= 0.0) {
                nonItemStreak++
                return@forEach
            }

            items += ReceiptItem(
                name = description.take(70),
                amount = total,
                quantity = qty,
                unitPrice = unit
            )
            nonItemStreak = 0
        }

        return items
            .ifEmpty { emptyList() }
            .distinctBy { it.name.lowercase(Locale.ROOT) to "%.2f".format(Locale.US, it.amount) }
            .take(MAX_RECEIPT_ITEMS)
    }

    private fun parseVisionRowsWithoutHeader(lines: List<VisionLineRecord>): List<ReceiptItem> {
        val items = mutableListOf<ReceiptItem>()
        var nonItemStreak = 0

        lines.forEach { line ->
            val lower = line.text.lowercase(Locale.ROOT)
            if (isFooterLine(lower) || isDiscountLine(lower)) {
                nonItemStreak++
                return@forEach
            }

            val amountTokens = line.tokens.mapNotNull { token ->
                val amount = extractLastAmount(token.text) ?: return@mapNotNull null
                token to amount
            }
            if (amountTokens.isEmpty()) {
                nonItemStreak++
                return@forEach
            }

            val rightMostAmountToken = amountTokens.maxByOrNull { it.first.centerX } ?: run {
                nonItemStreak++
                return@forEach
            }

            val total = rightMostAmountToken.second
            if (total <= 0.0) {
                nonItemStreak++
                return@forEach
            }

            val description = line.tokens
                .filter { token ->
                    token.centerX < rightMostAmountToken.first.centerX &&
                        token.text.any { ch -> ch.isLetter() } &&
                        extractAllAmounts(token.text).isEmpty()
                }
                .joinToString(" ") { it.text }
                .replace(Regex("""\s+"""), " ")
                .trim()

            if (description.count { it.isLetter() } < 2) {
                nonItemStreak++
                return@forEach
            }

            items += ReceiptItem(name = description.take(70), amount = total)
            nonItemStreak = 0
        }

        return items
            .distinctBy { it.name.lowercase(Locale.ROOT) to "%.2f".format(Locale.US, it.amount) }
            .take(MAX_RECEIPT_ITEMS)
    }

    private fun collectVisionLineRecords(visionText: Text): List<VisionLineRecord> {
        val records = mutableListOf<VisionLineRecord>()

        visionText.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                val tokens = if (line.elements.isNotEmpty()) {
                    line.elements.map { element ->
                        val centerX = element.boundingBox?.let { it.left + (it.width() / 2f) } ?: 0f
                        val centerY = element.boundingBox?.let { it.top + (it.height() / 2f) } ?: 0f
                        VisionToken(text = element.text.trim(), centerX = centerX, centerY = centerY)
                    }.filter { it.text.isNotBlank() }
                } else {
                    val split = line.text.split(Regex("""\s+"""))
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    split.mapIndexed { index, token ->
                        VisionToken(text = token, centerX = index.toFloat(), centerY = lineIndexFallback(line.text, index))
                    }
                }

                records += VisionLineRecord(text = line.text.trim(), tokens = tokens)
            }
        }

        return records.filter { it.text.isNotBlank() }
    }

    private fun collectVisionSpatialRows(visionText: Text): List<VisionLineRecord> {
        val tokens = mutableListOf<VisionToken>()

        visionText.textBlocks.forEach { block ->
            block.lines.forEach { line ->
                line.elements.forEach { element ->
                    val box = element.boundingBox ?: return@forEach
                    val text = element.text.trim()
                    if (text.isBlank()) return@forEach
                    tokens += VisionToken(
                        text = text,
                        centerX = box.left + (box.width() / 2f),
                        centerY = box.top + (box.height() / 2f)
                    )
                }
            }
        }

        if (tokens.isEmpty()) return emptyList()

        val sorted = tokens.sortedWith(compareBy<VisionToken> { it.centerY }.thenBy { it.centerX })
        val rows = mutableListOf<MutableList<VisionToken>>()
        val yTolerance = 18f

        sorted.forEach { token ->
            val targetRow = rows.lastOrNull()?.takeIf { row ->
                row.isNotEmpty() && kotlin.math.abs(row.first().centerY - token.centerY) <= yTolerance
            }
            if (targetRow != null) {
                targetRow += token
            } else {
                rows += mutableListOf(token)
            }
        }

        return rows.map { row ->
            val ordered = row.sortedBy { it.centerX }
            VisionLineRecord(
                text = ordered.joinToString(" ") { it.text }.replace(Regex("""\s+"""), " ").trim(),
                tokens = ordered
            )
        }.filter { it.text.isNotBlank() }
    }

    private fun lineIndexFallback(lineText: String, tokenIndex: Int): Float {
        return (lineText.length.coerceAtLeast(1) + tokenIndex).toFloat()
    }

    private fun detectHeaderFromVisionLines(lines: List<VisionLineRecord>): VisionHeaderMap? {
        val descKeywords = listOf("desc", "descr", "design", "produto", "product", "item", "artigo")
        val qtyKeywords = listOf("qtd", "qnt", "qty", "quant", "unid")
        val unitKeywords = listOf("unit", "preco", "price", "p/u", "unitario")
        val totalKeywords = listOf("total", "valor", "amount", "importe")

        lines.take(40).forEachIndexed { index, line ->
            var descX: Float? = null
            var qtyX: Float? = null
            var unitX: Float? = null
            var totalX: Float? = null

            line.tokens.forEach { token ->
                val t = token.text.lowercase(Locale.ROOT)
                if (descX == null && descKeywords.any { it in t }) descX = token.centerX
                if (qtyX == null && qtyKeywords.any { it in t }) qtyX = token.centerX
                if (unitX == null && unitKeywords.any { it in t }) unitX = token.centerX
                if (totalX == null && totalKeywords.any { it in t }) totalX = token.centerX
            }

            if (descX != null && (totalX != null || unitX != null)) {
                return VisionHeaderMap(
                    lineIndex = index,
                    descX = descX!!,
                    qtyX = qtyX,
                    unitX = unitX,
                    totalX = totalX
                )
            }
        }
        return null
    }

    private fun isDiscountLine(lower: String): Boolean {
        val keywords = listOf("discount", "desconto", "promo", "promocao", "coupon", "cupao", "poupanca")
        return keywords.any { it in lower }
    }

    private fun buildOcrCandidates(bitmap: Bitmap): List<Bitmap> {
        val candidates = mutableListOf<Bitmap>()
        candidates += bitmap

        // High-contrast binary pass helps separate thin digits on low-contrast receipts.
        candidates += buildAdaptiveBinaryBitmap(bitmap)

        // Long receipts often place the final total near the bottom.
        val h = bitmap.height
        val w = bitmap.width
        if (h > 400 && w > 300) {
            val bottomHalf = Bitmap.createBitmap(bitmap, 0, h / 2, w, h - (h / 2))
            val bottomThird = Bitmap.createBitmap(bitmap, 0, (h * 2) / 3, w, h - ((h * 2) / 3))
            candidates += bottomHalf
            candidates += bottomThird
        }

        // Upscale smaller captures to help OCR on tiny glyphs.
        if (maxOf(w, h) < 1800) {
            val scaled = Bitmap.createScaledBitmap(bitmap, w * 2, h * 2, true)
            candidates += scaled
        }

        return candidates
    }

    private fun buildAdaptiveBinaryBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val total = width * height
        if (total <= 0) return bitmap

        val src = IntArray(total)
        bitmap.getPixels(src, 0, width, 0, 0, width, height)

        val histogram = IntArray(256)
        src.forEach { px ->
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            histogram[luminance]++
        }

        val threshold = computeOtsuThreshold(histogram, total)
        val dst = IntArray(total)
        for (i in 0 until total) {
            val px = src[i]
            val alpha = (px ushr 24) and 0xFF
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            val bw = if (luminance >= threshold) 0xFF else 0x00
            dst[i] = (alpha shl 24) or (bw shl 16) or (bw shl 8) or bw
        }

        return Bitmap.createBitmap(dst, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun computeOtsuThreshold(histogram: IntArray, totalPixels: Int): Int {
        var sum = 0.0
        for (i in histogram.indices) {
            sum += i * histogram[i]
        }

        var sumBackground = 0.0
        var weightBackground = 0
        var maxVariance = -1.0
        var threshold = 128

        for (i in histogram.indices) {
            weightBackground += histogram[i]
            if (weightBackground == 0) continue

            val weightForeground = totalPixels - weightBackground
            if (weightForeground == 0) break

            sumBackground += i * histogram[i]
            val meanBackground = sumBackground / weightBackground
            val meanForeground = (sum - sumBackground) / weightForeground
            val betweenClassVariance =
                weightBackground.toDouble() * weightForeground.toDouble() *
                    (meanBackground - meanForeground) * (meanBackground - meanForeground)

            if (betweenClassVariance > maxVariance) {
                maxVariance = betweenClassVariance
                threshold = i
            }
        }

        return threshold.coerceIn(40, 215)
    }

    private fun parseLikelyTotal(rawText: String): Double? {
        if (rawText.isBlank()) return null

        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val amountRegex = Regex("""(?<!\d)(\d{1,3}(?:[\s.,]\d{3})*[.,]\d{2}|\d+[.,]\d{2})(?!\d)""")
        var best: Pair<Double, Int>? = null

        lines.forEachIndexed { index, line ->
            val lowerLine = line.lowercase(Locale.ROOT)
            val keywordScore = when {
                "grand total" in lowerLine -> 120
                "total" in lowerLine -> 100
                "amount due" in lowerLine || "to pay" in lowerLine -> 90
                "subtotal" in lowerLine -> 40
                else -> 10
            }

            amountRegex.findAll(line).forEach { match ->
                val parsed = parseAmount(match.value) ?: return@forEach
                val linePositionBonus = index
                val score = keywordScore + linePositionBonus
                if (best == null || score > best!!.second) {
                    best = parsed to score
                }
            }
        }

        if (best != null) return best!!.first

        // Fallback: choose the maximum decimal-looking value in the full text.
        return amountRegex.findAll(rawText)
            .mapNotNull { parseAmount(it.value) }
            .maxOrNull()
    }

    private fun parseAmount(raw: String): Double? {
        val token = normalizeAmountToken(raw)
        if (token.isEmpty()) return null

        val lastComma = token.lastIndexOf(',')
        val lastDot = token.lastIndexOf('.')
        val decimalSeparator = when {
            lastComma > lastDot -> ','
            lastDot > lastComma -> '.'
            else -> null
        }

        val normalized = when (decimalSeparator) {
            ',' -> token.replace(".", "").replace(',', '.')
            '.' -> token.replace(",", "")
            else -> token.replace(",", "").replace(".", "")
        }

        return normalized.toDoubleOrNull()
    }

    private fun normalizeAmountToken(raw: String): String {
        return raw
            .trim()
            .uppercase(Locale.ROOT)
            .replace("€", "")
            .replace("$", "")
            .replace('O', '0')
            .replace('D', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('S', '5')
            .replace('B', '8')
            .replace(Regex("""(?<=[.,])\s+"""), "")
            .replace(" ", "")
    }

    private fun parseReceiptItems(rawText: String): List<ReceiptItem> {
        if (rawText.isBlank()) return emptyList()

        val lines = rawText.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val expectedItemCount = extractExpectedItemCount(lines)

        val tableItems = parseItemsFromDetectedTable(lines)
        val sectionItems = parseItemsFromProductSection(lines, expectedItemCount)

        if (tableItems.isNotEmpty()) {
            val merged = (tableItems + sectionItems)
                .distinctBy { it.name.lowercase(Locale.ROOT) to "%.2f".format(Locale.US, it.amount) }
            if (OCR_DEBUG_ENABLED) {
                Log.d(
                    OCR_DEBUG_TAG,
                    "parseReceiptItems: tableItems=${tableItems.size}, sectionItems=${sectionItems.size}, expected=${expectedItemCount ?: -1}, merged=${merged.size}, sample=${summarizeItems(merged)}"
                )
            }
            val chosen = chooseByExpectedCount(merged, expectedItemCount)
            return chosen
                .map { it.copy(name = it.name.replace(Regex("""\s+"""), " ").trim()) }
                .filter { it.name.length >= 2 && it.amount > 0.0 }
                .distinctBy { it.name.lowercase(Locale.ROOT) to "%.2f".format(Locale.US, it.amount) }
                .take(MAX_RECEIPT_ITEMS)
        }

        val splitSectionItems = parseItemsFromSplitSections(lines)
        if (splitSectionItems.isNotEmpty()) {
            if (OCR_DEBUG_ENABLED) {
                Log.d(OCR_DEBUG_TAG, "parseReceiptItems: using split-sections fallback items=${splitSectionItems.size} sample=${summarizeItems(splitSectionItems)}")
            }
            val chosen = chooseByExpectedCount(splitSectionItems + sectionItems, expectedItemCount)
            return chosen
                .map { it.copy(name = it.name.replace(Regex("""\s+"""), " ").trim()) }
                .filter { it.name.length >= 2 && it.amount > 0.0 }
                .distinctBy { it.name.lowercase(Locale.ROOT) to "%.2f".format(Locale.US, it.amount) }
                .take(MAX_RECEIPT_ITEMS)
        }

        val fallbackItems = parseItemsWithHeuristics(lines)
        if (fallbackItems.isNotEmpty()) {
            return fallbackItems
                .map { it.copy(name = it.name.replace(Regex("""\s+"""), " ").trim()) }
                .filter { it.name.length >= 2 && it.amount > 0.0 }
                .distinctBy { it.name.lowercase(Locale.ROOT) to "%.2f".format(Locale.US, it.amount) }
                .take(MAX_RECEIPT_ITEMS)
        }

        return parseItemsVeryLoose(lines)
            .map { it.copy(name = it.name.replace(Regex("""\s+"""), " ").trim()) }
            .filter { it.name.length >= 2 && it.amount > 0.0 }
            .distinctBy { it.name.lowercase(Locale.ROOT) to "%.2f".format(Locale.US, it.amount) }
            .take(MAX_RECEIPT_ITEMS)
    }

    private fun parseItemsFromDetectedTable(lines: List<String>): List<ReceiptItem> {
        val header = detectTableHeader(lines) ?: return emptyList()

        val items = mutableListOf<ReceiptItem>()
        var nonItemStreak = 0

        for (index in (header.headerLineIndex + 1) until lines.size) {
            val line = lines[index]
            val lower = line.lowercase(Locale.ROOT)

            if (isFooterLine(lower)) {
                if (items.isNotEmpty()) break
                continue
            }

            val cells = splitColumns(line)
            if (cells.isEmpty()) {
                nonItemStreak++
                if (nonItemStreak >= 4 && items.isNotEmpty()) break
                continue
            }

            val desc = extractDescriptionCell(cells, header)
            if (desc.isBlank() || desc.count { it.isLetter() } < 2) {
                nonItemStreak++
                if (nonItemStreak >= 4 && items.isNotEmpty()) break
                continue
            }

            val qty = header.qtyIndex?.let { idx -> cells.getOrNull(idx)?.let { parseQuantity(it) } }
            val unitPrice = header.unitIndex?.let { idx -> cells.getOrNull(idx)?.let { extractFirstAmount(it) } }
            val lineTotalFromTotalCol = header.totalIndex?.let { idx -> cells.getOrNull(idx)?.let { extractLastAmount(it) } }
            val lineTotal = chooseSubtotal(
                listOfNotNull(lineTotalFromTotalCol, extractLastAmount(line)),
                qty
            ) ?: if (qty != null && unitPrice != null) qty * unitPrice else extractLastAmount(line)

            if (lineTotal == null || lineTotal <= 0.0) {
                nonItemStreak++
                if (nonItemStreak >= 4 && items.isNotEmpty()) break
                continue
            }

            items += ReceiptItem(
                name = desc.take(70),
                amount = lineTotal,
                quantity = qty,
                unitPrice = unitPrice
            )
            nonItemStreak = 0
        }

        return items
    }

    private fun parseItemsWithHeuristics(lines: List<String>): List<ReceiptItem> {
        val skipKeywords = listOf(
            "total", "subtotal", "change", "payment", "cash", "card",
            "mbway", "visa", "mastercard", "amount due", "balance", "tip"
        )
        val discountKeywords = listOf("discount", "desconto", "promo", "promocao", "coupon", "cupao", "poupanca")

        return lines.mapNotNull { line ->
            val lower = line.lowercase(Locale.ROOT)
            if (skipKeywords.any { it in lower }) return@mapNotNull null
            if (discountKeywords.any { it in lower }) return@mapNotNull null

            val amounts = extractAllAmounts(line)
            if (amounts.isEmpty()) return@mapNotNull null

            val description = line
                .replace(Regex("""(?<!\d)(\d{1,3}(?:[\s.,]\d{3})*[.,]\d{2}|\d+[.,]\d{2})(?!\d)"""), " ")
                .replace(Regex("""[\t|]+"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            if (description.count { it.isLetter() } < 2) return@mapNotNull null

            val amount = amounts.maxOrNull() ?: return@mapNotNull null
            ReceiptItem(name = description.take(70), amount = amount)
        }
    }

    private fun parseItemsFromSplitSections(lines: List<String>): List<ReceiptItem> {
        val productHeaderKeywords = listOf("produto", "product", "item", "artigo", "descricao", "descr")
        val qtyKeywords = listOf("qnt", "qtd", "qty", "quant")
        val unitHeaderKeywords = listOf("unit", "preco", "price", "valor un")

        val productHeaderIndex = lines.indexOfFirst { line ->
            val lower = line.lowercase(Locale.ROOT)
            productHeaderKeywords.any { it in lower } || (qtyKeywords.any { it in lower } && productHeaderKeywords.any { it in lower || "prod" in lower })
        }
        if (productHeaderIndex == -1) return emptyList()

        val unitHeaderIndex = lines.indexOfFirst { line ->
            unitHeaderKeywords.any { keyword -> keyword in line.lowercase(Locale.ROOT) }
        }
            .takeIf { it > productHeaderIndex } ?: -1

        val footerIndex = lines.indexOfFirst { line ->
            val lower = line.lowercase(Locale.ROOT)
            isFooterLine(lower)
        }.takeIf { it > productHeaderIndex }

        val productsStart = productHeaderIndex + 1
        val productsEnd = listOfNotNull(unitHeaderIndex.takeIf { it > productsStart }, footerIndex?.takeIf { it > productsStart })
            .minOrNull() ?: minOf(lines.size, productsStart + 30)

        val productRows = lines.subList(productsStart, productsEnd)
            .mapNotNull { raw ->
                val qty = extractLeadingQuantity(raw)
                val cleaned = raw
                    .replace(Regex("""^\d+(?:[.,]\d+)?\s+"""), "")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                if (cleaned.count { it.isLetter() } < 2) return@mapNotNull null
                if (extractAllAmounts(cleaned).isNotEmpty()) return@mapNotNull null
                val lower = cleaned.lowercase(Locale.ROOT)
                if (isFooterLine(lower) || isDiscountLine(lower)) return@mapNotNull null
                if (isLikelyNoiseName(lower)) return@mapNotNull null
                if (isLikelyMetadataLine(lower)) return@mapNotNull null
                cleaned to qty
            }

        if (productRows.isEmpty()) return emptyList()

        val pricesStart = when {
            unitHeaderIndex != -1 -> unitHeaderIndex + 1
            else -> productsEnd
        }
        val pricesEnd = footerIndex ?: minOf(lines.size, pricesStart + 30)
        if (pricesStart >= pricesEnd) return emptyList()

        val priceValues = lines.subList(pricesStart, pricesEnd)
            .mapNotNull { line ->
                val lower = line.lowercase(Locale.ROOT)
                if (isFooterLine(lower) || isDiscountLine(lower)) return@mapNotNull null
                val amounts = extractAllAmounts(line)
                if (amounts.isEmpty()) return@mapNotNull null
                // Prefer standalone numeric rows in this fallback mode.
                val mostlyNumeric = line.count { it.isLetter() } <= 2
                if (!mostlyNumeric && amounts.size == 1) return@mapNotNull null
                amounts.last()
            }

        if (priceValues.isEmpty()) return emptyList()

        val pairCount = minOf(productRows.size, priceValues.size)
        if (pairCount <= 0) return emptyList()

        val paired = (0 until pairCount).map { idx ->
            val name = productRows[idx].first
            val qty = productRows[idx].second
            val subtotal = priceValues[idx]
            ReceiptItem(
                name = name.take(70),
                amount = subtotal,
                quantity = qty,
                unitPrice = null
            )
        }

        // This fallback is intentionally conservative for long/noisy receipts.
        val cleaned = paired.filter { item ->
            val lower = item.name.lowercase(Locale.ROOT)
            !isLikelyNoiseName(lower) && item.amount > 0.0
        }

        return cleaned.take(MAX_RECEIPT_ITEMS)
    }

    private fun parseItemsFromProductSection(lines: List<String>, expectedItemCount: Int?): List<ReceiptItem> {
        val productHeaderKeywords = listOf("produto", "product", "item", "artigo", "descricao", "descr")
        val qtyKeywords = listOf("qnt", "qtd", "qty", "quant")

        val productHeaderLineIndex = lines.indexOfFirst { line ->
            val lower = line.lowercase(Locale.ROOT)
            productHeaderKeywords.any { it in lower } || isProductHeaderLike(lower)
        }
        val qtyHeaderLineIndex = lines.indexOfFirst { line ->
            val lower = line.lowercase(Locale.ROOT)
            qtyKeywords.any { it in lower }
        }

        val headerIndex = when {
            productHeaderLineIndex != -1 && qtyHeaderLineIndex != -1 &&
                kotlin.math.abs(productHeaderLineIndex - qtyHeaderLineIndex) <= 3 -> {
                minOf(productHeaderLineIndex, qtyHeaderLineIndex)
            }
            productHeaderLineIndex != -1 -> productHeaderLineIndex
            qtyHeaderLineIndex != -1 -> qtyHeaderLineIndex
            else -> -1
        }
        if (headerIndex == -1) return emptyList()

        val footerIndex = lines.indexOfFirst { indexLine ->
            val idx = lines.indexOf(indexLine)
            idx > headerIndex && isFooterLine(indexLine.lowercase(Locale.ROOT))
        }.takeIf { it > headerIndex } ?: lines.size

        val region = lines.subList(headerIndex + 1, footerIndex)
        if (region.isEmpty()) return emptyList()

        val inlineItems = mutableListOf<ReceiptItem>()
        val descOnly = mutableListOf<String>()
        val standaloneQuantities = mutableListOf<Double>()
        val standaloneAmounts = mutableListOf<Double>()

        region.forEach { line ->
            val lower = line.lowercase(Locale.ROOT)
            if (isFooterLine(lower) || isDiscountLine(lower)) return@forEach

            val amounts = extractAllAmounts(line)
            val letterCount = line.count { it.isLetter() }

            when {
                amounts.isEmpty() && letterCount == 0 -> {
                    extractStandaloneQuantity(line)?.let { standaloneQuantities += it }
                }
                amounts.isNotEmpty() && letterCount >= 2 -> {
                    val qty = extractLeadingQuantity(line)
                    val subtotal = chooseSubtotal(amounts, qty) ?: amounts.last()
                    val desc = line
                        .replace(Regex("""(?<!\d)(\d{1,3}(?:[\s.,]\d{3})*[.,]\d{2}|\d+[.,]\d{2})(?!\d)"""), " ")
                        .replace(Regex("""\b\d+(?:[.,]\d+)?\b"""), " ")
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                    if (desc.count { it.isLetter() } >= 2) {
                        inlineItems += ReceiptItem(
                            name = desc.take(70),
                            amount = subtotal,
                            quantity = qty,
                            unitPrice = null
                        )
                    }
                }
                amounts.isEmpty() && letterCount >= 2 -> {
                    val cleaned = line.replace(Regex("""\s+"""), " ").trim()
                    if (cleaned.count { it.isLetter() } >= 2) descOnly += cleaned
                }
                amounts.isNotEmpty() && letterCount <= 2 -> {
                    standaloneAmounts += amounts.last()
                }
            }
        }

        val pairedSplitItems = mutableListOf<ReceiptItem>()
        val pairCount = minOf(descOnly.size, maxOf(standaloneQuantities.size, standaloneAmounts.size))
        for (i in 0 until pairCount) {
            val qty = standaloneQuantities.getOrNull(i) ?: 1.0
            val subtotal = standaloneAmounts.getOrNull(i) ?: 0.0
            if (subtotal > 0.0) {
                pairedSplitItems += ReceiptItem(
                    name = descOnly[i].take(70),
                    amount = subtotal,
                    quantity = qty,
                    unitPrice = null
                )
            }
        }

        val merged = (inlineItems + pairedSplitItems)
            .distinctBy { it.name.lowercase(Locale.ROOT) to "%.2f".format(Locale.US, it.amount) }

        return chooseByExpectedCount(merged, expectedItemCount)
    }

    private fun isProductHeaderLike(lower: String): Boolean {
        // OCR often mutates "produto" into forms like "proluto".
        val compact = lower.replace(Regex("""\s+"""), "")
        if ("produto" in compact || "product" in compact || "artigo" in compact) return true
        return Regex("""pr[o0][dl]u?t[o0]""").containsMatchIn(compact) ||
            Regex("""prod""").containsMatchIn(compact)
    }

    private fun extractExpectedItemCount(lines: List<String>): Int? {
        val markerRegex = Regex("""(?i)(numero\s+de\s+artig\w*|n[úu]mero\s+de\s+artigos|n\.?\s*artigos?)""")
        val numberRegex = Regex("""\b(\d{1,3})\b""")

        lines.forEach { line ->
            if (!markerRegex.containsMatchIn(line)) return@forEach
            val count = numberRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (count != null && count > 0) return count
        }
        return null
    }

    private fun chooseByExpectedCount(items: List<ReceiptItem>, expected: Int?): List<ReceiptItem> {
        if (items.isEmpty()) return emptyList()
        if (expected == null || expected <= 0) return items
        if (items.size <= expected) return items

        // Prefer lower amounts when we have over-detection from duplicated OCR rows.
        return items
            .sortedBy { it.amount }
            .take(expected)
    }

    private fun chooseBestCandidateItems(
        geometryItems: List<ReceiptItem>,
        textItems: List<ReceiptItem>,
        expectedCount: Int?
    ): List<ReceiptItem> {
        if (geometryItems.isEmpty()) return textItems
        if (textItems.isEmpty()) return geometryItems

        val geoNormalized = geometryItems
            .distinctBy { it.name.lowercase(Locale.ROOT) to "%.2f".format(Locale.US, it.amount) }
        val textNormalized = textItems
            .distinctBy { it.name.lowercase(Locale.ROOT) to "%.2f".format(Locale.US, it.amount) }

        if (expectedCount != null && expectedCount > 0) {
            val geoDiff = kotlin.math.abs(geoNormalized.size - expectedCount)
            val textDiff = kotlin.math.abs(textNormalized.size - expectedCount)
            if (textDiff < geoDiff) return textNormalized
            if (geoDiff < textDiff) return geoNormalized
        }

        val geoQuality = scoreCandidateQuality(geoNormalized)
        val textQuality = scoreCandidateQuality(textNormalized)
        if (geoNormalized.size >= 3 && geoQuality >= textQuality - 5) return geoNormalized
        if (geoQuality > textQuality + 12) return geoNormalized
        if (textQuality > geoQuality + 15 && geoNormalized.size <= 2) return textNormalized

        return geoNormalized
    }

    private fun parseItemsVeryLoose(lines: List<String>): List<ReceiptItem> {
        return lines.mapNotNull { line ->
            val lower = line.lowercase(Locale.ROOT)
            if (isFooterLine(lower) || isDiscountLine(lower)) return@mapNotNull null

            val amounts = extractAllAmounts(line)
            if (amounts.isEmpty()) return@mapNotNull null

            val description = line
                .replace(Regex("""(?<!\d)(\d{1,3}(?:[\s.,]\d{3})*[.,]\d{2}|\d+[.,]\d{2})(?!\d)"""), " ")
                .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()

            if (description.count { it.isLetter() } < 2) return@mapNotNull null

            ReceiptItem(
                name = description.take(70),
                amount = amounts.lastOrNull() ?: return@mapNotNull null
            )
        }
    }

    private fun detectTableHeader(lines: List<String>): ReceiptHeaderMap? {
        val descKeywords = listOf("desc", "descr", "design", "produto", "product", "item", "artigo")
        val qtyKeywords = listOf("qtd", "qnt", "qty", "quant", "unid")
        val unitKeywords = listOf("unit", "preco", "price", "p/u", "unitario")
        val totalKeywords = listOf("total", "valor", "amount", "importe")

        lines.take(40).forEachIndexed { index, line ->
            val cells = splitColumns(line)
            if (cells.size < 2) return@forEachIndexed

            var descIndex: Int? = null
            var qtyIndex: Int? = null
            var unitIndex: Int? = null
            var totalIndex: Int? = null

            cells.forEachIndexed { i, cell ->
                val c = cell.lowercase(Locale.ROOT)
                if (descIndex == null && descKeywords.any { it in c }) descIndex = i
                if (qtyIndex == null && qtyKeywords.any { it in c }) qtyIndex = i
                if (unitIndex == null && unitKeywords.any { it in c }) unitIndex = i
                if (totalIndex == null && totalKeywords.any { it in c }) totalIndex = i
            }

            if (descIndex != null && (totalIndex != null || unitIndex != null)) {
                return ReceiptHeaderMap(
                    headerLineIndex = index,
                    descIndex = descIndex!!,
                    qtyIndex = qtyIndex,
                    unitIndex = unitIndex,
                    totalIndex = totalIndex
                )
            }
        }

        return null
    }

    private fun splitColumns(line: String): List<String> {
        val byStrongDelimiters = line.split(Regex("""\s{2,}|\t+|\|"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (byStrongDelimiters.size >= 2) return byStrongDelimiters

        val bySingleSpace = line.split(' ')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return bySingleSpace
    }

    private fun extractDescriptionCell(cells: List<String>, header: ReceiptHeaderMap): String {
        val preferred = cells.getOrNull(header.descIndex).orEmpty().trim()
        if (preferred.count { it.isLetter() } >= 2) return preferred

        return cells.firstOrNull { cell -> cell.count { it.isLetter() } >= 2 }.orEmpty()
    }

    private fun parseQuantity(raw: String): Double? {
        val normalized = normalizeAmountToken(raw).trim()
        if (normalized.isBlank()) return null
        if (!Regex("""^\d{1,3}(?:[.,]\d{1,3})?$""").matches(normalized)) return null

        val decimalPartLength = normalized
            .substringAfterLast('.', missingDelimiterValue = normalized)
            .substringAfterLast(',', missingDelimiterValue = normalized)
            .let { part -> if (part == normalized) 0 else part.length }

        // In receipts, amounts are typically x,xx while weighted quantities are often x,xxx.
        if (decimalPartLength == 2) return null

        val parsed = parseAmount(normalized) ?: return null
        return parsed.takeIf { it in 0.001..200.0 }
    }

    private fun extractAllAmounts(raw: String): List<Double> {
        val amountRegex = Regex(
            """(?<![0-9A-Z])([0-9ODILSB]{1,3}(?:[\s.,][0-9ODILSB]{3})*[.,]\s*[0-9ODILSB]{2}|[0-9ODILSB]+[.,]\s*[0-9ODILSB]{2})(?![0-9A-Z])"""
        )
        return amountRegex.findAll(raw)
            .mapNotNull { parseAmount(it.value) }
            .filter { it > 0.0 }
            .toList()
    }

    private fun extractFirstAmount(raw: String): Double? = extractAllAmounts(raw).firstOrNull()

    private fun extractLastAmount(raw: String): Double? = extractAllAmounts(raw).lastOrNull()

    private fun extractLeadingQuantity(raw: String): Double? {
        val firstToken = raw.trim().substringBefore(' ').trim()
        val qty = parseQuantity(firstToken) ?: return null
        return qty.takeIf { it > 0.0 && it <= 200.0 }
    }

    private fun extractStandaloneQuantity(raw: String): Double? {
        val token = raw.trim()
        if (!Regex("""^\d{1,3}(?:[.,]\d+)?$""").matches(token)) return null
        val qty = parseQuantity(token) ?: return null
        return qty.takeIf { it > 0.0 && it <= 200.0 }
    }

    private fun inferQuantityFromTokens(
        tokens: List<VisionToken>,
        cutoffX: Float,
        targetX: Float?
    ): Double? {
        val candidates = tokens
            .filter { token ->
                token.centerX < cutoffX &&
                    Regex("""^\d{1,3}(?:[.,]\d{1,2})?$""").matches(normalizeAmountToken(token.text).trim())
            }
            .mapNotNull { token ->
                val quantity = parseQuantity(token.text)
                quantity?.let { token to it }
            }

        if (candidates.isEmpty()) return null

        return targetX?.let { qx ->
            candidates.minByOrNull { (token, _) -> kotlin.math.abs(token.centerX - qx) }?.second
        } ?: candidates.lastOrNull()?.second
    }

    private fun chooseSubtotal(amounts: List<Double>, qty: Double?): Double? {
        if (amounts.isEmpty()) return null
        val only = amounts.last()
        return when {
            amounts.size >= 2 -> amounts.maxOrNull()
            qty != null && qty > 1.01 -> qty * only
            else -> only
        }
    }

    private fun chooseUnitPrice(amounts: List<Double>, qty: Double?): Double? {
        if (amounts.isEmpty()) return null
        val only = amounts.last()
        return when {
            amounts.size >= 2 -> amounts.minOrNull()
            qty != null && qty > 1.01 -> only
            else -> null
        }
    }

    private fun isFooterLine(lower: String): Boolean {
        val footerKeywords = listOf(
            "subtotal", "total", "tax", "iva", "vat", "change", "troco", "payment", "card", "cash", "mbway"
        )
        return footerKeywords.any { it in lower }
    }

    private data class ReceiptHeaderMap(
        val headerLineIndex: Int,
        val descIndex: Int,
        val qtyIndex: Int?,
        val unitIndex: Int?,
        val totalIndex: Int?
    )

    private data class VisionToken(
        val text: String,
        val centerX: Float,
        val centerY: Float
    )

    private data class VisionLineRecord(
        val text: String,
        val tokens: List<VisionToken>
    )

    private data class VisionHeaderMap(
        val lineIndex: Int,
        val descX: Float,
        val qtyX: Float?,
        val unitX: Float?,
        val totalX: Float?
    )

    private fun scoreParsedItems(items: List<ReceiptItem>): Int {
        if (items.isEmpty()) return 0
        val uniqueNames = items
            .map { it.name.lowercase(Locale.ROOT).replace(Regex("""[^a-z0-9]"""), "") }
            .filter { it.isNotBlank() }
            .toSet()
            .size
        val duplicatePenalty = (items.size - uniqueNames).coerceAtLeast(0)
        return (items.size * 10) + (uniqueNames * 4) - (duplicatePenalty * 2)
    }

    private fun scoreCandidateItems(items: List<ReceiptItem>): Int {
        if (items.isEmpty()) return 0
        val base = scoreParsedItems(items)
        val cleanCount = items.count { item -> !isLikelyNoiseName(item.name.lowercase(Locale.ROOT)) }
        val noisyCount = (items.size - cleanCount).coerceAtLeast(0)
        val avgNameLen = items.map { it.name.trim().length }.average()
        val shortNamePenalty = if (avgNameLen < 4.0) 10 else 0
        return base + (cleanCount * 3) - (noisyCount * 8) - shortNamePenalty
    }

    private fun scoreCandidateQuality(items: List<ReceiptItem>): Int {
        if (items.isEmpty()) return Int.MIN_VALUE
        val base = scoreCandidateItems(items)
        val shortNames = items.count { it.name.trim().length < 6 }
        val numericLeading = items.count { item -> item.name.trim().firstOrNull()?.isDigit() == true }
        val digitHeavy = items.count { item -> item.name.count { ch -> ch.isDigit() } >= item.name.length / 3 }
        return base - (shortNames * 6) - (numericLeading * 10) - (digitHeavy * 8)
    }

    private fun scoreSelectedCandidate(items: List<ReceiptItem>): Int {
        if (items.isEmpty()) return Int.MIN_VALUE
        val base = scoreCandidateQuality(items)
        val withQty = items.count { (it.quantity ?: 0.0) > 0.0 }
        val suspiciousQty = items.count { item ->
            val q = item.quantity ?: return@count false
            q >= 10.0 && item.amount > 0.0 && (item.amount / q) < 0.35
        }
        return base + (withQty * 2) - (suspiciousQty * 12)
    }

    private fun isLikelyNoiseName(lower: String): Boolean {
        val normalized = lower.replace(Regex("""\s+"""), " ").trim()
        if (normalized.isBlank()) return true
        if (Regex("""^\d+[.,]?\d*$""").matches(normalized)) return true
        if (Regex("""\b\d{4}-\d{2}-\d{2}\b""").containsMatchIn(normalized)) return true
        val noiseKeywords = listOf(
            "qnt", "qtd", "qty", "quant", "data", "date", "cliente", "nif", "fatura", "talhao", "talhao"
        )
        return noiseKeywords.any { keyword -> keyword in normalized }
    }

    private fun isLikelyMetadataLine(lower: String): Boolean {
        val normalized = lower.replace(Regex("""\s+"""), " ").trim()
        if (normalized.isBlank()) return true
        val metadataKeywords = listOf(
            "data", "date", "hora", "time", "unit", "unit:", "total", "subtotal",
            "tx", "tax", "contribuinte", "nif", "numero de", "numaro", "numero",
            "resumo", "imposto", "arred", "modo", "pagamento", "payment", "gift", "change"
        )
        if (metadataKeywords.any { it in normalized }) return true
        if (Regex("""^[a-z]{1,4}\s*:?$""").matches(normalized)) return true
        return false
    }

    private fun summarizeItems(items: List<ReceiptItem>): String {
        if (items.isEmpty()) return "[]"
        return items.take(5).joinToString(prefix = "[", postfix = if (items.size > 5) ", ...]" else "]") { item ->
            "${item.name}:${String.format(Locale.US, "%.2f", item.amount)}"
        }
    }

    private fun previewText(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        return lines.take(20).joinToString("\n")
    }

    private suspend fun <T> Task<T>.awaitTaskResult(): T {
        return suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    continuation.resume(task.result)
                } else {
                    val exception = task.exception ?: IllegalStateException("Task failed")
                    continuation.cancel(exception)
                }
            }
        }
    }

    companion object {
        private val JOIN_CODE_REGEX = Regex("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{8}")
        private const val OCR_DEBUG_TAG = "ReceiptOCR"
        private const val OCR_DEBUG_ENABLED = true
        private const val MAX_RECEIPT_ITEMS = 50
    }
}
