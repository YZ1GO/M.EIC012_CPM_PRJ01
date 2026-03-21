package com.cpm.cleave

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.cpm.cleave.data.Cache
import com.cpm.cleave.data.Repository
import com.cpm.cleave.ui.theme.CleaveTheme
import com.cpm.cleave.ui.theme.MainScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val cache = Cache(this)
        val repository = Repository(cache)

        lifecycleScope.launch {
            repository.getOrCreateAnonymousUser()
        }

        setContent {
            CleaveTheme {
                MainScreen(repository)
            }
        }
    }
}