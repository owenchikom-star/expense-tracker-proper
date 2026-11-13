package com.example.expensetracker.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.example.expensetracker.data.notifications.NotificationHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.Calendar
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomExpenseRepository @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    @param:ApplicationContext private val context: Context,
    private val storage: FirebaseStorage = FirebaseStorage.getInstance()
) : ExpenseRepository {

    private var syncListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private var activeSyncUid: String? = null

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null && !user.uid.isBlank()) {
                startRealtimeSync()
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    try {
                        syncFromCloud()
                    } catch (e: Exception) {
                        Log.e("SYNC", "Auth state listener cloud sync failed", e)
                    }
                }
            } else {
                syncListenerRegistration?.remove()
                syncListenerRegistration = null
            }
        }
    }

    override suspend fun insert(expense: Expense) = expenseDao.insert(expense)

    private fun isLocalPath(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return !path.startsWith("http://") && !path.startsWith("https://")
    }

    private fun getCompressibleFile(rawPath: String): File? {
        return try {
            if (rawPath.startsWith("content://")) {
                val uri = Uri.parse(rawPath)
                val inputStream = context.contentResolver.openInputStream(uri) ?: return null
                val tempFile = File(context.cacheDir, "temp_receipt_${System.currentTimeMillis()}.jpg")
                FileOutputStream(tempFile).use { out ->
                    inputStream.copyTo(out)
                }
                tempFile
            } else {
                val path = if (rawPath.startsWith("file:")) Uri.parse(rawPath).path ?: rawPath else rawPath
                val f = File(path)
                if (f.exists()) f else null
            }
        } catch (e: Exception) {
            Log.e("REPO", "Error creating compressible file", e)
            null
        }
    }

    override suspend fun upsert(expense: Expense): String = withContext(Dispatchers.IO) {
        val expenseWithFbId = if (expense.firebaseId.isBlank()) {
            expense.copy(firebaseId = java.util.UUID.randomUUID().toString())
        } else {
            expense
        }
        val expenseToSave = if (expenseWithFbId.id.isBlank()) {
            expenseWithFbId.copy(id = java.util.UUID.randomUUID().toString())
        } else {
            expenseWithFbId
        }

        // 1. ROOM FIRST - returns immediately for UI
        expenseDao.upsert(expenseToSave)
        val savedForUi = expenseToSave

        // 2. FIREBASE IN BACKGROUND - don't block UI
        launch {
            val user = auth.currentUser
            val uid = user?.uid
            if (uid.isNullOrBlank()) return@launch
            var currentExpense = savedForUi

            var pushSuccess = false
            // Step A: Push text data to Firestore IMMEDIATELY so secondary devices get the item instantly
            try {
                val initialFirestoreExpense = if (isLocalPath(currentExpense.receiptImagePath)) {
                    currentExpense.copy(receiptImagePath = null, isSynced = false)
                } else {
                    currentExpense.copy(isSynced = true)
                }

                firestore.collection("users").document(uid)
                    .collection("expenses").document(initialFirestoreExpense.firebaseId)
                    .set(initialFirestoreExpense.toMap())
                    .await()
                pushSuccess = true
            } catch (e: Exception) {
                Log.e("SYNC", "Initial Firestore push failed", e)
            }

            // Step B: Upload receipt image to Storage in background if local file
            val rawImage = savedForUi.receiptImagePath
            if (isLocalPath(rawImage)) {
                try {
                    val file = getCompressibleFile(rawImage!!)
                    if (file != null) {
                        val compressed = compressImage(file)
                        val ref = storage.reference.child("users/$uid/receipts/${savedForUi.firebaseId}.jpg")
                        uploadImageToStorage(ref, compressed)
                        val downloadUrl = ref.downloadUrl.await().toString()

                        currentExpense = savedForUi.copy(receiptImagePath = downloadUrl, isSynced = true)

                        // Update Firestore with the public Storage URL
                        firestore.collection("users").document(uid)
                            .collection("expenses").document(currentExpense.firebaseId)
                            .set(currentExpense.toMap())
                            .await()

                        // Update local Room with public URL and synced status
                        expenseDao.upsert(currentExpense)
                    }
                } catch (e: Exception) {
                    Log.e("SYNC", "Image upload / Storage sync failed", e)
                }
            } else {
                if (pushSuccess) {
                    expenseDao.upsert(savedForUi.copy(isSynced = true))
                }
            }
        }
        savedForUi.id
    }

    private suspend fun uploadImageToStorage(ref: StorageReference, file: File) {
        if (!file.exists() || file.length() == 0L) {
            Log.e("SYNC", "File to upload does not exist or is empty: ${file.absolutePath}")
            return
        }

        val bytes = try {
            file.readBytes()
        } catch (e: Exception) {
            Log.e("SYNC", "Failed to read image bytes from file: ${file.absolutePath}", e)
            return
        }

        if (bytes.isEmpty()) {
            Log.e("SYNC", "Image bytes are empty: ${file.absolutePath}")
            return
        }

        val metadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .build()

        var lastException: Exception? = null

        for (attempt in 1..3) {
            try {
                // Ensure auth token is valid and refreshed prior to starting upload
                auth.currentUser?.let { user ->
                    try {
                        user.getIdToken(attempt > 1).await()
                    } catch (tokenErr: Exception) {
                        Log.w("SYNC", "Auth token refresh on attempt $attempt failed: ${tokenErr.message}")
                    }
                }

                // putBytes direct byte array upload avoids resumable upload session creation and termination errors
                ref.putBytes(bytes, metadata).await()
                return
            } catch (e: Exception) {
                lastException = e
                Log.e("SYNC", "Storage upload attempt $attempt failed", e)
                if (attempt < 3) {
                    delay(1000L * attempt)
                }
            }
        }
        throw lastException ?: Exception("Upload failed after 3 attempts")
    }

    private fun compressImage(original: File): File {
        return try {
            val bitmap = BitmapFactory.decodeFile(original.absolutePath) ?: return original
            val maxDim = 1024f
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return original

            val scale = if (width > height) maxDim / width else maxDim / height
            if (scale >= 1f) return original

            val scaledWidth = (width * scale).toInt()
            val scaledHeight = (height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

            val outFile = File(original.parent, "comp_${original.name}")
            FileOutputStream(outFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
            }
            outFile
        } catch (e: Exception) {
            original
        }
    }

    override fun getAllExpenses(): Flow<List<Expense>> = expenseDao.getAllExpenses()

    override suspend fun delete(expense: Expense) {
        if (expense.id.isNotBlank()) {
            expenseDao.delete(expense)
        }
        if (expense.firebaseId.isNotBlank()) {
            expenseDao.deleteByFirebaseId(expense.firebaseId)
        }

        val uid = auth.currentUser?.uid ?: return
        try {
            if (expense.firebaseId.isNotBlank()) {
                firestore.collection("users").document(uid)
                    .collection("expenses").document(expense.firebaseId)
                    .delete().await()
            }
        } catch (e: Exception) {
            Log.e("REPO", "Failed to delete expense from Firestore", e)
        }
    }

    override suspend fun update(expense: Expense) = expenseDao.update(expense)
    override fun getExpensesByDateRange(startDate: Long, endDate: Long): Flow<List<Expense>> = expenseDao.getExpensesByDateRange(startDate, endDate)
    override fun getExpensesByCategory(category: String): Flow<List<Expense>> = expenseDao.getExpensesByCategory(category)
    override fun getTotalBetweenDates(startDate: Long, endDate: Long): Flow<Double?> = expenseDao.getTotalBetweenDates(startDate, endDate)
    override fun getFilteredExpenses(category: String, startMs: Long?, endMs: Long?): Flow<List<Expense>> = expenseDao.getFilteredExpenses(category, startMs, endMs)
    override fun getTotalForDate(startOfDay: Long, endOfDay: Long): Float = expenseDao.getTotalForDate(startOfDay, endOfDay)
    override suspend fun getTotalForWeek(startOfWeek: Long, endOfWeek: Long): Float = expenseDao.getTotalForWeek(startOfWeek, endOfWeek)
    override suspend fun getTotalByCategoryAndMonth(category: String, start: Long, end: Long): Float = expenseDao.getTotalByCategoryAndMonth(category, start, end)

    override suspend fun clearAll() = expenseDao.clearAll()
    override suspend fun insertAll(expenses: List<Expense>) = expenseDao.insertAll(expenses)
    override suspend fun deleteAll(id: String) = expenseDao.deleteAll(id)

    val localExpenses: Flow<List<Expense>> = expenseDao.getAllExpenses()

    private fun userCollection(): com.google.firebase.firestore.CollectionReference? {
        val user = auth.currentUser ?: return null
        val uid = user.uid
        if (uid.isBlank()) return null
        return firestore.collection("users").document(uid).collection("expenses")
    }

    override suspend fun addExpense(expense: Expense) {
        upsert(expense)
    }

    override suspend fun syncFromCloud() {
        val user = auth.currentUser ?: return
        if (user.uid.isBlank()) return
        val col = userCollection() ?: return
        try {
            val snapshot = col.get().await()
            snapshot.documents.forEach { doc ->
                val newEntity = doc.toEntity()
                val local = expenseDao.getExpenseByFirebaseId(newEntity.firebaseId)
                    ?: (if (newEntity.id.isNotBlank()) expenseDao.getExpenseById(newEntity.id) else null)
                val idToUse = local?.id ?: newEntity.id.ifBlank { newEntity.firebaseId }
                val finalEntity = if (local != null &&
                    isLocalPath(local.receiptImagePath) &&
                    newEntity.receiptImagePath.isNullOrBlank()) {
                    newEntity.copy(id = idToUse, receiptImagePath = local.receiptImagePath)
                } else {
                    newEntity.copy(id = idToUse)
                }
                expenseDao.insert(finalEntity)
            }
            pushUnsynced()
        } catch (e: com.google.firebase.firestore.FirebaseFirestoreException) {
            if (e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                Log.w("SYNC", "syncFromCloud permission denied: ${e.message}")
            } else {
                Log.e("SYNC", "syncFromCloud firestore error", e)
            }
        } catch (e: Exception) {
            Log.e("SYNC", "syncFromCloud error", e)
        }
    }

    override suspend fun pushUnsynced() {
        val user = auth.currentUser ?: return
        if (user.uid.isBlank()) return
        val col = userCollection() ?: return
        val uid = user.uid
        expenseDao.getUnsynced().forEach { expense ->
            try {
                var finalUrl = expense.receiptImagePath
                if (isLocalPath(finalUrl)) {
                    val file = getCompressibleFile(finalUrl!!)
                    if (file != null) {
                        val compressed = compressImage(file)
                        val ref = storage.reference.child("users/$uid/receipts/${expense.firebaseId}.jpg")
                        uploadImageToStorage(ref, compressed)
                        finalUrl = ref.downloadUrl.await().toString()
                    }
                }
                val syncedExpense = expense.copy(receiptImagePath = finalUrl, isSynced = true)
                col.document(syncedExpense.firebaseId).set(syncedExpense.toMap()).await()
                expenseDao.insert(syncedExpense)
            } catch (e: Exception) {
                Log.e("REPO", "Failed to push unsynced expense", e)
            }
        }
    }

    @Synchronized
    override fun startRealtimeSync(onUpdated: () -> Unit): com.google.firebase.firestore.ListenerRegistration {
        val user = auth.currentUser
        val uid = user?.uid
        if (uid.isNullOrBlank()) {
            return syncListenerRegistration ?: com.google.firebase.firestore.ListenerRegistration { }
        }

        if (syncListenerRegistration != null && activeSyncUid == uid) {
            return syncListenerRegistration!!
        }

        syncListenerRegistration?.remove()
        activeSyncUid = uid

        val col = firestore.collection("users").document(uid).collection("expenses")
        val reg = col.addSnapshotListener { snap, error ->
            if (error != null) {
                if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    Log.w("SYNC", "Realtime sync permission denied: ${error.message}")
                    syncListenerRegistration?.remove()
                    syncListenerRegistration = null
                    activeSyncUid = null
                } else {
                    Log.e("SYNC", "Realtime sync error: ${error.message}", error)
                }
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener

            val changes = snap.documentChanges
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    for (change in changes) {
                        val doc = change.document
                        when (change.type) {
                            com.google.firebase.firestore.DocumentChange.Type.REMOVED -> {
                                val fbId = doc.getString("firebaseId") ?: doc.id
                                expenseDao.deleteByFirebaseId(fbId)
                            }
                            com.google.firebase.firestore.DocumentChange.Type.ADDED,
                            com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                                val newEntity = doc.toEntity()
                                val local = expenseDao.getExpenseByFirebaseId(newEntity.firebaseId)
                                    ?: (if (newEntity.id.isNotBlank()) expenseDao.getExpenseById(newEntity.id) else null)
                                val idToUse = local?.id ?: newEntity.id.ifBlank { newEntity.firebaseId }
                                val finalEntity = if (local != null &&
                                    isLocalPath(local.receiptImagePath) &&
                                    newEntity.receiptImagePath.isNullOrBlank()) {
                                    newEntity.copy(id = idToUse, receiptImagePath = local.receiptImagePath)
                                } else {
                                    newEntity.copy(id = idToUse)
                                }
                                expenseDao.insert(finalEntity)
                            }
                        }
                    }
                    onUpdated()
                } catch (e: Exception) {
                    Log.e("SYNC", "Error processing document change", e)
                }
            }
        }
        syncListenerRegistration = reg
        return reg
    }

    override suspend fun logoutCleanup() {
        syncListenerRegistration?.remove()
        syncListenerRegistration = null
        activeSyncUid = null
        expenseDao.clearAll()
    }
}

