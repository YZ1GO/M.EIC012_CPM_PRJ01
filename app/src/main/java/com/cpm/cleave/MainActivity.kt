package com.cpm.cleave

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.cpm.cleave.dependencyinjection.AppContainer
import com.cpm.cleave.ui.theme.CleaveTheme
import com.cpm.cleave.ui.theme.MainScreen
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val deepLinkJoinCodeState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        deepLinkJoinCodeState.value = extractJoinCodeFromIntent(intent)

        val appContainer = AppContainer(this)
        val authRepository = appContainer.authRepository
        val groupRepository = appContainer.groupRepository
        val expenseRepository = appContainer.expenseRepository
        val scannerRepository = appContainer.scannerRepository

        setContent {
            CleaveTheme {
                MainScreen(
                    authRepository = authRepository,
                    groupRepository = groupRepository,
                    expenseRepository = expenseRepository,
                    scannerRepository = scannerRepository,
                    pendingDeepLinkJoinCode = deepLinkJoinCodeState.value
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val joinCode = extractJoinCodeFromIntent(intent)
        if (!joinCode.isNullOrBlank()) {
            deepLinkJoinCodeState.value = joinCode
        }
    }

    private fun extractJoinCodeFromIntent(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null

        val uri = intent.data ?: return null

        val fromQuery = uri.getQueryParameter("joinCode")
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }

        if (fromQuery != null) return fromQuery

        val fromPath = uri.lastPathSegment
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() }

        return fromPath
    }
}