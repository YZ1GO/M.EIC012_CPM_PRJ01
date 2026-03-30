package com.cpm.cleave.data.repository.impl

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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseRepositoryImpl(
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

    override suspend fun createExpense(
        groupId: String,
        amount: Double,
        description: String,
        splitMemberIds: List<String>,
        payerContributions: Map<String, Double>
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

            cache.insertExpenseWithSplit(
                expenseId = expenseId,
                amount = amount,
                description = description,
                date = now,
                groupId = groupId,
                payerContributions = normalizedContributions,
                memberIds = splitMemberIds
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
                        payerContributions = normalizedContributions
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

            val syncSucceeded = runCatching {
                withTimeoutOrNull(4000L) {
                    syncExpenseToRemote(
                        expenseId = localExpense.id,
                        groupId = pending.groupId,
                        amount = localExpense.amount,
                        description = localExpense.description,
                        date = localExpense.date,
                        splitMemberIds = shares.map { it.userId },
                        payerContributions = payers.associate { it.userId to it.amount }
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
        payerContributions: Map<String, Double>
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
                "updatedAt" to now
            ),
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
