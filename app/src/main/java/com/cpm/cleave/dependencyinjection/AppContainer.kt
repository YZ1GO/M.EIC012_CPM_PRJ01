package com.cpm.cleave.dependencyinjection

import android.content.Context
import com.cpm.cleave.data.local.AuthSessionStore
import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.data.local.ConnectivityStatus
import com.cpm.cleave.data.local.PendingSyncStore
import com.cpm.cleave.data.repository.impl.AuthRepositoryImpl
import com.cpm.cleave.data.repository.impl.ExpenseRepositoryImpl
import com.cpm.cleave.data.repository.impl.GroupRepositoryImpl
import com.cpm.cleave.data.repository.impl.ScannerRepositoryImpl
import com.cpm.cleave.domain.usecase.CalculateDebtsUseCase

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val cache = Cache(appContext)
    private val authSessionStore = AuthSessionStore(appContext)
    private val pendingSyncStore = PendingSyncStore(appContext)
    private val connectivityStatus = ConnectivityStatus(appContext)
    private val calculateDebtsUseCase = CalculateDebtsUseCase(
        enableOptimizedSettlement = ENABLE_OPTIMIZED_SETTLEMENT
    )
    val scannerRepository = ScannerRepositoryImpl()

    val authRepository = AuthRepositoryImpl(
        authSessionStore = authSessionStore,
        cache = cache
    )
    val groupRepository = GroupRepositoryImpl(
        cache = cache,
        authSessionStore = authSessionStore,
        pendingSyncStore = pendingSyncStore,
        connectivityStatus = connectivityStatus
    )
    val expenseRepository = ExpenseRepositoryImpl(
        cache = cache,
        authSessionStore = authSessionStore,
        pendingSyncStore = pendingSyncStore,
        connectivityStatus = connectivityStatus,
        calculateDebtsUseCase = calculateDebtsUseCase
    )

    companion object {
        private const val ENABLE_OPTIMIZED_SETTLEMENT = true
    }
}
