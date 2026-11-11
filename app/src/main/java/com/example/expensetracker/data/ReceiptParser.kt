package com.example.expensetracker.data

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.vertexai.GenerativeModel
import com.google.firebase.vertexai.type.content
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.time.LocalDate
import javax.inject.Inject

class ReceiptParser @Inject constructor(
    private val generativeModel: GenerativeModel
) {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // 1. OCR
    suspend fun extractText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = textRecognizer.process(image).await()
        return result.text
    }

    // 2. AI Parse
    suspend fun parseWithGemini(ocrText: String): ParsedReceipt {
        val fallback = parse(ocrText)
        val prompt = """
            You are a receipt parser for an expense tracker. 
            From this OCR text, extract JSON.
            Text: """ + "\"\"\"" + ocrText + "\"\"\"" + """
            
            Return ONLY valid JSON like:
            {
              "storeName": "Shoprite",
              "amount": 152.50,
              "date": "2026-05-11",
              "category": "Food"
            }
            Categories: Entertainment, Transport, Food, Bills, Shopping, Health, Other.
            If date missing use today.
        """.trimIndent()

        return try {
            val response = generativeModel.generateContent(
                content { text(prompt) }
            )
            val jsonStr = response.text?.replace("```json", "")?.replace("```", "")?.trim() ?: "{}"
            val obj = JSONObject(jsonStr)

            val storeName = when {
                obj.has("storeName") && obj.getString("storeName").isNotBlank() -> obj.getString("storeName")
                obj.has("merchant") && obj.getString("merchant").isNotBlank() -> obj.getString("merchant")
                else -> fallback.storeName
            }

            val amount = if (obj.has("amount") && !obj.isNull("amount")) {
                val amt = obj.optDouble("amount", 0.0)
                if (amt > 0.0 && !amt.isNaN()) amt else fallback.amount
            } else {
                fallback.amount
            }

            val category = obj.optString("category", fallback.category)

            val dateStr = obj.optString("date", "")
            val date = try {
                if (dateStr.isNotBlank()) LocalDate.parse(dateStr) else fallback.date
            } catch (e: Exception) {
                fallback.date
            }

            ParsedReceipt(
                storeName = storeName,
                amount = amount,
                category = category,
                date = date
            )
        } catch (e: Exception) {
            Log.w("ReceiptParser", "Gemini parsing failed, falling back to OCR parser: ${e.message}")
            fallback
        }
    }

    suspend fun parse(bitmap: Bitmap): ParsedReceipt {
        val text = extractText(bitmap)
        if (text.isBlank()) return ParsedReceipt(
            amount = 0.0,
            date = LocalDate.now(),
            category = "Other",
            storeName = "Receipt"
        )
        return parseWithGemini(text)
    }

    companion object {
        fun parse(ocrText: String): ParsedReceipt {
            val lines = ocrText.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val amount = extractAmount(lines, ocrText)
            val date = extractDate(ocrText)
            val storeName = extractStoreName(lines)
            val category = inferCategory(storeName, ocrText)
            return ParsedReceipt(
                amount = amount,
                date = date,
                category = category,
                storeName = storeName
            )
        }

        private fun extractAmount(lines: List<String>, fullText: String): Double {
            val totalKeywords = listOf(
                "grand total", "subtotal", "sub total", "totala", "total", "totai",
                "gesamt", "montant a payer", "montant", "amount", "summe", "balance"
            )

            for (i in lines.indices) {
                val line = lines[i]
                val lineLower = line.lowercase()
                val matchedKeyword = totalKeywords.find { lineLower.contains(it) }
                if (matchedKeyword != null) {
                    val index = line.indexOf(matchedKeyword, ignoreCase = true)
                    val afterKeyword = if (index != -1) line.substring(index + matchedKeyword.length) else line
                    val sameLineAmount = parseNumericValue(afterKeyword)
                    if (sameLineAmount != null && sameLineAmount > 0) {
                        return sameLineAmount
                    }
                    val entireLineAmount = parseNumericValue(line)
                    if (entireLineAmount != null && entireLineAmount > 0) {
                        return entireLineAmount
                    }
                    if (i + 1 < lines.size) {
                        val nextLineAmount = parseNumericValue(lines[i + 1])
                        if (nextLineAmount != null && nextLineAmount > 0) {
                            return nextLineAmount
                        }
                    }
                }
            }

            val numbers = findAllNumericValues(fullText)
            return numbers.maxOrNull() ?: 0.0
        }

        private fun cleanNumericString(text: String): String {
            var s = text.replace(Regex("""(?<=\d)[oO]|[oO](?=\d|\.)"""), "0")
            s = s.replace(Regex("""(?<=\d)[Ii|l]|[Ii|l](?=\d)"""), "1")
            return s
        }

        private fun parseNumericValue(text: String): Double? {
            val cleaned = cleanNumericString(text)
            val regex = Regex("""\d+(?:[.,]\d{1,2})?""")
            val matches = regex.findAll(cleaned)
            for (match in matches) {
                val rawStr = match.value.replace(',', '.')
                val dbl = rawStr.toDoubleOrNull()
                if (dbl != null && dbl > 0) return dbl
            }
            return null
        }

        private fun findAllNumericValues(text: String): List<Double> {
            val lines = text.lines()
            val validLines = lines.filterNot { line ->
                val l = line.uppercase()
                l.contains("INV") || l.contains("VAT") || l.contains("TAX") ||
                l.contains("TEL") || l.contains("NO:") || l.contains("#") ||
                l.contains("DATE")
            }

            val result = mutableListOf<Double>()
            for (line in validLines) {
                val cleaned = cleanNumericString(line)
                val regex = Regex("""\d+(?:[.,]\d{2})""")
                val matches = regex.findAll(cleaned)
                for (match in matches) {
                    val dbl = match.value.replace(',', '.').toDoubleOrNull()
                    if (dbl != null && dbl > 0 && dbl < 100000) {
                        result.add(dbl)
                    }
                }
            }

            if (result.isEmpty()) {
                val cleaned = cleanNumericString(text)
                val regex = Regex("""\d+(?:[.,]\d{1,2})?""")
                for (match in regex.findAll(cleaned)) {
                    val dbl = match.value.replace(',', '.').toDoubleOrNull()
                    if (dbl != null && dbl > 0 && dbl < 100000) {
                        result.add(dbl)
                    }
                }
            }

            return result
        }

        private fun extractDate(text: String): LocalDate {
            val isoRegex = Regex("""\b(\d{4})[-/](\d{1,2})[-/](\d{1,2})\b""")
            val isoMatch = isoRegex.find(text)
            if (isoMatch != null) {
                try {
                    val (y, m, d) = isoMatch.destructured
                    return LocalDate.of(y.toInt(), m.toInt(), d.toInt())
                } catch (_: Exception) {}
            }

            val textDateRegex = Regex("""\b(\d{1,2})\s+([A-Za-z]+)\s+(\d{4})\b""")
            val textDateMatch = textDateRegex.find(text)
            if (textDateMatch != null) {
                try {
                    val (d, mStr, y) = textDateMatch.destructured
                    val month = parseMonth(mStr)
                    if (month != null) {
                        return LocalDate.of(y.toInt(), month, d.toInt())
                    }
                } catch (_: Exception) {}
            }

            return LocalDate.now()
        }

        private fun parseMonth(monthStr: String): Int? {
            val months = listOf(
                "january", "february", "march", "april", "may", "june",
                "july", "august", "september", "october", "november", "december"
            )
            val shortMonths = listOf(
                "jan", "feb", "mar", "apr", "may", "jun",
                "jul", "aug", "sep", "oct", "nov", "dec"
            )
            val lower = monthStr.lowercase()
            val fullIdx = months.indexOf(lower)
            if (fullIdx != -1) return fullIdx + 1
            val shortIdx = shortMonths.indexOf(lower)
            if (shortIdx != -1) return shortIdx + 1
            return null
        }

        private fun extractStoreName(lines: List<String>): String {
            val knownStores = listOf(
                "McDonald's Restaurant", "McDonald's", "Checkers", "Pick n Pay",
                "Shoprite", "Spar", "Aldi Sud", "Aldi", "Carrefour"
            )
            for (line in lines) {
                val known = knownStores.find { line.equals(it, ignoreCase = true) || line.contains(it, ignoreCase = true) }
                if (known != null) return known
            }

            for (line in lines) {
                if (line.isBlank()) continue
                if (line.contains("VAT NO", ignoreCase = true)) continue
                if (line.contains("INV-", ignoreCase = true)) continue
                if (line.matches(Regex("""^[\d\s\.,-]+$"""))) continue
                return line
            }

            return "Receipt"
        }

        private fun inferCategory(storeName: String, text: String): String {
            val lower = (storeName + " " + text).lowercase()
            return when {
                lower.contains("mcdonald") || lower.contains("bistro") || lower.contains("restaurant") ||
                lower.contains("checkers") || lower.contains("aldi") || lower.contains("carrefour") ||
                lower.contains("shoprite") || lower.contains("spar") || lower.contains("pick n pay") ||
                lower.contains("food") || lower.contains("milk") || lower.contains("bread") -> "Food"
                lower.contains("uber") || lower.contains("transport") || lower.contains("fuel") || lower.contains("gas") -> "Transport"
                lower.contains("cinema") || lower.contains("movie") -> "Entertainment"
                else -> "Other"
            }
        }
    }
}
