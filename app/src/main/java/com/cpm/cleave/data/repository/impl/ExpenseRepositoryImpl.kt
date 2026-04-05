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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import org.json.JSONArray
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

    private data class PendingExpensePayload(
        val amount: Double,
        val description: String,
        val date: Long,
        val mutationTimestamp: Long,
        val splitMemberIds: List<String>,
        val payerContributions: Map<String, Double>,
        val imagePath: String?,
        val receiptItems: List<ReceiptItem>
    )

    private data class RemoteExpenseVersion(
        val exists: Boolean,
        val updatedAt: Long?
    )

    private val localExpenseRefreshEvents = MutableSharedFlow<String>(extraBufferCapacity = 64)

    override fun observeExpensesByGroup(groupId: String): Flow<List<Expense>> {
        // Restart the entire flow logic whenever the active user changes
        return authSessionStore.observeActiveUser()
        .distinctUntilChanged { old, new -> old?.id == new?.id }
        .flatMapLatest { user ->
            // During transient auth swaps, avoid emitting an empty snapshot that clears UI.
            if (user == null) return@flatMapLatest emptyFlow()

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

            // Try to flush pending syncs but don't let it block the overall operation
            runCatching { flushPendingExpenseSyncs() }

            val remoteResult = runCatching {
                val remotePayload = withTimeoutOrNull(5000L) {
                    fetchExpensesFromRemote(groupId)
                } ?: throw IllegalStateException("Remote expenses fetch timed out")

                val (remoteExpenses, sharesByExpenseId) = remotePayload
                cache.upsertExpensesForGroup(
                    groupId = groupId,
                    expenses = remoteExpenses,
                    sharesByExpenseId = sharesByExpenseId
                )
                recomputeAndPersistDebts(groupId)
                cache.getExpensesByGroup(groupId)
            }
            
            Result.success(
                remoteResult.getOrElse { error ->
                    android.util.Log.w("ExpenseRepo", "Remote fetch blocked: ${error.message}")
                    localExpenses
                }
            )
        } catch (e: Exception) {
            // As a final fallback, return cached data if available
            try {
                val cachedExpenses = cache.getExpensesByGroup(groupId)
                if (cachedExpenses.isNotEmpty()) {
                    android.util.Log.w("ExpenseRepo", "Returning cached expenses (${cachedExpenses.size}) due to error: ${e.message}")
                    Result.success(cachedExpenses)
                } else {
                    Result.failure(e)
                }
            } catch (cacheError: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun getExpenseById(expenseId: String): Result<Expense?> {
        return runCatching { cache.getExpenseById(expenseId) }
    }

    override fun observeDebtsByGroup(groupId: String): Flow<List<Debt>> = flow {
        runCatching { recomputeAndPersistDebts(groupId) }
        emitAll(cache.observeDebtsByGroup(groupId))
    }.distinctUntilChanged()

    override suspend fun getExpenseSharesByGroup(groupId: String): Result<Map<String, List<ExpenseShare>>> {
        return try {
            // Keep this local to avoid remote refresh latency in detail/debt rendering paths.
            val expenses = cache.getExpensesByGroup(groupId)
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
            val cachedDebts = cache.getDebtsByGroup(groupId)
            if (cachedDebts.isNotEmpty()) return Result.success(cachedDebts)

            recomputeAndPersistDebts(groupId)
            Result.success(cache.getDebtsByGroup(groupId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteExpense(groupId: String, expenseId: String): Result<Unit> {
        return try {
            val localExpense = cache.getExpenseById(expenseId)

            val isPendingLocalOnly = pendingSyncStore.getPendingExpenseSyncs()
                .any { pending -> pending.groupId == groupId && pending.expenseId == expenseId }

            val isOnline = connectivityStatus.isNetworkAvailable()

            if (!isPendingLocalOnly && isOnline) {
                syncExpenseDeletionToRemote(groupId = groupId, expenseId = expenseId)
            } else if (!isPendingLocalOnly && !isOnline) {
                pendingSyncStore.addPendingExpenseDeletion(groupId, expenseId)
            }

            cache.deleteExpenseWithRelations(expenseId)
            recomputeAndPersistDebts(groupId)
            pendingSyncStore.removePendingExpenseSync(groupId, expenseId)
            if (isPendingLocalOnly) {
                pendingSyncStore.removePendingExpenseDeletion(groupId, expenseId)
            }

            localExpense?.imagePath
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
            val mutationTimestamp = now
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
            recomputeAndPersistDebts(groupId)

            val pendingPayloadJson = buildPendingExpensePayloadJson(
                amount = amount,
                description = description,
                date = now,
                mutationTimestamp = mutationTimestamp,
                splitMemberIds = splitMemberIds,
                payerContributions = normalizedContributions,
                imagePath = receiptImagePath,
                receiptItems = receiptItems
            )

            localExpenseRefreshEvents.tryEmit(groupId)

            // If expense has a local receipt image, always defer sync until image is uploaded
            if (receiptImagePath.isLocalReceiptPath()) {
                pendingSyncStore.addPendingExpenseSync(groupId, expenseId)
                pendingSyncStore.setPendingExpenseSyncPayload(groupId, expenseId, pendingPayloadJson)
                return Result.success(Unit)
            }

            if (!connectivityStatus.isNetworkAvailable()) {
                pendingSyncStore.addPendingExpenseSync(groupId, expenseId)
                pendingSyncStore.setPendingExpenseSyncPayload(groupId, expenseId, pendingPayloadJson)
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
                        mutationTimestamp = mutationTimestamp,
                        splitMemberIds = splitMemberIds,
                        payerContributions = normalizedContributions,
                        imagePath = null,
                        receiptItems = receiptItems
                    )
                    true
                } ?: false
            }.getOrDefault(false)

            if (syncSucceeded) {
                pendingSyncStore.removePendingExpenseSync(groupId, expenseId)
            } else {
                pendingSyncStore.addPendingExpenseSync(groupId, expenseId)
                pendingSyncStore.setPendingExpenseSyncPayload(groupId, expenseId, pendingPayloadJson)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateExpense(
        groupId: String,
        expenseId: String,
        amount: Double,
        description: String,
        splitMemberIds: List<String>,
        payerContributions: Map<String, Double>,
        receiptImageBytes: ByteArray?,
        removeReceiptImage: Boolean,
        receiptItems: List<ReceiptItem>
    ): Result<Unit> {
        return try {
            var existingExpense = cache.getExpenseById(expenseId)

            if (existingExpense == null) {
                repeat(4) {
                    delay(100)
                    existingExpense = cache.getExpenseById(expenseId)
                    if (existingExpense != null) return@repeat
                }
            }

            if (existingExpense == null && connectivityStatus.isNetworkAvailable()) {
                runCatching {
                    val remotePayload = withTimeoutOrNull(3000L) { fetchExpensesFromRemote(groupId) }
                    if (remotePayload != null) {
                        val (remoteExpenses, sharesByExpenseId) = remotePayload
                        cache.upsertExpensesForGroup(
                            groupId = groupId,
                            expenses = remoteExpenses,
                            sharesByExpenseId = sharesByExpenseId
                        )
                        existingExpense = cache.getExpenseById(expenseId)
                    }
                }
            }

            if (existingExpense == null) {
                return Result.failure(IllegalArgumentException("Expense not found"))
            }

            val normalizedContributions = payerContributions
                .filterValues { it > 0.0 }
            if (normalizedContributions.isEmpty()) {
                return Result.failure(IllegalArgumentException("Expense must have at least one payer"))
            }

            val now = System.currentTimeMillis()
            val mutationTimestamp = now
            val preservedDate = existingExpense!!.date

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
            } else if (removeReceiptImage) {
                null
            } else {
                existingExpense!!.imagePath
            }

            cache.insertExpenseWithSplit(
                expenseId = expenseId,
                amount = amount,
                description = description,
                date = preservedDate,
                groupId = groupId,
                payerContributions = normalizedContributions,
                memberIds = splitMemberIds,
                imagePath = receiptImagePath,
                receiptItems = receiptItems
            )
            recomputeAndPersistDebts(groupId)

            val pendingPayloadJson = buildPendingExpensePayloadJson(
                amount = amount,
                description = description,
                date = preservedDate,
                mutationTimestamp = mutationTimestamp,
                splitMemberIds = splitMemberIds,
                payerContributions = normalizedContributions,
                imagePath = receiptImagePath,
                receiptItems = receiptItems
            )

            localExpenseRefreshEvents.tryEmit(groupId)

            if (!connectivityStatus.isNetworkAvailable()) {
                pendingSyncStore.addPendingExpenseSync(groupId, expenseId)
                pendingSyncStore.setPendingExpenseSyncPayload(groupId, expenseId, pendingPayloadJson)
                return Result.success(Unit)
            }

            val syncSucceeded = runCatching {
                withTimeoutOrNull(4000L) {
                    syncExpenseToRemote(
                        expenseId = expenseId,
                        groupId = groupId,
                        amount = amount,
                        description = description,
                        date = preservedDate,
                        mutationTimestamp = mutationTimestamp,
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
                pendingSyncStore.setPendingExpenseSyncPayload(groupId, expenseId, pendingPayloadJson)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun flushPendingExpenseSyncs() {
        if (!connectivityStatus.isNetworkAvailable()) return

        pendingSyncStore.getPendingExpenseDeletions().forEach { pendingDeletion ->
            val deleteSucceeded = runCatching {
                withTimeoutOrNull(4000L) {
                    syncExpenseDeletionToRemote(
                        groupId = pendingDeletion.groupId,
                        expenseId = pendingDeletion.expenseId
                    )
                    true
                } ?: false
            }.getOrDefault(false)

            if (deleteSucceeded) {
                pendingSyncStore.removePendingExpenseDeletion(pendingDeletion.groupId, pendingDeletion.expenseId)
            } else {
                // Keep pending for later retry.
            }
        }

        pendingSyncStore.getPendingExpenseSyncs().forEach { pending ->
            val localExpense = cache.getExpenseById(pending.expenseId)
            val payload = pendingSyncStore.getPendingExpenseSyncPayload(pending.groupId, pending.expenseId)
                ?.let { decodePendingExpensePayloadJson(it) }
            val preferPayload = payload != null

            if (localExpense == null && payload == null) {
                // Keep pending. Without a local row or payload snapshot we cannot safely sync yet.
                return@forEach
            }

            val remoteVersion = runCatching {
                withTimeoutOrNull(2500L) {
                    fetchRemoteExpenseVersion(
                        groupId = pending.groupId,
                        expenseId = pending.expenseId
                    )
                }
            }.getOrNull()

            val mutationTimestamp = payload?.mutationTimestamp ?: 0L
            val remoteUpdatedAt = remoteVersion?.updatedAt
            val hasRemoteNewerState =
                mutationTimestamp > 0L &&
                    remoteVersion?.exists == true &&
                    remoteUpdatedAt != null &&
                    remoteUpdatedAt > mutationTimestamp

            if (hasRemoteNewerState) {
                pendingSyncStore.removePendingExpenseSync(pending.groupId, pending.expenseId)
                return@forEach
            }

            val syncExpenseId = pending.expenseId
            val syncAmount = if (preferPayload) payload!!.amount else localExpense?.amount ?: payload!!.amount
            val syncDescription = if (preferPayload) payload!!.description else localExpense?.description ?: payload!!.description
            val syncDate = if (preferPayload) payload!!.date else localExpense?.date ?: payload!!.date
            val syncMutationTimestamp = if (preferPayload) {
                payload!!.mutationTimestamp.takeIf { it > 0L } ?: payload.date
            } else {
                payload?.mutationTimestamp?.takeIf { it > 0L } ?: syncDate
            }
            val syncSplitMemberIds = if (preferPayload) {
                payload!!.splitMemberIds
            } else {
                localExpense
                    ?.let { expense -> cache.getExpenseSharesForExpense(expense.id).map { it.userId } }
                    ?: payload!!.splitMemberIds
            }
            val syncPayerContributions = if (preferPayload) {
                payload!!.payerContributions
            } else {
                localExpense
                    ?.payerContributions
                    ?.ifEmpty {
                        listOf(PayerContribution(userId = localExpense.paidByUserId, amount = localExpense.amount))
                    }
                    ?.associate { it.userId to it.amount }
                    ?: payload!!.payerContributions
            }
            val syncReceiptItems = if (preferPayload) payload!!.receiptItems else localExpense?.receiptItems ?: payload!!.receiptItems
            val sourceImagePath = payload?.imagePath ?: localExpense?.imagePath

            // If source has local image, attempt upload first
            if (sourceImagePath.isLocalReceiptPath()) {
                val syncedImagePath = resolveImagePathForSync(
                    groupId = pending.groupId,
                    expenseId = syncExpenseId,
                    currentImagePath = sourceImagePath
                )

                // Only proceed to sync if image upload succeeded (not local anymore)
                if (syncedImagePath.isLocalReceiptPath()) {
                    // Image still local; upload failed. Keep pending and try again later.
                    return@forEach
                }

                // Image uploaded successfully, now sync with the URL
                val syncSucceeded = runCatching {
                    withTimeoutOrNull(4000L) {
                        syncExpenseToRemote(
                            expenseId = syncExpenseId,
                            groupId = pending.groupId,
                            amount = syncAmount,
                            description = syncDescription,
                            date = syncDate,
                            mutationTimestamp = syncMutationTimestamp,
                            splitMemberIds = syncSplitMemberIds,
                            payerContributions = syncPayerContributions,
                            imagePath = syncedImagePath,
                            receiptItems = syncReceiptItems
                        )
                        true
                    } ?: false
                }.getOrDefault(false)

                if (syncSucceeded) {
                    pendingSyncStore.removePendingExpenseSync(pending.groupId, pending.expenseId)
                } else {
                    // Keep pending for later retry.
                }
            } else {
                // No local image, proceed with normal sync
                val syncSucceeded = runCatching {
                    withTimeoutOrNull(4000L) {
                        syncExpenseToRemote(
                            expenseId = syncExpenseId,
                            groupId = pending.groupId,
                            amount = syncAmount,
                            description = syncDescription,
                            date = syncDate,
                            mutationTimestamp = syncMutationTimestamp,
                            splitMemberIds = syncSplitMemberIds,
                            payerContributions = syncPayerContributions,
                            imagePath = sourceImagePath,
                            receiptItems = syncReceiptItems
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

    private suspend fun recomputeAndPersistDebts(groupId: String) {
        val group = cache.getGroupById(groupId) ?: run {
            // Group visibility can be transient during sync/auth transitions.
            // Do not wipe persisted debts on temporary cache misses.
            return
        }

        val expenses = cache.getExpensesByGroup(groupId)
        val existingDebts = cache.getDebtsByGroup(groupId)

        // Preserve previous debt snapshot while expenses are transiently unavailable.
        if (expenses.isEmpty() && existingDebts.isNotEmpty()) {
            return
        }

        val sharesByExpenseId = expenses.associate { expense ->
            expense.id to cache.getExpenseSharesForExpense(expense.id)
        }

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

        val debts = calculateDebtsUseCase.execute(
            groupMembers = participantIds.toList(),
            expenses = expenses,
            sharesByExpenseId = sharesByExpenseId
        )

        cache.replaceDebtsForGroup(groupId, debts)
    }

    private suspend fun syncExpenseToRemote(
        expenseId: String,
        groupId: String,
        amount: Double,
        description: String,
        date: Long,
        mutationTimestamp: Long,
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
                "updatedAt" to mutationTimestamp
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

    private fun buildPendingExpensePayloadJson(
        amount: Double,
        description: String,
        date: Long,
        mutationTimestamp: Long,
        splitMemberIds: List<String>,
        payerContributions: Map<String, Double>,
        imagePath: String?,
        receiptItems: List<ReceiptItem>
    ): String {
        val json = JSONObject().apply {
            put("amount", amount)
            put("description", description)
            put("date", date)
            put("mutationTimestamp", mutationTimestamp)
            put("splitMemberIds", JSONArray().apply {
                splitMemberIds.forEach { memberId -> put(memberId) }
            })
            put("payerContributions", JSONObject().apply {
                payerContributions.forEach { (userId, contribution) -> put(userId, contribution) }
            })
            if (!imagePath.isNullOrBlank()) {
                put("imagePath", imagePath)
            }
            put("receiptItems", JSONArray().apply {
                receiptItems.forEach { item ->
                    put(
                        JSONObject().apply {
                            put("name", item.name)
                            put("amount", item.amount)
                            if (item.quantity != null) put("quantity", item.quantity)
                            if (item.unitPrice != null) put("unitPrice", item.unitPrice)
                        }
                    )
                }
            })
        }
        return json.toString()
    }

    private fun decodePendingExpensePayloadJson(payloadJson: String): PendingExpensePayload? {
        return runCatching {
            val json = JSONObject(payloadJson)
            val splitMemberIds = buildList {
                val membersArray = json.optJSONArray("splitMemberIds") ?: JSONArray()
                for (index in 0 until membersArray.length()) {
                    val memberId = membersArray.optString(index).trim()
                    if (memberId.isNotBlank()) add(memberId)
                }
            }
            val payerContributions = buildMap {
                val payersJson = json.optJSONObject("payerContributions") ?: JSONObject()
                val keys = payersJson.keys()
                while (keys.hasNext()) {
                    val userId = keys.next()
                    val contribution = payersJson.optDouble(userId, 0.0)
                    if (userId.isNotBlank() && contribution > 0.0) {
                        put(userId, contribution)
                    }
                }
            }
            val receiptItems = buildList {
                val itemsArray = json.optJSONArray("receiptItems") ?: JSONArray()
                for (index in 0 until itemsArray.length()) {
                    val itemJson = itemsArray.optJSONObject(index) ?: continue
                    val name = itemJson.optString("name").trim()
                    val amount = itemJson.optDouble("amount", 0.0)
                    val quantity = itemJson.optDouble("quantity", Double.NaN)
                        .takeUnless { it.isNaN() || it <= 0.0 }
                    val unitPrice = itemJson.optDouble("unitPrice", Double.NaN)
                        .takeUnless { it.isNaN() || it <= 0.0 }
                    if (name.isNotBlank() && amount > 0.0) {
                        add(ReceiptItem(name = name, amount = amount, quantity = quantity, unitPrice = unitPrice))
                    }
                }
            }

            PendingExpensePayload(
                amount = json.optDouble("amount", 0.0),
                description = json.optString("description"),
                date = json.optLong("date", 0L),
                mutationTimestamp = json.optLong("mutationTimestamp", 0L),
                splitMemberIds = splitMemberIds,
                payerContributions = payerContributions,
                imagePath = json.optString("imagePath").takeIf { it.isNotBlank() },
                receiptItems = receiptItems
            )
        }.getOrNull()
    }

    private suspend fun fetchRemoteExpenseVersion(
        groupId: String,
        expenseId: String
    ): RemoteExpenseVersion {
        val snapshot = firestore.collection("groups")
            .document(groupId)
            .collection("expenses")
            .document(expenseId)
            .get()
            .awaitTaskResult()

        if (!snapshot.exists()) {
            return RemoteExpenseVersion(exists = false, updatedAt = null)
        }

        val rawUpdatedAt = snapshot.get("updatedAt")
        val parsedUpdatedAt = when (rawUpdatedAt) {
            is Number -> rawUpdatedAt.toLong()
            is java.util.Date -> rawUpdatedAt.time
            is com.google.firebase.Timestamp -> rawUpdatedAt.toDate().time
            is Map<*, *> -> {
                val seconds = (rawUpdatedAt["seconds"] as? Number)?.toLong() ?: 0L
                val nanos = (rawUpdatedAt["nanoseconds"] as? Number)?.toLong() ?: 0L
                (seconds * 1000L) + (nanos / 1_000_000L)
            }
            else -> null
        }

        return RemoteExpenseVersion(exists = true, updatedAt = parsedUpdatedAt)
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
