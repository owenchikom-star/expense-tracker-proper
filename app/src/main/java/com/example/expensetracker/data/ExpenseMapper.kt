package com.example.expensetracker.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

// Room -> Firestore
fun Expense.toMap(): Map<String, Any?> = mapOf(
    "id" to id,
    "firebaseId" to firebaseId,
    "name" to name,
    "price" to price,
    "amount" to price,
    "category" to category,
    "date" to date.toEpochDay(),
    "timeStamp" to timeStamp,
    "note" to note,
    "receiptImagePath" to receiptImagePath,
    "receiptText" to receiptText,
    "isSynced" to isSynced
)

// Firestore -> Room
fun DocumentSnapshot.toEntity(): Expense {
    val parsedDate = extractLocalDate("date")
    val rawTimeStamp = get("timeStamp")
    val timeStampVal = when (rawTimeStamp) {
        is Number -> rawTimeStamp.toLong()
        else -> parsedDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    val idVal = get("id")?.toString() ?: id
    val firebaseIdVal = get("firebaseId")?.toString() ?: id
    val nameVal = get("name")?.toString() ?: ""
    val priceVal = (get("amount") as? Number)?.toDouble()
        ?: (get("price") as? Number)?.toDouble()
        ?: 0.0
    val categoryVal = get("category")?.toString() ?: ""
    val noteVal = get("note")?.toString() ?: ""
    val receiptImagePathVal = get("receiptImagePath")?.toString()?.takeIf { path ->
        if (path.startsWith("/") || path.startsWith("file:")) {
            val rawPath = if (path.startsWith("file:")) android.net.Uri.parse(path).path ?: path else path
            java.io.File(rawPath).exists()
        } else {
            true
        }
    }
    val receiptTextVal = get("receiptText")?.toString()

    return Expense(
        id = idVal,
        firebaseId = firebaseIdVal,
        name = nameVal,
        price = priceVal,
        category = categoryVal,
        timeStamp = timeStampVal,
        receiptImagePath = receiptImagePathVal,
        receiptText = receiptTextVal,
        isSynced = true,
        date = parsedDate,
        note = noteVal
    )
}

private fun DocumentSnapshot.extractLocalDate(field: String): LocalDate {
    val raw = get(field) ?: return LocalDate.now()
    return when (raw) {
        is Number -> {
            val num = raw.toLong()
            if (num > 1_000_000_000_000L) { // Epoch millis
                Instant.ofEpochMilli(num)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            } else if (num > 1_000_000_000L) { // Epoch seconds
                Instant.ofEpochSecond(num)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            } else { // Epoch day (e.g. 20220)
                try {
                    LocalDate.ofEpochDay(num)
                } catch (e: Exception) {
                    LocalDate.now()
                }
            }
        }
        is String -> {
            raw.toLongOrNull()?.let { num ->
                if (num > 1_000_000_000_000L) {
                    Instant.ofEpochMilli(num)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                } else {
                    try { LocalDate.ofEpochDay(num) } catch (e: Exception) { null }
                }
            } ?: run {
                try {
                    LocalDate.parse(raw)
                } catch (e: Exception) {
                    LocalDate.now()
                }
            }
        }
        is Timestamp -> {
            Instant.ofEpochSecond(raw.seconds, raw.nanoseconds.toLong())
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
        }
        is Date -> {
            raw.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        }
        is Map<*, *> -> {
            val year = (raw["year"] as? Number)?.toInt()
            val month = (raw["monthValue"] as? Number)?.toInt()
                ?: (raw["month"] as? Number)?.toInt()
            val day = (raw["dayOfMonth"] as? Number)?.toInt()
                ?: (raw["day"] as? Number)?.toInt()
            if (year != null && month != null && day != null) {
                try {
                    LocalDate.of(year, month, day)
                } catch (e: Exception) {
                    LocalDate.now()
                }
            } else {
                LocalDate.now()
            }
        }
        else -> LocalDate.now()
    }
}
