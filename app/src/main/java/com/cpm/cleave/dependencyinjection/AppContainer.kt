package com.cpm.cleave.dependencyinjection

import android.content.Context
import com.cpm.cleave.data.local.AuthSessionStore
import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.data.repository.impl.AuthRepositoryImpl
import com.cpm.cleave.data.repository.impl.ExpenseRepositoryImpl
import com.cpm.cleave.data.repository.impl.GroupRepositoryImpl

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val cache = Cache(appContext)
    private val authSessionStore = AuthSessionStore(appContext)

    val authRepository = AuthRepositoryImpl(
        authSessionStore = authSessionStore,
        cache = cache
    )
    val groupRepository = GroupRepositoryImpl(cache, authSessionStore)
    val expenseRepository = ExpenseRepositoryImpl(cache, authSessionStore)
}
