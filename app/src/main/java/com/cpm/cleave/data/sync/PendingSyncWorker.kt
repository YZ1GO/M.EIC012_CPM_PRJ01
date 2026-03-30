package com.cpm.cleave.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cpm.cleave.dependencyinjection.AppContainer
import com.cpm.cleave.CleaveApplication

class PendingSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val appContainer = (applicationContext as CleaveApplication).appContainer

        if (appContainer.authRepository.getCurrentUser().getOrNull() == null) {
            return Result.success()
        }

        val groupsResult = appContainer.groupRepository.getGroups()
        val groups = groupsResult.getOrElse {
            return Result.retry()
        }

        for (group in groups) {
            val expensesResult = appContainer.expenseRepository.getExpensesByGroup(group.id)
            if (expensesResult.isFailure) {
                return Result.retry()
            }
        }

        return Result.success()
    }
}
