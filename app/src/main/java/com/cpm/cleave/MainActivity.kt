package com.cpm.cleave

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import com.cpm.cleave.data.Cache
import com.cpm.cleave.data.Repository
import com.cpm.cleave.ui.theme.CleaveTheme
import com.cpm.cleave.ui.theme.MainScreen
import com.cpm.cleave.ui.theme.ViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: ViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val cache = Cache(this)
        val repository = Repository(
        )

        viewModel = ViewModel(repository)

        setContent {
            CleaveTheme {
                MainScreen()
            }
        }
    }
}