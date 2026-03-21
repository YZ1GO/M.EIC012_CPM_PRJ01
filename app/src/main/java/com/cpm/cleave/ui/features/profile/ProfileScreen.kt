package com.cpm.cleave.ui.features.profile

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpm.cleave.data.repository.contracts.IAuthRepository
import com.cpm.cleave.model.User
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(repository: IAuthRepository) {
    var currentUser by remember { mutableStateOf<User?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val limits = remember { repository.getAnonymousLimits() }
    val coroutineScope = rememberCoroutineScope()

    // TODO(remove-before-release): remove debug user-switch tools from profile UI.
    val isDebuggable = (LocalContext.current.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    LaunchedEffect(Unit) {
        repository.getCurrentUser()
            .onSuccess { currentUser = it }
            .onFailure { loadError = it.message ?: "Could not load profile" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Profile", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        loadError?.let {
            Text(text = it, color = Color.Red)
        }

        if (currentUser == null) {
            Text("Loading user...")
            return@Column
        }

        val user = currentUser!!
        Text("Name: ${user.name}")
        Text("Mode: ${if (user.isAnonymous) "Anonymous" else "Verified"}")
        Text("Groups joined: ${user.groups.size}")

        if (user.isAnonymous) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Anonymous limits", fontWeight = FontWeight.Medium)
            Text("- Max groups: ${limits.maxGroups}")
            Text("- Max total debt: ${limits.maxTotalDebt}")

            // TODO(remove-before-release): remove debug user-switch tools from profile UI.
            if (isDebuggable) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Debug tools", fontWeight = FontWeight.Medium)
                Text("Current user id: ${user.id}", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            repository.switchDebugAnonymousUser()
                                .onSuccess {
                                    currentUser = it
                                    loadError = null
                                }
                                .onFailure {
                                    loadError = it.message ?: "Could not switch debug user"
                                }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Switch Between User A/B", color = Color.White)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // TODO(remove-before-release): remove debug room database reset button.
                Button(
                    onClick = {
                        coroutineScope.launch {
                            repository.clearDebugDatabase()
                                .onSuccess {
                                    repository.switchDebugAnonymousUser()
                                        .onSuccess {
                                            currentUser = it
                                            loadError = null
                                        }
                                        .onFailure {
                                            loadError = it.message ?: "Cleared DB, but could not initialize debug user"
                                        }
                                }
                                .onFailure {
                                    loadError = it.message ?: "Could not clear local database"
                                }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Clear All Room Data (Debug)", color = Color.White)
                }
            }
        }
    }
}
