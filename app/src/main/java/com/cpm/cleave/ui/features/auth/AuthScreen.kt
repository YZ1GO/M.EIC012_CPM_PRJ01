package com.cpm.cleave.ui.features.auth

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.cpm.cleave.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(
    viewModel: AuthViewModel,
    onAuthenticated: () -> Unit,
    defaultRegisterMode: Boolean = false,
    showContinueAsGuest: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val webClientId = stringResource(id = R.string.default_web_client_id)
    var passwordVisible by remember { mutableStateOf(false) }
    val maxNameLength = 30

    val credentialManager = remember(context) { CredentialManager.create(context) }
    val googleIdOption = remember(webClientId) {
        GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)
            .build()
    }
    val credentialRequest = remember(googleIdOption) {
        GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
    }

    LaunchedEffect(defaultRegisterMode) {
        if (uiState.isRegisterMode != defaultRegisterMode) {
            viewModel.setRegisterMode(defaultRegisterMode)
        }
    }

    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            onAuthenticated()
        }
    }

    // Auto-dismiss Error Message after 1.5 seconds
    LaunchedEffect(uiState.errorMessage) {
        if (uiState.errorMessage != null) {
            delay(1500)
            viewModel.clearErrorMessage()
        }
    }

    // Auto-dismiss Success Message after 1.5 seconds
    LaunchedEffect(uiState.resetPasswordMessage) {
        if (uiState.resetPasswordMessage != null) {
            delay(1500)
            viewModel.clearResetPasswordMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding() 
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        // Header
        Text(
            text = if (uiState.isRegisterMode) "Create an account" else "Welcome back",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (uiState.isRegisterMode) "Sign up to start splitting expenses" else "Enter your details to sign in",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Inputs
        if (uiState.isRegisterMode) {
            OutlinedTextField(
                value = uiState.name,
                onValueChange = { 
                    if (it.length <= maxNameLength) viewModel.onNameChanged(it) 
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Name") },
                shape = RoundedCornerShape(12.dp),
                supportingText = {
                    Text(
                        text = "${uiState.name.length} / $maxNameLength",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = uiState.email,
            onValueChange = viewModel::onEmailChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Email") },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = uiState.password,
            onValueChange = viewModel::onPasswordChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Password") },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary
            ),
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        // --- Options & Forgot Password ---
        if (!uiState.isRegisterMode) {
            // 1. Forgot Password (Right Aligned - tied to Email)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = { viewModel.sendPasswordResetEmail() },
                    enabled = !uiState.isLoading,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text("Forgot password?", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Global Merge Data Toggle Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .clickable { viewModel.setMergeGuestDataOnSignIn(!uiState.mergeGuestDataOnSignIn) }
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = uiState.mergeGuestDataOnSignIn,
                    onCheckedChange = null,
                    enabled = !uiState.isLoading
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Column {
                    Text(
                        text = "Merge local guest data",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Applies to both Email and Google Sign-In",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        } else {
            // In Register Mode, merging happens automatically to upgrade the guest account.
            Spacer(modifier = Modifier.height(32.dp))
        }

        // --- FLUID ANIMATED MESSAGES ---
        AnimatedVisibility(
            visible = uiState.errorMessage != null || uiState.resetPasswordMessage != null,
            enter = expandVertically(
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(300)),
            exit = shrinkVertically(
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(300))
        ) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                uiState.errorMessage?.let { message ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = message, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }

                uiState.resetPasswordMessage?.let { message ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = Color(0xFF4CAF50).copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircleOutline, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = message, color = Color(0xFF2E7D32), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // Primary Action
        Button(
            onClick = {
                keyboardController?.hide()
                if (uiState.isRegisterMode) viewModel.signUpWithEmail() else viewModel.signInWithEmail()
            },
            enabled = !uiState.isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text(
                text = if (uiState.isRegisterMode) "Sign Up" else "Sign In",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Divider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "or",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Social Login
        OutlinedButton(
            onClick = {
                keyboardController?.hide()
                val activity = context as? Activity
                if (activity == null) {
                    viewModel.setTransientError("Google sign-in is unavailable")
                    return@OutlinedButton
                }

                coroutineScope.launch {
                    try {
                        val result = credentialManager.getCredential(context = activity, request = credentialRequest)
                        val credential = result.credential
                        if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                            val googleTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                            viewModel.signInWithGoogleIdToken(googleTokenCredential.idToken)
                        } else {
                            viewModel.setTransientError("Could not sign in with Google")
                        }
                    } catch (_: NoCredentialException) {
                        viewModel.setTransientError("No Google account found. Add one in device settings.")
                    } catch (_: GetCredentialCancellationException) {
                        viewModel.setTransientError("Google sign-in cancelled")
                    } catch (e: GetCredentialException) {
                        viewModel.setTransientError("Could not access Google credentials right now.")
                    } catch (_: Exception) {
                        viewModel.setTransientError("Could not sign in with Google")
                    }
                }
            },
            enabled = !uiState.isLoading,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("Continue with Google", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Bottom Toggles
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TextButton(
                onClick = {
                    keyboardController?.hide()
                    viewModel.setRegisterMode(!uiState.isRegisterMode) 
                },
                enabled = !uiState.isLoading
            ) {
                Text(
                    text = if (uiState.isRegisterMode) "Already have an account? Sign In" else "Don't have an account? Sign Up",
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (showContinueAsGuest) {
                TextButton(
                    onClick = {
                        keyboardController?.hide()
                        viewModel.continueAsGuest() 
                    },
                    enabled = !uiState.isLoading
                ) {
                    Text(
                        text = "Continue as guest",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}