class RoomCategoryBudgetRepository @Inject constructor(
    private val categoryBudgetDao: CategoryBudgetDao,
    private val expenseDao: ExpenseDao,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val context: Context,
    private val notifier: NotificationHelper = NotificationHelper(context)
) : CategoryBudgetRepository {

    private var syncListenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
    private var activeSyncUid: String? = null

    init {
        auth.addAuthStateListener { firebaseAuth ->
            val user = firebaseAuth.currentUser
            if (user != null && !user.uid.isBlank()) {
                startRealtimeSync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        syncFromCloud()
                        pushUnsynced()
                    } catch (e: Exception) {
                        Log.e("BudgetSync", "Auth listener cloud sync failed", e)
                    }
                }
            } else {
                syncListenerRegistration?.remove()
                syncListenerRegistration = null
                activeSyncUid = null
            }
        }
    }

    private fun uid(): String? {
        val u = auth.currentUser?.uid
        return if (u.isNullOrBlank()) null else u
    }

    private fun budgetCollection(): com.google.firebase.firestore.CollectionReference? {
        val u = uid() ?: return null
        return firestore.collection("users").document(u).collection("budgets")
    }

    override fun getBudgetForMonth(month: Int, year: Int): Flow<List<CategoryBudget>> = categoryBudgetDao.getBudgetForMonth(month, year)

    override suspend fun deleteBudget(category: String, month: Int, year: Int) {
        categoryBudgetDao.deleteBudget(category, month, year)
        val col = budgetCollection() ?: return
        try {
            val docId = "${category.replace("/", "_")}_${month}_${year}"
            col.document(docId).delete().await()
        } catch (e: Exception) {
            Log.e("BudgetSync", "Failed to delete budget from cloud", e)
        }
    }

    override suspend fun getBudgetForOneMonth(category: String, month: Int, year: Int): Double? = categoryBudgetDao.getBudgetForOneMonth(category, month, year)
    override suspend fun insertBudget(budget: CategoryBudget) = upsertBudget(budget)

    override suspend fun getMonthlyReportData(month: Int, year: Int): MonthlyReport {
        val budgets = categoryBudgetDao.getBudgetForMonth(month, year).first()
        val start = LocalDate.of(year, month, 1)
        val end = start.withDayOfMonth(start.lengthOfMonth())

        val startDate = start.toEpochDay()
        val endDate = end.toEpochDay()

        val spentMap = expenseDao.getSpentByCategoryBetween(startDate, endDate)
        val topExpenses = expenseDao.getTopExpensesBetween(startDate, endDate)
        val totalSpent = spentMap.values.sum()
        val totalBudget = budgets.sumOf { it.monthlyBudget }

        return MonthlyReport(
            month, year, totalSpent, totalBudget,
            spentMap, budgets.associate { it.category to it.monthlyBudget },
            topExpenses
        )
    }

    override suspend fun onTransactionAdded(expense: Expense) = withContext(Dispatchers.IO) {
        val category = expense.category
        if (category.isBlank()) return@withContext

        val tz = TimeZone.getTimeZone("Africa/Johannesburg")
        val now = Calendar.getInstance(tz)
        val month = now.get(Calendar.MONTH) + 1 // Calendar.MONTH is 0-11
        val year = now.get(Calendar.YEAR)

        Log.d("BUDGET", "Checking category: '$category' for $month/$year")
        val categoryBudgetDouble = (categoryBudgetDao.getBudgetForOneMonth(category, month, year) ?: 0.0).toFloat()
        val allBudgets = categoryBudgetDao.getBudgetForMonth(month, year).first()
        Log.d("BUDGET", "All budgets in DB for $month/$year: $allBudgets")
        val categoryBudget = categoryBudgetDouble.toFloat()

        Log.d("BUDGET", "1. Checking $category with budget $categoryBudget")
        if (categoryBudget <= 0f) return@withContext

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@withContext

        val startOfMonth = now.apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val endOfMonth = now.apply { set(Calendar.DAY_OF_MONTH, now.getActualMaximum(Calendar.DAY_OF_MONTH)); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999) }.timeInMillis

        val prefs = context.getSharedPreferences("expense_prefs", Context.MODE_PRIVATE) // only for lastAlert
        val lastAlertKey = "last_budget_alert_${category}_${month}_${year}"
        val lastAlertedPercent = prefs.getInt(lastAlertKey, 0)

        val spentThisMonthInCat = expenseDao.getTotalByCategoryAndMonth(category, startOfMonth, endOfMonth)
        Log.d("BUDGET", "2. DAO returned: $spentThisMonthInCat")

        val percent = ((spentThisMonthInCat / categoryBudget) * 100).coerceAtMost(100f).toInt()
        Log.d("BUDGET", "3. Cat:$category Spent:$spentThisMonthInCat Budget:$categoryBudget Percent:$percent Last:$lastAlertedPercent")

        val alertThreshold = (percent/5)*5
        if (alertThreshold >= 80 && lastAlertedPercent < alertThreshold) {
            withContext(Dispatchers.Main) {
                notifier.showBudgetAlert(spentThisMonthInCat.toFloat(), categoryBudget, percent)
            }
            prefs.edit { putInt(lastAlertKey, alertThreshold) }
            Log.d("BUDGET", "4. ALERT FIRED for $category at $percent%")
        }
    }

    override suspend fun upsertBudget(budget: CategoryBudget) {
        val user = auth.currentUser
        val userId = user?.uid ?: budget.userId.ifBlank { null }

        // 1. Save locally first (fast UI) marked as unsynced
        val toSave = budget.copy(
            userId = userId ?: "",
            updatedAt = System.currentTimeMillis(),
            isSynced = false
        )
        categoryBudgetDao.upsertBudget(toSave)

        if (userId.isNullOrBlank()) return

        // 2. Try pushing to Firestore
        try {
            val docId = "${toSave.category.replace("/", "_")}_${toSave.month}_${toSave.year}"
            val col = budgetCollection() ?: return
            col.document(docId).set(toSave.toMap()).await()
            categoryBudgetDao.upsertBudget(toSave.copy(isSynced = true))
            Log.d("BudgetSync", "Uploaded budget $docId to Firestore")
        } catch (e: Exception) {
            Log.e("BudgetSync", "Upload failed for ${toSave.category} (will retry): ${e.message}")
        }
    }

    override suspend fun pushUnsynced() {
        val u = uid() ?: return
        val col = budgetCollection() ?: return
        val unsynced = categoryBudgetDao.getUnsyncedBudgets()
        if (unsynced.isEmpty()) return

        Log.d("BudgetSync", "Pushing ${unsynced.size} unsynced budgets to cloud...")
        for (b in unsynced) {
            try {
                val toSave = b.copy(userId = u)
                val docId = "${toSave.category.replace("/", "_")}_${toSave.month}_${toSave.year}"
                col.document(docId).set(toSave.toMap()).await()
                categoryBudgetDao.upsertBudget(toSave.copy(isSynced = true))
                Log.d("BudgetSync", "Successfully synced pending budget: $docId")
            } catch (e: Exception) {
                Log.e("BudgetSync", "Retry push for ${b.category} failed: ${e.message}")
            }
        }
    }

    override suspend fun syncFromCloud() {
        val col = budgetCollection() ?: return
        try {
            val snapshot = col.get().await()
            val cloudBudgets = snapshot.documents.mapNotNull { doc ->
                val category = doc.getString("category") ?: return@mapNotNull null
                val month = (doc.getLong("month") ?: 0).toInt()
                val year = (doc.getLong("year") ?: 0).toInt()
                val monthlyBudget = doc.getDouble("monthlyBudget") ?: 0.0
                val userId = doc.getString("userId") ?: (uid() ?: "")
                val updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()

                val existing = categoryBudgetDao.getBudgetForCategoryAndMonth(category, month, year)
                val idToUse = existing?.id ?: 0L

                CategoryBudget(
                    id = idToUse,
                    category = category,
                    monthlyBudget = monthlyBudget,
                    month = month,
                    year = year,
                    userId = userId,
                    updatedAt = updatedAt,
                    isSynced = true
                )
            }
            categoryBudgetDao.upsertAll(cloudBudgets)
            pushUnsynced()
        } catch (e: Exception) {
            Log.e("BudgetSync", "download failed", e)
        }
    }

    @Synchronized
    override fun startRealtimeSync(): Job = CoroutineScope(Dispatchers.IO).launch {
        val currentUid = uid() ?: return@launch
        if (syncListenerRegistration != null && activeSyncUid == currentUid) {
            return@launch
        }
        syncListenerRegistration?.remove()
        activeSyncUid = currentUid

        val col = budgetCollection() ?: return@launch
        val reg = col.addSnapshotListener { snap, error ->
            if (error != null) {
                if (error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    Log.w("BudgetSync", "Realtime budget sync permission denied: ${error.message}")
                    syncListenerRegistration?.remove()
                    syncListenerRegistration = null
                    activeSyncUid = null
                } else {
                    Log.e("BudgetSync", "Realtime budget sync error: ${error.message}", error)
                }
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener

            val changes = snap.documentChanges
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    for (change in changes) {
                        val doc = change.document
                        val category = doc.getString("category") ?: continue
                        val month = (doc.getLong("month") ?: 0).toInt()
                        val year = (doc.getLong("year") ?: 0).toInt()

                        when (change.type) {
                            com.google.firebase.firestore.DocumentChange.Type.REMOVED -> {
                                categoryBudgetDao.deleteBudget(category, month, year)
                            }
                            com.google.firebase.firestore.DocumentChange.Type.ADDED,
                            com.google.firebase.firestore.DocumentChange.Type.MODIFIED -> {
                                val monthlyBudget = doc.getDouble("monthlyBudget") ?: 0.0
                                val userId = doc.getString("userId") ?: currentUid
                                val updatedAt = doc.getLong("updatedAt") ?: System.currentTimeMillis()

                                val existing = categoryBudgetDao.getBudgetForCategoryAndMonth(category, month, year)
                                val idToUse = existing?.id ?: 0L

                                val b = CategoryBudget(
                                    id = idToUse,
                                    category = category,
                                    monthlyBudget = monthlyBudget,
                                    month = month,
                                    year = year,
                                    userId = userId,
                                    updatedAt = updatedAt,
                                    isSynced = true
                                )
                                categoryBudgetDao.upsertBudget(b)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BudgetSync", "Error processing budget snapshot change", e)
                }
            }
        }
        syncListenerRegistration = reg
    }

    override suspend fun copyLastMonthBudgets(targetMonth: Int, targetYear: Int) {
        val lastMonth = LocalDate.of(targetYear, targetMonth, 1).minusMonths(1)
        val lastBudgets = getBudgetForMonth(lastMonth.monthValue, lastMonth.year).first()
        lastBudgets.forEach { b ->
            upsertBudget(b.copy(id = 0, month = targetMonth, year = targetYear))
        }
    }

    suspend fun setBudget(category: String, amount: Double) {
        upsertBudget(CategoryBudget(0, category, amount, LocalDate.now().monthValue, LocalDate.now().year))
    }
}
