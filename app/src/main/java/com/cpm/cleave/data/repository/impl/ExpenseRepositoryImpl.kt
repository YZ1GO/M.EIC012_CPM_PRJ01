package com.cpm.cleave.data.repository.impl

import android.util.Log
import android.content.Context
import com.cpm.cleave.BuildConfig
import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.data.local.AuthSessionStore
import com.cpm.cleave.data.local.ConnectivityStatus
import com.cpm.cleave.data.local.PendingSyncStore
import com.cpm.cleave.domain.repository.contracts.IExpenseRepository
import com.cpm.cleave.domain.usecase.CalculateDebtsUseCase
import com.cpm.cleave.domain.usecase.CreateExpenseCommand
import com.cpm.cleave.domain.usecase.CreateExpenseUseCase
import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.ExpenseShare
import com.cpm.cleave.model.PayerContribution
import com.cpm.cleave.model.ReceiptItem
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID
import java.net.HttpURLConnection
import java.net.URL
import android.util.Base64
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseRepositoryImpl(
    private val appContext: Context,
    private val cache: Cache,
    private val authSessionStore: AuthSessionStore,
    private val pendingSyncStore: PendingSyncStore,
    private val connectivityStatus: ConnectivityStatus,
    private val calculateDebtsUseCase: CalculateDebtsUseCase = CalculateDebtsUseCase(),
    private val createExpenseUseCase: CreateExpenseUseCase = CreateExpenseUseCase(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) : IExpenseRepository {

    private val localExpenseRefreshEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)

    override fun observeExpensesByGroup(groupId: String): Flow<List<Expense>> {
        // Restart the entire flow logic whenever the active user changes
        return authSessionStore.observeActiveUser()
        .distinctUntilChanged { old, new -> old?.id == new?.id }
        .flatMapLatest { user ->
            // If no user, emit empty
            if (user == null) return@flatMapLatest kotlinx.coroutines.flow.flowOf(emptyList())

            callbackFlow {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val refreshMutex = Mutex()
                val splitRegistrations = mutableMapOf<String, ListenerRegistration>()

                suspend fun refreshAndEmit() {
                    refreshMutex.withLock {
                        // Falls back to local cache if getExpensesByGroup hits a permission race condition
                        val refreshedExpenses = getExpensesByGroup(groupId)
                            .getOrElse { cache.getExpensesByGroup(groupId) }
                            .sortedByDescending { it.date }

                        trySend(refreshedExpenses)

                        val expenseIds = refreshedExpenses.map { it.id }.toSet()

                        splitRegistrations.keys.toMutableList().forEach { id ->
                            if (id !in expenseIds) {
                                splitRegistrations.remove(id)?.remove()
                            }
                        }

                        expenseIds.forEach { expenseId ->
                            if (!splitRegistrations.containsKey(expenseId)) {
                                splitRegistrations[expenseId] = firestore.collection("groups")
                                    .document(groupId)
                                    .collection("expenses")
                                    .document(expenseId)
                                    .collection("splits")
                                    .addSnapshotListener { _, error ->
                                        // SAFETY CHECK: Ignore permission errors during auth swap
                                        if (error != null && error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                                            return@addSnapshotListener
                                        }
                                        scope.launch { refreshAndEmit() }
                                    }
                            }
                        }
                    }
                }

                // Initial emission
                scope.launch {
                    trySend(cache.getExpensesByGroup(groupId).sortedByDescending { it.date })
                    refreshAndEmit()
                }

                // Main expenses collection listener
                val expensesRegistration = firestore.collection("groups")
                    .document(groupId)
                    .collection("expenses")
                    .addSnapshotListener { _, error ->
                        // SAFETY CHECK: Ignore permission errors during auth swap
                        if (error != null && error.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            return@addSnapshotListener
                        }
                        scope.launch { refreshAndEmit() }
                    }

                // Internal refresh trigger (e.g. after local creation)
                val localRefreshJob = scope.launch {
                    localExpenseRefreshEvents.collect { changedGroupId ->
                        if (changedGroupId == groupId) refreshAndEmit()
                    }
                }

                awaitClose {
                    expensesRegistration.remove()
                    localRefreshJob.cancel()
                    splitRegistrations.values.forEach { it.remove() }
                    scope.cancel()
                }
            }
        }
    }

    override suspend fun getExpensesByGroup(groupId: String): Result<List<Expense>> {
        return try {
            val localExpenses = cache.getExpensesByGroup(groupId)
            if (!connectivityStatus.isNetworkAvailable()) {
                return Result.success(localExpenses)
            }

            flushPendingExpenseSyncs()

            val remoteResult = runCatching {
                val remotePayload = withTimeoutOrNull(1500L) {
                    fetchExpensesFromRemote(groupId)
                } ?: throw IllegalStateException("Remote expenses fetch timed out")

                val (remoteExpenses, sharesByExpenseId) = remotePayload
                cache.upsertExpensesForGroup(
                    groupId = groupId,
                    expenses = remoteExpenses,
                    sharesByExpenseId = sharesByExpenseId
                )
                cache.getExpensesByGroup(groupId)
            }
            
            Result.success(
                remoteResult.getOrElse { error ->
                    android.util.Log.w("ExpenseRepo", "Remote fetch blocked: ${error.message}")
                    localExpenses
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getExpenseSharesByGroup(groupId: String): Result<Map<String, List<ExpenseShare>>> {
        return try {
            val expenses = getExpensesByGroup(groupId).getOrElse { return Result.failure(it) }
            val shares = expenses.associate { expense ->
                expense.id to cache.getExpenseSharesForExpense(expense.id)
            }
            Result.success(shares)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getDebtsByGroup(groupId: String): Result<List<Debt>> {
        return try {
            val group = cache.getGroupById(groupId) ?: return Result.success(emptyList())
            val expenses = cache.getExpensesByGroup(groupId)
            val sharesByExpenseId = expenses.associate { expense ->
                expense.id to cache.getExpenseSharesForExpense(expense.id)
            }

            // Remote member snapshots can briefly be incomplete during sync;
            // include all participants seen in expenses/splits to keep debts stable.
            val participantIds = mutableSetOf<String>()
            participantIds.addAll(group.members)
            expenses.forEach { expense ->
                participantIds.add(expense.paidByUserId)
                expense.payerContributions.forEach { contribution ->
                    participantIds.add(contribution.userId)
                }
                sharesByExpenseId[expense.id].orEmpty().forEach { share ->
                    participantIds.add(share.userId)
                }
            }

            Result.success(
                calculateDebtsUseCase.execute(
                    groupMembers = participantIds.toList(),
                    expenses = expenses,
                    sharesByExpenseId = sharesByExpenseId
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteExpense(groupId: String, expenseId: String): Result<Unit> {
        return try {
            val localExpense = cache.getExpenseById(expenseId)
                ?: return Result.success(Unit)

            val isPendingLocalOnly = pendingSyncStore.getPendingExpenseSyncs()
                .any { pending -> pending.groupId == groupId && pending.expenseId == expenseId }

            if (!isPendingLocalOnly && !connectivityStatus.isNetworkAvailable()) {
                return Result.failure(
                    IllegalStateException("Deleting an expense requires an internet connection.")
                )
            }

            if (!isPendingLocalOnly) {
                syncExpenseDeletionToRemote(groupId = groupId, expenseId = expenseId)
            }

            cache.deleteExpenseWithRelations(expenseId)
            pendingSyncStore.removePendingExpenseSync(groupId, expenseId)

            localExpense.imagePath
                ?.takeIf { it.isLocalReceiptPath() }
                ?.let { localPath ->
                    localReceiptFileFromPath(localPath)?.let { file ->
                        runCatching { file.delete() }
                    }
                }

            localExpenseRefreshEvents.tryEmit(groupId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createExpense(
        groupId: String,
        amount: Double,
        description: String,
        splitMemberIds: List<String>,
        payerContributions: Map<String, Double>,
        receiptImageBytes: ByteArray?,
        receiptItems: List<ReceiptItem>
    ): Result<Unit> {
        return try {
            val currentUser = authSessionStore.getActiveUser()
            val group = cache.getGroupById(groupId)
            val normalizedContributions = payerContributions
                .filterValues { it > 0.0 }

            val command = CreateExpenseCommand(
                amount = amount,
                payerContributions = normalizedContributions,
                splitMemberIds = splitMemberIds
            )
            createExpenseUseCase.execute(
                command = command,
                currentUser = currentUser,
                group = group
            ).getOrElse { return Result.failure(it) }

            val expenseId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val receiptImagePath = if (receiptImageBytes != null) {
                val localPath = persistReceiptLocally(expenseId, receiptImageBytes)
                if (!connectivityStatus.isNetworkAvailable()) {
                    localPath
                } else {
                    runCatching {
                        uploadReceiptImage(
                            groupId = groupId,
                            expenseId = expenseId,
                            imageBytes = receiptImageBytes
                        )
                    }.getOrElse { error ->
                        Log.w(
                            "ExpenseRepo",
                            "Receipt upload failed; keeping local receipt for deferred sync: ${error.message}"
                        )
                        localPath
                    }
                }
            } else {
                null
            }

            cache.insertExpenseWithSplit(
                expenseId = expenseId,
                amount = amount,
                description = description,
                date = now,
                groupId = groupId,
                payerContributions = normalizedContributions,
                memberIds = splitMemberIds,
                imagePath = receiptImagePath,
                receiptItems = receiptItems
            )

            localExpenseRefreshEvents.tryEmit(groupId)

            if (!connectivityStatus.isNetworkAvailable()) {
                pendingSyncStore.addPendingExpenseSync(groupId, expenseId)
                return Result.success(Unit)
            }

            val syncSucceeded = runCatching {
                withTimeoutOrNull(4000L) {
                    syncExpenseToRemote(
                        expenseId = expenseId,
                        groupId = groupId,
                        amount = amount,
                        description = description,
                        date = now,
                        splitMemberIds = splitMemberIds,
                        payerContributions = normalizedContributions,
                        imagePath = receiptImagePath.takeUnless { it.isLocalReceiptPath() },
                        receiptItems = receiptItems
                    )
                    true
                } ?: false
            }.getOrDefault(false)

            if (syncSucceeded) {
                pendingSyncStore.removePendingExpenseSync(groupId, expenseId)
            } else {
                pendingSyncStore.addPendingExpenseSync(groupId, expenseId)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun flushPendingExpenseSyncs() {
        if (!connectivityStatus.isNetworkAvailable()) return

        pendingSyncStore.getPendingExpenseSyncs().forEach { pending ->
            val localExpense = cache.getExpenseById(pending.expenseId) ?: run {
                pendingSyncStore.removePendingExpenseSync(pending.groupId, pending.expenseId)
                return@forEach
            }
            val shares = cache.getExpenseSharesForExpense(localExpense.id)
            val payers = localExpense.payerContributions.ifEmpty {
                listOf(PayerContribution(userId = localExpense.paidByUserId, amount = localExpense.amount))
            }

            val syncedImagePath = resolveImagePathForSync(
                groupId = pending.groupId,
                expenseId = localExpense.id,
                currentImagePath = localExpense.imagePath
            )

            val syncSucceeded = runCatching {
                withTimeoutOrNull(4000L) {
                    syncExpenseToRemote(
                        expenseId = localExpense.id,
                        groupId = pending.groupId,
                        amount = localExpense.amount,
                        description = localExpense.description,
                        date = localExpense.date,
                        splitMemberIds = shares.map { it.userId },
                        payerContributions = payers.associate { it.userId to it.amount },
                        imagePath = syncedImagePath,
                        receiptItems = localExpense.receiptItems
                    )
                    true
                } ?: false
            }.getOrDefault(false)

            if (syncSucceeded) {
                pendingSyncStore.removePendingExpenseSync(pending.groupId, pending.expenseId)
            } else {
                // Keep pending for later retry.
            }
        }
    }

    private suspend fun fetchExpensesFromRemote(groupId: String): Pair<List<Expense>, Map<String, List<ExpenseShare>>> {
        val expensesSnapshot = firestore.collection("groups")
            .document(groupId)
            .collection("expenses")
            .get()
            .awaitTaskResult()

        val expenses = mutableListOf<Expense>()
        val sharesByExpenseId = mutableMapOf<String, List<ExpenseShare>>()

        for (expenseDoc in expensesSnapshot.documents) {
            val payersSnapshot = firestore.collection("groups")
                .document(groupId)
                .collection("expenses")
                .document(expenseDoc.id)
                .collection("payers")
                .get()
                .awaitTaskResult()

            val payerContributions = payersSnapshot.documents.mapNotNull { payerDoc ->
                val userId = payerDoc.getString("userId") ?: return@mapNotNull null
                val contribution = payerDoc.getDouble("amount") ?: 0.0
                PayerContribution(userId = userId, amount = contribution)
            }

            val legacyPaidBy = expenseDoc.getString("paidByUserId") ?: ""
            val expense = Expense(
                id = expenseDoc.id,
                amount = (expenseDoc.getDouble("amount") ?: 0.0),
                description = expenseDoc.getString("description") ?: "",
                date = expenseDoc.getLong("date") ?: 0L,
                groupId = expenseDoc.getString("groupId") ?: groupId,
                paidByUserId = legacyPaidBy,
                imagePath = expenseDoc.getString("imagePath"),
                receiptItems = decodeReceiptItemsField(expenseDoc.get("receiptItems")),
                payerContributions = if (payerContributions.isNotEmpty()) {
                    payerContributions
                } else {
                    listOf(PayerContribution(userId = legacyPaidBy, amount = (expenseDoc.getDouble("amount") ?: 0.0)))
                }
            )
            expenses.add(expense)

            val splitsSnapshot = firestore.collection("groups")
                .document(groupId)
                .collection("expenses")
                .document(expenseDoc.id)
                .collection("splits")
                .get()
                .awaitTaskResult()

            val shares = splitsSnapshot.documents.mapNotNull { splitDoc ->
                val userId = splitDoc.getString("userId") ?: return@mapNotNull null
                val amount = splitDoc.getDouble("amount") ?: 0.0
                ExpenseShare(userId = userId, amount = amount)
            }
            sharesByExpenseId[expenseDoc.id] = shares
        }

        return expenses to sharesByExpenseId
    }

    private suspend fun syncExpenseToRemote(
        expenseId: String,
        groupId: String,
        amount: Double,
        description: String,
        date: Long,
        splitMemberIds: List<String>,
        payerContributions: Map<String, Double>,
        imagePath: String?,
        receiptItems: List<ReceiptItem>
    ) {
        val now = System.currentTimeMillis()
        val groupRef = firestore.collection("groups").document(groupId)
        val expenseRef = groupRef.collection("expenses").document(expenseId)
        val primaryPayerId = payerContributions.maxByOrNull { it.value }?.key.orEmpty()

        val batch = firestore.batch()
        batch.set(
            expenseRef,
            mapOf(
                "amount" to amount,
                "description" to description,
                "date" to date,
                "groupId" to groupId,
                "paidByUserId" to primaryPayerId,
                "imagePath" to imagePath,
                "receiptItems" to receiptItems.map { item ->
                    mapOf(
                        "name" to item.name,
                        "amount" to item.amount,
                        "quantity" to item.quantity,
                        "unitPrice" to item.unitPrice
                    )
                },
                "updatedAt" to now
            ),
            SetOptions.merge()
        )

        batch.set(
            groupRef,
            mapOf("updatedAt" to now),
            SetOptions.merge()
        )

        payerContributions.forEach { (payerId, contributionAmount) ->
            val payerRef = expenseRef.collection("payers").document(payerId)
            batch.set(
                payerRef,
                mapOf(
                    "userId" to payerId,
                    "amount" to contributionAmount,
                    "updatedAt" to now
                ),
                SetOptions.merge()
            )
        }

        if (splitMemberIds.isNotEmpty()) {
            val splitAmount = amount / splitMemberIds.size
            splitMemberIds.forEach { memberId ->
                val splitRef = expenseRef.collection("splits").document(memberId)
                batch.set(
                    splitRef,
                    mapOf(
                        "userId" to memberId,
                        "amount" to splitAmount,
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                )
            }
        }

        batch.commit().awaitTaskResult()
    }

    private suspend fun syncExpenseDeletionToRemote(groupId: String, expenseId: String) {
        val now = System.currentTimeMillis()
        val groupRef = firestore.collection("groups").document(groupId)
        val expenseRef = groupRef.collection("expenses").document(expenseId)

        val payersSnapshot = expenseRef.collection("payers")
            .get()
            .awaitTaskResult()
        val splitsSnapshot = expenseRef.collection("splits")
            .get()
            .awaitTaskResult()

        val batch = firestore.batch()

        payersSnapshot.documents.forEach { payerDoc ->
            batch.delete(payerDoc.reference)
        }

        splitsSnapshot.documents.forEach { splitDoc ->
            batch.delete(splitDoc.reference)
        }

        batch.delete(expenseRef)
        batch.set(
            groupRef,
            mapOf("updatedAt" to now),
            SetOptions.merge()
        )
        batch.commit().awaitTaskResult()
    }

    private suspend fun uploadReceiptImage(
        groupId: String,
        expenseId: String,
        imageBytes: ByteArray
    ): String {
        val baseUrl = BuildConfig.SUPABASE_UPLOAD_URL.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            throw IllegalStateException("SUPABASE_UPLOAD_URL is not configured")
        }

        var lastError: Exception? = null
        repeat(5) { attempt ->
            try {
                return uploadReceiptViaHttp(
                    endpointUrl = "$baseUrl/receipts/upload",
                    groupId = groupId,
                    expenseId = expenseId,
                    imageBytes = imageBytes
                )
            } catch (error: Exception) {
                lastError = error
                delay(250L * (attempt + 1))
            }
        }

        throw IllegalStateException(
            "Receipt was uploaded but its URL could not be resolved. Please try again.",
            lastError
        )
    }

    private suspend fun uploadReceiptViaHttp(
        endpointUrl: String,
        groupId: String,
        expenseId: String,
        imageBytes: ByteArray
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("groupId", groupId)
            put("expenseId", expenseId)
            put("imageBase64", Base64.encodeToString(imageBytes, Base64.NO_WRAP))
        }

        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 45000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray()) }
            val status = connection.responseCode
            val body = runCatching {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("")

            if (status !in 200..299) {
                throw IllegalStateException("Receipt upload failed (HTTP $status): $body")
            }

            val response = JSONObject(body)
            val receiptUrl = response.optString("receiptUrl")
            if (receiptUrl.isBlank()) {
                throw IllegalStateException("Receipt upload endpoint did not return receiptUrl")
            }
            receiptUrl
        } finally {
            connection.disconnect()
        }
    }

    private fun persistReceiptLocally(expenseId: String, imageBytes: ByteArray): String? {
        return runCatching {
            val dir = File(appContext.filesDir, "receipt-cache").apply { mkdirs() }
            val file = File(dir, "$expenseId.jpg")
            file.writeBytes(imageBytes)
            "file://${file.absolutePath}"
        }.getOrElse { error ->
            Log.w("ExpenseRepo", "Could not persist local receipt cache: ${error.message}")
            null
        }
    }

    private suspend fun resolveImagePathForSync(
        groupId: String,
        expenseId: String,
        currentImagePath: String?
    ): String? {
        if (currentImagePath.isNullOrBlank()) return null
        if (!currentImagePath.isLocalReceiptPath()) return currentImagePath

        val localFile = localReceiptFileFromPath(currentImagePath) ?: return null
        if (!localFile.exists()) return null

        val uploadedPath = runCatching {
            uploadReceiptImage(groupId = groupId, expenseId = expenseId, imageBytes = localFile.readBytes())
        }.getOrElse { error ->
            Log.w("ExpenseRepo", "Deferred receipt upload failed: ${error.message}")
            return currentImagePath
        }

        cache.updateExpenseImagePath(expenseId, uploadedPath)
        runCatching { localFile.delete() }
        return uploadedPath
    }

    private fun localReceiptFileFromPath(imagePath: String): File? {
        val rawPath = imagePath.removePrefix("file://")
        if (rawPath.isBlank()) return null
        return File(rawPath)
    }

    private fun String?.isLocalReceiptPath(): Boolean {
        return this?.startsWith("file://") == true
    }

    private fun decodeReceiptItemsField(raw: Any?): List<ReceiptItem> {
        val entries = raw as? List<*> ?: return emptyList()
        return entries.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val name = (map["name"] as? String)?.trim().orEmpty()
            val amount = (map["amount"] as? Number)?.toDouble() ?: 0.0
            val quantity = (map["quantity"] as? Number)?.toDouble()?.takeIf { it > 0.0 }
            val unitPrice = (map["unitPrice"] as? Number)?.toDouble()?.takeIf { it > 0.0 }
            if (name.isBlank() || amount <= 0.0) null
            else ReceiptItem(name = name, amount = amount, quantity = quantity, unitPrice = unitPrice)
        }
    }

    private suspend fun <T> Task<T>.awaitTaskResult(): T {
        return suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    continuation.resume(task.result)
                } else {
                    val exception = task.exception ?: IllegalStateException("Firebase task failed.")
                    continuation.cancel(exception)
                }
            }
        }
    }

    fun forceRefresh(groupId: String) {
        localExpenseRefreshEvents.tryEmit(groupId)
    }
}
