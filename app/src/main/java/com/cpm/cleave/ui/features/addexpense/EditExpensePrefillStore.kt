package com.cpm.cleave.ui.features.addexpense

import com.cpm.cleave.model.Expense
import java.util.concurrent.ConcurrentHashMap

object EditExpensePrefillStore {
    data class PrefillPayload(
        val expense: Expense,
        val displayNames: Map<String, String>
    )

    private val cache = ConcurrentHashMap<String, PrefillPayload>()

    private fun key(groupId: String, expenseId: String): String = "$groupId::$expenseId"

    fun put(groupId: String, expense: Expense, displayNames: Map<String, String>) {
        if (groupId.isBlank() || expense.id.isBlank()) return
        cache[key(groupId, expense.id)] = PrefillPayload(
            expense = expense,
            displayNames = displayNames
        )
    }

    fun take(groupId: String, expenseId: String): PrefillPayload? {
        if (groupId.isBlank() || expenseId.isBlank()) return null
        return cache.remove(key(groupId, expenseId))
    }
}
