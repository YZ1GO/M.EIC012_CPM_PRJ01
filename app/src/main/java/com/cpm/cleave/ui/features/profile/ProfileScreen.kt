package com.cpm.cleave.ui.features.profile

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import coil.compose.AsyncImage
import com.cpm.cleave.R 

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: ProfileViewModel,
    onSignedOut: () -> Unit = {},
    onRegisterRequested: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val credentialManager = remember(context) { CredentialManager.create(context) }
    
    var showPhotoMenu by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
        viewModel.onSaveProfilePhotoClicked()
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
                    } catch (_: Exception) {}
                    Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
                    onSignedOut()
                }
            }
        }
    }

    // --- Photo Options Bottom Sheet ---
    if (showPhotoMenu) {
        ModalBottomSheet(
            onDismissRequest = { showPhotoMenu = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "Profile Photo",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showPhotoMenu = false
                            pickImageLauncher.launch("image/*")
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Choose new photo", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            showPhotoMenu = false
                            // Passes empty string to clear the UI
                            viewModel.onProfilePhotoSelected("", null)
                            // Triggers save. Make sure your ViewModel allows saving empty URIs to delete it!
                            viewModel.onSaveProfilePhotoClicked()
                        }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Remove photo", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.error)
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Profile",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium
        )

        uiState.errorMessage?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        }

        if (uiState.isLoading || uiState.currentUser == null) {
            Spacer(modifier = Modifier.height(64.dp))
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            return@Column
        }

        val user = uiState.currentUser!!
        val photoModel = uiState.pendingPhotoUri ?: user.photoUrl
        val hasPhoto = !photoModel.isNullOrBlank()

        Spacer(modifier = Modifier.height(40.dp))

        // --- Avatar Section ---
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !uiState.isUploadingPhoto,
                        onClick = {
                            if (hasPhoto) {
                                showPhotoMenu = true 
                            } else {
                                pickImageLauncher.launch("image/*") 
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                // The Photo
                AsyncImage(
                    model = photoModel.takeIf { hasPhoto } ?: R.drawable.default_user_image,
                    contentDescription = "Profile photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            shape = CircleShape
                        )
                )
                
                // Uploading Overlay
                if (uiState.isUploadingPhoto) {
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .background(Color.Black.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color.White, 
                            strokeWidth = 3.dp, 
                            modifier = Modifier.size(32.dp)
                        )
                    }
                } 
                // Camera Badge (Only if no photo AND not uploading)
                else if (!hasPhoto) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = (-4).dp, y = (-4).dp)
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape)
                            .padding(3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddAPhoto,
                                contentDescription = "Add photo",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- User Info Card ---
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = user.name.ifBlank { "Anonymous User" },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                val isGuest = user.isAnonymous
                Box(
                    modifier = Modifier
                        .background(
                            color = if (isGuest) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isGuest) "Guest Account" else "Verified Account",
                        color = if (isGuest) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Groups, 
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Member of ${user.groups.size} group${if (user.groups.size != 1) "s" else ""}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            }
        }

        if (user.isAnonymous) {
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = "Info",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Guest Limitations",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "You can only join up to ${uiState.maxGroups} groups. Sign Up to lift this limit and secure your data.",
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // --- Action Buttons ---
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (user.isAnonymous) {
                Button(
                    onClick = viewModel::onRegisterClicked,
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Sign Up", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }

                Button(
                    onClick = viewModel::onLogInClicked,
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                ) {
                    Text("Log In", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Button(
                    onClick = viewModel::onSignOutClicked,
                    enabled = !uiState.isBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text("Sign out", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}