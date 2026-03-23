package com.cpm.cleave.ui.features.profile

import android.content.pm.ApplicationInfo
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onSignedOut: () -> Unit = {},
    onRegisterRequested: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val isDebuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is ProfileUiEffect.ShowMessage -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }

                ProfileUiEffect.NavigateToSignIn -> {
                    Toast.makeText(context, "Sign in to your account", Toast.LENGTH_SHORT).show()
                    onSignedOut()
                }

                ProfileUiEffect.NavigateToRegister -> {
                    Toast.makeText(context, "Create your account", Toast.LENGTH_SHORT).show()
                    onRegisterRequested()
                }

                ProfileUiEffect.SignedOut -> {
                    try {
                        credentialManager.clearCredentialState(ClearCredentialStateRequest())
                    } catch (_: Exception) {
                        // Best effort: sign-out should still complete even if provider state clear fails.
                    }
                    Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
                    onSignedOut()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Profile", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        uiState.errorMessage?.let {
            Text(text = it, color = Color.Red)
        }

        if (uiState.isLoading || uiState.currentUser == null) {
            Text("Loading user...")
            return@Column
        }

        val user = uiState.currentUser!!
        Text("Name: ${user.name}")
        Text("Mode: ${if (user.isAnonymous) "Anonymous" else "Verified"}")
        Text("Groups joined: ${user.groups.size}")

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (user.isAnonymous) {
                Button(
                    onClick = viewModel::onLogInClicked,
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Log in", color = Color.White)
                }

                Button(
                    onClick = viewModel::onRegisterClicked,
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00695C)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Register account", color = Color.White)
                }
            } else {
                Button(
                    onClick = viewModel::onSignOutClicked,
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF37474F)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Sign out", color = Color.White)
                }
            }
        }

        if (user.isAnonymous) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Anonymous limits", fontWeight = FontWeight.Medium)
            Text("- Max groups: ${uiState.maxGroups}")
            Text("- Max total debt: ${uiState.maxTotalDebt}")

            // TODO: delete
            if (isDebuggable) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Debug tools", fontWeight = FontWeight.Medium)
                Text("Current user id: ${user.id}", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = viewModel::onSwitchDebugUserClicked,
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Switch Between User A/B", color = Color.White)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = viewModel::onClearDebugDataClicked,
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB00020)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Clear All Room Data (Debug)", color = Color.White)
                }
            }
        }
    }
}
