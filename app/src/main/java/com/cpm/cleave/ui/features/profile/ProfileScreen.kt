package com.cpm.cleave.ui.features.profile

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import coil.compose.AsyncImage

@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onSignedOut: () -> Unit = {},
    onRegisterRequested: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            viewModel.onProfilePhotoSelected(null, null)
            return@rememberLauncherForActivityResult
        }

        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        viewModel.onProfilePhotoSelected(uri.toString(), bytes)
    }

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

                ProfileUiEffect.ProfilePhotoSaved -> {
                    Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
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
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }

        if (uiState.isLoading || uiState.currentUser == null) {
            Text("Loading user...")
            return@Column
        }

        val user = uiState.currentUser!!
        val photoModel = uiState.pendingPhotoUri ?: user.photoUrl

        Text("Profile photo", fontWeight = FontWeight.Medium)
        if (!photoModel.isNullOrBlank()) {
            AsyncImage(
                model = photoModel,
                contentDescription = "Profile photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(48.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(48.dp))
            )
        } else {
            Text("No photo selected")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { pickImageLauncher.launch("image/*") },
                enabled = !uiState.isBusy && !uiState.isUploadingPhoto,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (uiState.pendingPhotoUri == null) "Choose photo" else "Change photo")
            }

            Button(
                onClick = viewModel::onSaveProfilePhotoClicked,
                enabled = uiState.pendingPhotoUri != null && !uiState.isBusy && !uiState.isUploadingPhoto,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (uiState.isUploadingPhoto) "Saving..." else "Save photo")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Name: ${user.name}")
        Text("Mode: ${if (user.isAnonymous) "Anonymous" else "Verified"}")
        Text("Groups joined: ${user.groups.size}")

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (user.isAnonymous) {
                Button(
                    onClick = viewModel::onLogInClicked,
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Log in")
                }

                Button(
                    onClick = viewModel::onRegisterClicked,
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Register account")
                }
            } else {
                Button(
                    onClick = viewModel::onSignOutClicked,
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Sign out")
                }
            }
        }

        if (user.isAnonymous) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Anonymous limits", fontWeight = FontWeight.Medium)
            Text("- Max groups: ${uiState.maxGroups}")
        }
    }
}
