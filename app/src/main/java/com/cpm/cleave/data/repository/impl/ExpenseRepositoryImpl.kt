package com.cpm.cleave.data.repository.impl

import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.data.local.AuthSessionStore
import com.cpm.cleave.domain.repository.contracts.IExpenseRepository
import com.cpm.cleave.domain.usecase.CalculateDebtsUseCase
import com.cpm.cleave.domain.usecase.CreateExpenseCommand
import com.cpm.cleave.domain.usecase.CreateExpenseUseCase
import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

class ExpenseRepositoryImpl(
    private val cache: Cache,
    private val authSessionStore: AuthSessionStore,
    private val calculateDebtsUseCase: CalculateDebtsUseCase = CalculateDebtsUseCase(),
    private val createExpenseUseCase: CreateExpenseUseCase = CreateExpenseUseCase(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) : IExpenseRepository {

    override suspend fun getExpensesByGroup(groupId: String): Result<List<Expense>> {
        return try {
            Result.success(cache.getExpensesByGroup(groupId))
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

            Result.success(
                calculateDebtsUseCase.execute(
                    groupMembers = group.members,
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
        paidByUserId: String,
        splitMemberIds: List<String>
    ): Result<Unit> {
        // TODO(expense-advanced): support multi-payer expenses by accepting payer contributions and validating sum == total.
        return try {
            val currentUser = authSessionStore.getActiveUser()
            val group = cache.getGroupById(groupId)

            val command = CreateExpenseCommand(
                amount = amount,
                paidByUserId = paidByUserId,
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
                paidBy = paidByUserId,
                memberIds = splitMemberIds
            )

            syncExpenseToRemote(
                expenseId = expenseId,
                groupId = groupId,
                amount = amount,
                description = description,
                date = now,
                paidByUserId = paidByUserId,
                splitMemberIds = splitMemberIds
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun syncExpenseToRemote(
        expenseId: String,
        groupId: String,
        amount: Double,
        description: String,
        date: Long,
        paidByUserId: String,
        splitMemberIds: List<String>
    ) {
        val now = System.currentTimeMillis()
        val groupRef = firestore.collection("groups").document(groupId)
        val expenseRef = groupRef.collection("expenses").document(expenseId)

        expenseRef.set(
            mapOf(
                "amount" to amount,
                "description" to description,
                "date" to date,
                "groupId" to groupId,
                "paidByUserId" to paidByUserId,
                "updatedAt" to now
            ),
            SetOptions.merge()
        ).awaitTaskResult()

        if (splitMemberIds.isEmpty()) return
        val splitAmount = amount / splitMemberIds.size
        splitMemberIds.forEach { memberId ->
            expenseRef.collection("splits").document(memberId)
                .set(
                    mapOf(
                        "userId" to memberId,
                        "amount" to splitAmount,
                        "updatedAt" to now
                    ),
                    SetOptions.merge()
                )
                .awaitTaskResult()
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
}
