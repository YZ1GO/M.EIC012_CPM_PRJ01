package com.cpm.cleave.data.local

import android.content.Context

class PendingSyncStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addPendingGroupSync(groupId: String) {
        if (groupId.isBlank()) return
        val updated = prefs.getStringSet(KEY_PENDING_GROUP_SYNCS, emptySet()).orEmpty().toMutableSet()
        updated.add(groupId)
        prefs.edit().putStringSet(KEY_PENDING_GROUP_SYNCS, updated).apply()
    }

    fun removePendingGroupSync(groupId: String) {
        val updated = prefs.getStringSet(KEY_PENDING_GROUP_SYNCS, emptySet()).orEmpty().toMutableSet()
        if (updated.remove(groupId)) {
            prefs.edit().putStringSet(KEY_PENDING_GROUP_SYNCS, updated).apply()
        }
    }

    fun getPendingGroupSyncs(): Set<String> {
        return prefs.getStringSet(KEY_PENDING_GROUP_SYNCS, emptySet()).orEmpty().toSet()
    }

    fun addPendingExpenseSync(groupId: String, expenseId: String) {
        if (groupId.isBlank() || expenseId.isBlank()) return
        val token = expenseToken(groupId, expenseId)
        val updated = prefs.getStringSet(KEY_PENDING_EXPENSE_SYNCS, emptySet()).orEmpty().toMutableSet()
        updated.add(token)
        prefs.edit().putStringSet(KEY_PENDING_EXPENSE_SYNCS, updated).apply()
    }

    fun removePendingExpenseSync(groupId: String, expenseId: String) {
        val token = expenseToken(groupId, expenseId)
        val updated = prefs.getStringSet(KEY_PENDING_EXPENSE_SYNCS, emptySet()).orEmpty().toMutableSet()
        if (updated.remove(token)) {
            prefs.edit().putStringSet(KEY_PENDING_EXPENSE_SYNCS, updated).apply()
        }
    }

    fun getPendingExpenseSyncs(): List<PendingExpenseSync> {
        return prefs.getStringSet(KEY_PENDING_EXPENSE_SYNCS, emptySet())
            .orEmpty()
            .mapNotNull { token ->
                val parts = token.split(TOKEN_SEPARATOR)
                if (parts.size != 2) return@mapNotNull null
                val groupId = parts[0]
                val expenseId = parts[1]
                if (groupId.isBlank() || expenseId.isBlank()) return@mapNotNull null
                PendingExpenseSync(groupId = groupId, expenseId = expenseId)
            }
    }

    fun addPendingExpenseDeletion(groupId: String, expenseId: String) {
        if (groupId.isBlank() || expenseId.isBlank()) return
        val token = expenseToken(groupId, expenseId)
        val updated = prefs.getStringSet(KEY_PENDING_EXPENSE_DELETIONS, emptySet()).orEmpty().toMutableSet()
        updated.add(token)
        prefs.edit().putStringSet(KEY_PENDING_EXPENSE_DELETIONS, updated).apply()
    }

    fun removePendingExpenseDeletion(groupId: String, expenseId: String) {
        val token = expenseToken(groupId, expenseId)
        val updated = prefs.getStringSet(KEY_PENDING_EXPENSE_DELETIONS, emptySet()).orEmpty().toMutableSet()
        if (updated.remove(token)) {
            prefs.edit().putStringSet(KEY_PENDING_EXPENSE_DELETIONS, updated).apply()
        }
    }

    fun getPendingExpenseDeletions(): List<PendingExpenseSync> {
        return prefs.getStringSet(KEY_PENDING_EXPENSE_DELETIONS, emptySet())
            .orEmpty()
            .mapNotNull { token ->
                val parts = token.split(TOKEN_SEPARATOR)
                if (parts.size != 2) return@mapNotNull null
                val groupId = parts[0]
                val expenseId = parts[1]
                if (groupId.isBlank() || expenseId.isBlank()) return@mapNotNull null
                PendingExpenseSync(groupId = groupId, expenseId = expenseId)
            }
    }

    private fun expenseToken(groupId: String, expenseId: String): String {
        return "$groupId$TOKEN_SEPARATOR$expenseId"
    }

    data class PendingExpenseSync(
        val groupId: String,
        val expenseId: String
    )

    companion object {
        private const val PREFS_NAME = "pending_sync_store"
        private const val KEY_PENDING_GROUP_SYNCS = "pending_group_syncs"
        private const val KEY_PENDING_EXPENSE_SYNCS = "pending_expense_syncs"
        private const val KEY_PENDING_EXPENSE_DELETIONS = "pending_expense_deletions"
        private const val TOKEN_SEPARATOR = "::"
    }
}
