package com.cpm.cleave.ui.features.joingroup

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cpm.cleave.ui.features.common.CameraPermissionPrompt
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

@Composable
fun JoinGroupScreen(viewModel: JoinGroupViewModel, onNavigateBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var cameraPermissionDeniedMessage by remember { mutableStateOf<String?>(null) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            cameraPermissionDeniedMessage = null
            viewModel.openScanner()
        } else {
            cameraPermissionDeniedMessage = "Camera permission is required to scan a QR code"
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        if (uiState.errorMessage != null) {
            delay(3000)
            viewModel.onJoinCodeChanged(uiState.joinCode)
        }
    }

    LaunchedEffect(uiState.joinCode) {
        if (uiState.joinCode.length == 8 && !uiState.isLoading && uiState.errorMessage == null) {
            keyboardController?.hide()
            viewModel.joinGroup(onSuccess = onNavigateBack)
        }
    }

    cameraPermissionDeniedMessage?.let { message ->
        CameraPermissionPrompt(
            message = message,
            onDismiss = { cameraPermissionDeniedMessage = null },
            dismissLabel = "Back to join group"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Join Group",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(48.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Join Code", 
                fontSize = 14.sp, 
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            JoinCodeInputField(
                code = uiState.joinCode,
                onCodeChange = { viewModel.onJoinCodeChanged(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

                    if (granted) {
                        viewModel.openScanner()
                    } else {
                        requestPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        shape = RoundedCornerShape(12.dp)
                    ),
                enabled = !uiState.isLoading
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = "Scan QR Code")
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        AnimatedVisibility(
            visible = uiState.errorMessage != null,
            enter = slideInVertically(
                initialOffsetY = { fullHeight -> fullHeight },
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(300)),
            exit = slideOutVertically(
                targetOffsetY = { fullHeight -> fullHeight },
                animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(300))
        ) {
            uiState.errorMessage?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .background(
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f), 
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Button(
            onClick = {
                keyboardController?.hide()
                viewModel.joinGroup(onSuccess = onNavigateBack)
            },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .height(52.dp)
                .fillMaxWidth(),
            enabled = !uiState.isLoading && uiState.joinCode.length == 8
        ) {
            Text(
                text = if (uiState.isLoading) "Joining..." else "Join Group",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (uiState.isScannerVisible) {
            JoinCodeScannerDialog(
                onDismiss = { viewModel.closeScanner() },
                onQrCodeDetected = { rawValue ->
                    viewModel.onQrCodeScanned(rawValue, onSuccess = onNavigateBack)
                }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun JoinCodeInputField(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Local state to track cursor and selection
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = code, selection = TextRange(code.length)))
    }

    // Keep internal state synced if external state clears the code
    LaunchedEffect(code) {
        if (code != textFieldValue.text) {
            textFieldValue = textFieldValue.copy(
                text = code,
                selection = TextRange(code.length)
            )
        }
    }

    LaunchedEffect(Unit) {
        delay(300)
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    BasicTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            val filteredText = newValue.text
                .filter { it.isLetterOrDigit() }
                .uppercase()
                .take(8)

            // Prevent crashing if a user pastes a string longer than 8 characters
            val safeStart = minOf(newValue.selection.start, filteredText.length)
            val safeEnd = minOf(newValue.selection.end, filteredText.length)

            if (filteredText != textFieldValue.text) {
                onCodeChange(filteredText)
            }

            textFieldValue = newValue.copy(
                text = filteredText,
                selection = TextRange(safeStart, safeEnd)
            )
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done
        ),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        decorationBox = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                repeat(8) { index ->
                    val char = textFieldValue.text.getOrNull(index)?.toString() ?: ""
                    
                    val selection = textFieldValue.selection
                    val isFocused = if (selection.collapsed) {
                        index == selection.start || (selection.start == 8 && index == 7)
                    } else {
                        index >= selection.start && index < selection.end
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .border(
                                width = if (isFocused) 2.dp else 1.dp,
                                color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(
                                color = if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.05f) else MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                                
                                if (index < textFieldValue.text.length) {
                                    // Highlight existing letter so it gets replaced when typing
                                    textFieldValue = textFieldValue.copy(selection = TextRange(index, index + 1))
                                } else {
                                    // Snap cursor to the end of the word if tapping an empty box
                                    textFieldValue = textFieldValue.copy(selection = TextRange(textFieldValue.text.length))
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = char,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun JoinCodeScannerDialog(
    onDismiss: () -> Unit,
    onQrCodeDetected: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val barcodeScanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )
    }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            barcodeScanner.close()
            cameraExecutor.shutdown()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text("Scan Group QR") },
        text = {
            Column {
                AndroidView(
                    factory = {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                        cameraProviderFuture.addListener(
                            {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.surfaceProvider = previewView.surfaceProvider
                                }

                                val analyzer = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also {
                                        it.setAnalyzer(
                                            cameraExecutor,
                                            JoinGroupQrAnalyzer(
                                                barcodeScanner = barcodeScanner,
                                                onCodeDetected = onQrCodeDetected
                                            )
                                        )
                                    }

                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analyzer
                                )
                            },
                            ContextCompat.getMainExecutor(context)
                        )

                        previewView
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(Color.Black, RoundedCornerShape(10.dp))
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text("Point the camera at the group's QR code")
            }
        }
    )
}