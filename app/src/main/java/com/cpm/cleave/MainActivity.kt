package com.cpm.cleave

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.cpm.cleave.di.AppContainer
import com.cpm.cleave.ui.theme.CleaveTheme
import com.cpm.cleave.ui.theme.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = AppContainer(this)
        val authRepository = appContainer.authRepository
        val groupRepository = appContainer.groupRepository
        val expenseRepository = appContainer.expenseRepository

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