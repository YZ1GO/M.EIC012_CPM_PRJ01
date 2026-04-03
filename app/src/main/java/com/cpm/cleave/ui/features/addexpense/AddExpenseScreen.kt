package com.cpm.cleave.ui.features.addexpense

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import androidx.core.content.FileProvider
import com.cpm.cleave.ui.features.common.CameraPermissionPrompt

@Composable
fun AddExpenseScreen(viewModel: AddExpenseViewModel, onNavigateBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val titleTopSpacing = 24.dp
    val titleBottomSpacing = 32.dp
    val sectionSpacing = 16.dp
    val colorScheme = MaterialTheme.colorScheme
    var receiptBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var currentReceiptUri by remember { mutableStateOf<Uri?>(null) }
    var pendingReceiptCapture by remember { mutableStateOf(false) }
    var cameraPermissionDeniedMessage by remember { mutableStateOf<String?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (!success) return@rememberLauncherForActivityResult
        val receiptUri = currentReceiptUri ?: return@rememberLauncherForActivityResult
        val prepared = loadPreparedReceipt(context, receiptUri)
        receiptBitmap = prepared?.first
        viewModel.onReceiptImageSelected(prepared?.second)
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            pendingReceiptCapture = false
            cameraPermissionDeniedMessage = "Camera permission is required to capture a receipt"
            return@rememberLauncherForActivityResult
        }

        if (!pendingReceiptCapture) return@rememberLauncherForActivityResult
        pendingReceiptCapture = false
        cameraPermissionDeniedMessage = null

        val imageUri = createReceiptImageUri(context)
        if (imageUri == null) {
            viewModel.setErrorMessage("Could not prepare a receipt image file")
            return@rememberLauncherForActivityResult
        }

        currentReceiptUri = imageUri
        runCatching {
            cameraLauncher.launch(imageUri)
        }.onFailure {
            viewModel.setErrorMessage("Could not open the camera")
        }
    }

    cameraPermissionDeniedMessage?.let { message ->
        CameraPermissionPrompt(
            message = message,
            onDismiss = { cameraPermissionDeniedMessage = null },
            dismissLabel = "Back to expense"
        )
    }

    val labelForMember: (String) -> String = { memberId ->
        uiState.memberDisplayNames[memberId] ?: memberId
    }

    val contributionTotal = uiState.selectedPayerIds.sumOf { payerId ->
        uiState.payerAmountInputs[payerId]?.toDoubleOrNull() ?: 0.0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(titleTopSpacing))
        Text(
            text = "Add Expense",
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(titleBottomSpacing))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
                .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Text("Amount", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            TextField(
                value = uiState.amountInput,
                onValueChange = { viewModel.onAmountChanged(it) },
                placeholder = { Text("0.00") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colorScheme.surface.copy(alpha = 0.55f),
                    unfocusedContainerColor = colorScheme.surface.copy(alpha = 0.55f),
                    disabledContainerColor = colorScheme.surface.copy(alpha = 0.35f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
        }

        Spacer(modifier = Modifier.height(sectionSpacing))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
                .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Text("Receipt (optional)", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val hasCameraPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasCameraPermission) {
                        pendingReceiptCapture = true
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        return@Button
                    }

                    val imageUri = createReceiptImageUri(context) ?: run {
                        viewModel.setErrorMessage("Could not prepare a receipt image file")
                        return@Button
                    }

                    currentReceiptUri = imageUri
                    runCatching {
                        cameraLauncher.launch(imageUri)
                    }.onFailure {
                        viewModel.setErrorMessage("Could not open the camera")
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.secondaryContainer),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(if (receiptBitmap == null) "Capture receipt" else "Retake receipt")
            }

            if (receiptBitmap != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Image(
                    bitmap = receiptBitmap!!.asImageBitmap(),
                    contentDescription = "Receipt preview",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .border(1.dp, colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                )

                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.extractTotalFromReceipt() },
                        enabled = !uiState.isExtractingTotal,
                        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (uiState.isExtractingTotal) "Reading total..." else "Extract total")
                    }

                    Button(
                        onClick = { viewModel.extractItemsFromReceipt() },
                        enabled = !uiState.isExtractingItems,
                        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.secondary),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (uiState.isExtractingItems) "Reading items..." else "Extract items")
                    }
                }

                if (uiState.detectedReceiptItems.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Receipt items", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(6.dp))

                    uiState.detectedReceiptItems.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextField(
                                value = String.format(java.util.Locale.US, "%.3f", (item.quantity ?: 1.0)).trimEnd('0').trimEnd('.'),
                                onValueChange = { value -> viewModel.onReceiptItemQuantityChanged(index, value) },
                                placeholder = { Text("Qty") },
                                modifier = Modifier.weight(0.55f),
                                shape = RoundedCornerShape(10.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = colorScheme.surface.copy(alpha = 0.55f),
                                    unfocusedContainerColor = colorScheme.surface.copy(alpha = 0.55f),
                                    disabledContainerColor = colorScheme.surface.copy(alpha = 0.35f),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                ),
                                singleLine = true
                            )
                            TextField(
                                value = item.name,
                                onValueChange = { value -> viewModel.onReceiptItemNameChanged(index, value) },
                                placeholder = { Text("Item") },
                                modifier = Modifier.weight(1.1f),
                                shape = RoundedCornerShape(10.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = colorScheme.surface.copy(alpha = 0.55f),
                                    unfocusedContainerColor = colorScheme.surface.copy(alpha = 0.55f),
                                    disabledContainerColor = colorScheme.surface.copy(alpha = 0.35f),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                ),
                                singleLine = true
                            )
                            TextField(
                                value = if (item.amount == 0.0) "" else String.format(java.util.Locale.US, "%.2f", item.amount),
                                onValueChange = { value -> viewModel.onReceiptItemAmountChanged(index, value) },
                                placeholder = { Text("Subtotal") },
                                modifier = Modifier.weight(0.75f),
                                shape = RoundedCornerShape(10.dp),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = colorScheme.surface.copy(alpha = 0.55f),
                                    unfocusedContainerColor = colorScheme.surface.copy(alpha = 0.55f),
                                    disabledContainerColor = colorScheme.surface.copy(alpha = 0.35f),
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    disabledIndicatorColor = Color.Transparent
                                ),
                                singleLine = true
                            )
                            IconButton(onClick = { viewModel.removeReceiptItem(index) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove item")
                            }
                        }
                        if (index < uiState.detectedReceiptItems.lastIndex) {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.addReceiptItem() },
                        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.secondaryContainer),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Add item")
                    }
                    Button(
                        onClick = { viewModel.fillDescriptionFromReceiptItems() },
                        enabled = uiState.detectedReceiptItems.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.tertiaryContainer),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Use in description")
                    }
                }
            }

            uiState.receiptMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = it, color = colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(sectionSpacing))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
                .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Text("Description", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            TextField(
                value = uiState.description,
                onValueChange = { viewModel.onDescriptionChanged(it) },
                placeholder = { Text("Dinner, groceries, fuel...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = colorScheme.surface.copy(alpha = 0.55f),
                    unfocusedContainerColor = colorScheme.surface.copy(alpha = 0.55f),
                    disabledContainerColor = colorScheme.surface.copy(alpha = 0.35f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
        }

        Spacer(modifier = Modifier.height(sectionSpacing))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
                .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Text("Who paid and how much?", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.onBuyerModeChanged(BuyerMode.SINGLE_BUYER) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.buyerMode == BuyerMode.SINGLE_BUYER) colorScheme.primary else colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("You paid")
                }

                Button(
                    onClick = { viewModel.onBuyerModeChanged(BuyerMode.SELECT_BUYERS) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.buyerMode == BuyerMode.SELECT_BUYERS) colorScheme.primary else colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Multiple payers")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (uiState.buyerMode == BuyerMode.SINGLE_BUYER) {
                Text(
                    text = "Buyer: ${uiState.primaryBuyerId.takeIf { it.isNotBlank() }?.let(labelForMember) ?: "(unknown)"} pays full amount",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            } else {
                uiState.availablePayers.forEachIndexed { index, payer ->
                    val selected = uiState.selectedPayerIds.contains(payer)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { checked ->
                                viewModel.onPayerToggled(payer, checked)
                            }
                        )
                        Text(
                            text = labelForMember(payer),
                            modifier = Modifier.weight(1f)
                        )
                        TextField(
                            value = uiState.payerAmountInputs[payer].orEmpty(),
                            onValueChange = { value ->
                                if (selected) {
                                    viewModel.onPayerAmountChanged(payer, value)
                                }
                            },
                            enabled = selected,
                            placeholder = { Text("0.00") },
                            modifier = Modifier.weight(0.8f),
                            shape = RoundedCornerShape(10.dp),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = colorScheme.surface.copy(alpha = 0.55f),
                                unfocusedContainerColor = colorScheme.surface.copy(alpha = 0.55f),
                                disabledContainerColor = colorScheme.surface.copy(alpha = 0.35f),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            ),
                            singleLine = true
                        )
                    }

                    if (index < uiState.availablePayers.lastIndex) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Contributions: $contributionTotal / ${uiState.amountInput.ifBlank { "0.0" }}",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(sectionSpacing))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
                .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Text("How to split?", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.onSplitModeChanged(SplitMode.ALL_MEMBERS) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.splitMode == SplitMode.ALL_MEMBERS) colorScheme.primary else colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("All members")
                }

                Button(
                    onClick = { viewModel.onSplitModeChanged(SplitMode.SELECTED_MEMBERS) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.splitMode == SplitMode.SELECTED_MEMBERS) colorScheme.primary else colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Selected members")
                }
            }

            if (uiState.splitMode == SplitMode.SELECTED_MEMBERS) {
                Spacer(modifier = Modifier.height(6.dp))
                uiState.availablePayers.forEach { memberId ->
                    val isPayer = if (uiState.buyerMode == BuyerMode.SINGLE_BUYER) {
                        uiState.primaryBuyerId == memberId
                    } else {
                        uiState.selectedPayerIds.contains(memberId)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = uiState.selectedSplitMemberIds.contains(memberId),
                            enabled = !isPayer,
                            onCheckedChange = { checked ->
                                viewModel.onSplitMemberToggled(memberId, checked)
                            }
                        )
                        Text(
                            text = if (isPayer) "${labelForMember(memberId)} (payer, required)" else labelForMember(memberId),
                            color = colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        uiState.errorMessage?.let {
            Text(text = it, color = colorScheme.error, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = { viewModel.createExpense(onSuccess = onNavigateBack) },
            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
            shape = RoundedCornerShape(8.dp),
            enabled = !uiState.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(if (uiState.isLoading) "Creating..." else "Create Expense")
        }
    }
}

private fun Bitmap.toJpegBytes(quality: Int = 90): ByteArray {
    val output = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, output)
    return output.toByteArray()
}

private fun createReceiptImageUri(context: Context): Uri? {
    return runCatching {
        val dir = File(context.cacheDir, "receipt_images").apply { mkdirs() }
        val file = File(dir, "receipt_${System.currentTimeMillis()}.jpg")
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()
}

private fun loadPreparedReceipt(context: Context, uri: Uri): Pair<Bitmap, ByteArray>? {
    return runCatching {
        val file = uriToFile(context, uri) ?: return@runCatching null
        val sampledBitmap = decodeSampledBitmap(file, targetMaxDimension = 2200) ?: return@runCatching null
        val jpegBytes = sampledBitmap.toJpegBytes(quality = 92)
        sampledBitmap to jpegBytes
    }.getOrNull()
}

private fun uriToFile(context: Context, uri: Uri): File? {
    return if (uri.scheme == "file") {
        uri.path?.let { File(it) }
    } else {
        // FileProvider content uri backed by our own cache file.
        val temp = File(context.cacheDir, "receipt_copy_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }
        if (temp.exists()) temp else null
    }
}

private fun decodeSampledBitmap(file: File, targetMaxDimension: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    FileInputStream(file).use { input -> BitmapFactory.decodeStream(input, null, options) }
    val maxDim = maxOf(options.outWidth, options.outHeight)
    if (maxDim <= 0) return null

    var sampleSize = 1
    while (maxDim / sampleSize > targetMaxDimension) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }

    return FileInputStream(file).use { input -> BitmapFactory.decodeStream(input, null, decodeOptions) }
}
