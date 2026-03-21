package com.cpm.cleave

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.cpm.cleave.data.local.Cache
import com.cpm.cleave.data.repository.impl.AuthRepositoryImpl
import com.cpm.cleave.data.repository.impl.ExpenseRepositoryImpl
import com.cpm.cleave.data.repository.impl.GroupRepositoryImpl
import com.cpm.cleave.ui.theme.CleaveTheme
import com.cpm.cleave.ui.theme.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val cache = Cache(this)
        val authRepository = AuthRepositoryImpl(cache)
        val groupRepository = GroupRepositoryImpl(cache)
        val expenseRepository = ExpenseRepositoryImpl(cache)

        lifecycleScope.launch {
            authRepository.getOrCreateAnonymousUser()
        }

        setContent {
            CleaveTheme {
                MainScreen(
                    authRepository = authRepository,
                    groupRepository = groupRepository,
                    expenseRepository = expenseRepository
                )
            }
        }
    }
}