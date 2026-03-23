package com.cpm.cleave

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.cpm.cleave.dependencyinjection.AppContainer
import com.cpm.cleave.ui.theme.CleaveTheme
import com.cpm.cleave.ui.theme.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = AppContainer(this)
        val authRepository = appContainer.authRepository
        val groupRepository = appContainer.groupRepository
        val expenseRepository = appContainer.expenseRepository

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