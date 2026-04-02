package com.cpm.cleave.data.repository.impl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.cpm.cleave.domain.repository.contracts.IScannerRepository
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
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

    private fun buildOcrCandidates(bitmap: Bitmap): List<Bitmap> {
        val candidates = mutableListOf<Bitmap>()
        candidates += bitmap

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
        val token = raw
            .trim()
            .replace(" ", "")
            .replace("€", "")
            .replace("$", "")
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
    }
}
