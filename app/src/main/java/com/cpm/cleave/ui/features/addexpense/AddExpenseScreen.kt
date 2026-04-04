package com.cpm.cleave.ui.features.addexpense

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.cpm.cleave.ui.features.common.CameraPermissionPrompt
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

@Composable
fun AddExpenseScreen(viewModel: AddExpenseViewModel, onNavigateBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val colorScheme = MaterialTheme.colorScheme
    val canEditExpense = !uiState.isEditing || uiState.canEditExpense

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

    val totalExpenseAmount = uiState.amountInput.toDoubleOrNull() ?: 0.0
    val contributionTotal = uiState.selectedPayerIds.sumOf { payerId ->
        uiState.payerAmountInputs[payerId]?.toDoubleOrNull() ?: 0.0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        if (uiState.isEditing && uiState.isLoading && uiState.amountInput.isBlank() && uiState.description.isBlank()) {
            Spacer(modifier = Modifier.height(48.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colorScheme.primary)
            }
            return@Column
        }

        AnimatedVisibility(
            visible = uiState.isEditing && !uiState.canEditExpense,
            enter = fadeIn(animationSpec = tween(250)) + expandVertically(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(250)) + shrinkVertically(animationSpec = tween(250))
        ) {
            Surface(
                color = colorScheme.errorContainer.copy(alpha = 0.45f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Debt payment expenses can't be edited.",
                    color = colorScheme.onErrorContainer,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Header ---
        Text(
            text = if (uiState.isEditing) "Edit Expense" else "Add Expense",
            color = colorScheme.primary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(32.dp))

        // --- Description Field ---
        Text("Description", fontSize = 14.sp, color = colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = uiState.description,
            onValueChange = { viewModel.onDescriptionChanged(it) },
            placeholder = { Text("Dinner, groceries, fuel...", color = colorScheme.onSurface.copy(alpha = 0.3f)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = canEditExpense,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = colorScheme.outlineVariant,
                focusedBorderColor = colorScheme.primary
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // --- Amount Field ---
        Text("Amount", fontSize = 14.sp, color = colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = uiState.amountInput,
            onValueChange = { viewModel.onAmountChanged(it) },
            placeholder = { Text("0.00", color = colorScheme.onSurface.copy(alpha = 0.3f)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = canEditExpense,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = colorScheme.outlineVariant,
                focusedBorderColor = colorScheme.primary
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // --- Receipt Scanner Card ---
        Surface(
            color = colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Receipt Scanner", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurface)
                    if (receiptBitmap != null) {
                        TextButton(
                            onClick = {
                                if (canEditExpense) {
                                    receiptBitmap = null
                                    viewModel.onReceiptImageSelected(null)
                                }
                            },
                            enabled = canEditExpense,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text("Clear", fontSize = 13.sp, color = colorScheme.error)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))

                if (receiptBitmap != null) {
                    Image(
                        bitmap = receiptBitmap!!.asImageBitmap(),
                        contentDescription = "Receipt preview",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .clickable(enabled = canEditExpense) {
                                val imageUri = createReceiptImageUri(context)
                                if (imageUri != null) {
                                    currentReceiptUri = imageUri
                                    cameraLauncher.launch(imageUri)
                                }
                            }
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.extractTotalFromReceipt() },
                            enabled = canEditExpense && !uiState.isExtractingTotal,
                            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primaryContainer, contentColor = colorScheme.onPrimaryContainer),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) {
                            Text(if (uiState.isExtractingTotal) "Reading..." else "Get Total", fontSize = 13.sp)
                        }
                        Button(
                            onClick = { viewModel.extractItemsFromReceipt() },
                            enabled = canEditExpense && !uiState.isExtractingItems,
                            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.secondaryContainer, contentColor = colorScheme.onSecondaryContainer),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(40.dp)
                        ) {
                            Text(if (uiState.isExtractingItems) "Reading..." else "Get Items", fontSize = 13.sp)
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            val hasCameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            if (!hasCameraPermission) {
                                pendingReceiptCapture = true
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            } else {
                                val imageUri = createReceiptImageUri(context)
                                if (imageUri == null) {
                                    viewModel.setErrorMessage("Could not prepare a receipt image file")
                                } else {
                                    currentReceiptUri = imageUri
                                    runCatching { cameraLauncher.launch(imageUri) }
                                        .onFailure { viewModel.setErrorMessage("Could not open the camera") }
                                }
                            }
                        },
                        enabled = canEditExpense,
                        colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(imageVector = Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("Capture Receipt")
                    }
                }

                // Receipt Items List
                if (uiState.detectedReceiptItems.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Detected Items", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(8.dp))

                    uiState.detectedReceiptItems.forEachIndexed { index, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = String.format(Locale.US, "%.3f", (item.quantity ?: 1.0)).trimEnd('0').trimEnd('.'),
                                onValueChange = { value -> viewModel.onReceiptItemQuantityChanged(index, value) },
                                modifier = Modifier.weight(0.5f),
                                enabled = canEditExpense,
                                shape = RoundedCornerShape(8.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            OutlinedTextField(
                                value = item.name,
                                onValueChange = { value -> viewModel.onReceiptItemNameChanged(index, value) },
                                modifier = Modifier.weight(1.2f),
                                enabled = canEditExpense,
                                shape = RoundedCornerShape(8.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = if (item.amount == 0.0) "" else String.format(Locale.US, "%.2f", item.amount),
                                onValueChange = { value -> viewModel.onReceiptItemAmountChanged(index, value) },
                                modifier = Modifier.weight(0.7f),
                                enabled = canEditExpense,
                                shape = RoundedCornerShape(8.dp),
                                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                            IconButton(
                                onClick = { viewModel.removeReceiptItem(index) },
                                enabled = canEditExpense,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = colorScheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                        if (index < uiState.detectedReceiptItems.lastIndex) {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.addReceiptItem() },
                            enabled = canEditExpense,
                            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.secondaryContainer, contentColor = colorScheme.onSecondaryContainer),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(36.dp)
                        ) {
                            Text("Add Row", fontSize = 12.sp)
                        }
                        Button(
                            onClick = { viewModel.fillDescriptionFromReceiptItems() },
                            enabled = canEditExpense && uiState.detectedReceiptItems.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.tertiaryContainer, contentColor = colorScheme.onTertiaryContainer),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(36.dp)
                        ) {
                            Text("Use in description", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                uiState.receiptMessage?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = it, color = colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- Payer Logic ---
        Text("Who Paid?", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
        Spacer(modifier = Modifier.height(12.dp))
        
        // Segmented Toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (uiState.buyerMode == BuyerMode.SINGLE_BUYER) colorScheme.surface else Color.Transparent)
                    .clickable(enabled = canEditExpense) { viewModel.onBuyerModeChanged(BuyerMode.SINGLE_BUYER) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Single Payer", 
                    fontSize = 14.sp, 
                    fontWeight = if (uiState.buyerMode == BuyerMode.SINGLE_BUYER) FontWeight.Bold else FontWeight.Medium,
                    color = if (uiState.buyerMode == BuyerMode.SINGLE_BUYER) colorScheme.primary else colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (uiState.buyerMode == BuyerMode.SELECT_BUYERS) colorScheme.surface else Color.Transparent)
                    .clickable(enabled = canEditExpense) {
                        viewModel.onBuyerModeChanged(BuyerMode.SELECT_BUYERS)
                        if (uiState.selectedPayerIds.isEmpty() && totalExpenseAmount > 0) {
                            val splitAmount = totalExpenseAmount / uiState.availablePayers.size
                            val formattedSplit = String.format(Locale.US, "%.2f", splitAmount)
                            uiState.availablePayers.forEach { p ->
                                viewModel.onPayerToggled(p, true)
                                viewModel.onPayerAmountChanged(p, formattedSplit)
                            }
                        }
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Multiple Payers", 
                    fontSize = 14.sp, 
                    fontWeight = if (uiState.buyerMode == BuyerMode.SELECT_BUYERS) FontWeight.Bold else FontWeight.Medium,
                    color = if (uiState.buyerMode == BuyerMode.SELECT_BUYERS) colorScheme.primary else colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.buyerMode == BuyerMode.SINGLE_BUYER) {
            val primaryPayerLabel = labelForMember(uiState.primaryBuyerId)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    text = if (!uiState.isEditing) {
                        "You pay the full amount"
                    } else if (uiState.primaryBuyerId.isBlank() || primaryPayerLabel.equals("You", ignoreCase = true)) {
                        "You pay the full amount"
                    } else {
                        "$primaryPayerLabel pays the full amount"
                    },
                    color = colorScheme.onPrimaryContainer,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            uiState.availablePayers.forEachIndexed { index, payer ->
                val selected = uiState.selectedPayerIds.contains(payer)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { checked -> if (canEditExpense) {
                            viewModel.onPayerToggled(payer, checked)

                            val newSelectedPayers = if (checked) uiState.selectedPayerIds + payer else uiState.selectedPayerIds - payer
                            if (newSelectedPayers.isNotEmpty() && totalExpenseAmount > 0) {
                                val splitAmount = totalExpenseAmount / newSelectedPayers.size
                                val formattedSplit = String.format(Locale.US, "%.2f", splitAmount)
                                newSelectedPayers.forEach { pId ->
                                    viewModel.onPayerAmountChanged(pId, formattedSplit)
                                }
                            }
                        } }
                    )
                    Text(
                        text = labelForMember(payer),
                        modifier = Modifier.weight(1f),
                        fontSize = 15.sp
                    )
                    OutlinedTextField(
                        value = uiState.payerAmountInputs[payer].orEmpty(),
                        onValueChange = { value -> 
                            if (selected) {
                                viewModel.onPayerAmountChanged(payer, value)
                                
                                val newAmount = value.toDoubleOrNull() ?: 0.0
                                val otherPayers = uiState.selectedPayerIds - payer
                                
                                if (otherPayers.isNotEmpty()) {
                                    val remainder = maxOf(0.0, totalExpenseAmount - newAmount)
                                    val splitAmount = remainder / otherPayers.size
                                    val formattedSplit = String.format(Locale.US, "%.2f", splitAmount)
                                    
                                    otherPayers.forEach { pId ->
                                        viewModel.onPayerAmountChanged(pId, formattedSplit)
                                    }
                                }
                            } 
                        },
                        enabled = canEditExpense && selected,
                        placeholder = { Text("0.00") },
                        modifier = Modifier
                            .weight(0.7f)
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    val currentVal = uiState.payerAmountInputs[payer].orEmpty()
                                    val asDouble = currentVal.toDoubleOrNull()
                                    if (asDouble != null) {
                                        viewModel.onPayerAmountChanged(payer, String.format(Locale.US, "%.2f", asDouble))
                                    }
                                }
                            },
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = colorScheme.outlineVariant,
                            focusedBorderColor = colorScheme.primary
                        )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                val formattedContribution = String.format(Locale.US, "%.2f", contributionTotal)
                val formattedTotal = String.format(Locale.US, "%.2f", totalExpenseAmount)
                
                Text(
                    text = "Total input: $formattedContribution / $formattedTotal",
                    color = if (contributionTotal > totalExpenseAmount + 0.01) colorScheme.error else colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- Split Logic ---
        Text("How to Split?", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
        Spacer(modifier = Modifier.height(12.dp))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (uiState.splitMode == SplitMode.ALL_MEMBERS) colorScheme.surface else Color.Transparent)
                    .clickable(enabled = canEditExpense) { viewModel.onSplitModeChanged(SplitMode.ALL_MEMBERS) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "All Members", 
                    fontSize = 14.sp, 
                    fontWeight = if (uiState.splitMode == SplitMode.ALL_MEMBERS) FontWeight.Bold else FontWeight.Medium,
                    color = if (uiState.splitMode == SplitMode.ALL_MEMBERS) colorScheme.primary else colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (uiState.splitMode == SplitMode.SELECTED_MEMBERS) colorScheme.surface else Color.Transparent)
                    .clickable(enabled = canEditExpense) { viewModel.onSplitModeChanged(SplitMode.SELECTED_MEMBERS) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Custom Split", 
                    fontSize = 14.sp, 
                    fontWeight = if (uiState.splitMode == SplitMode.SELECTED_MEMBERS) FontWeight.Bold else FontWeight.Medium,
                    color = if (uiState.splitMode == SplitMode.SELECTED_MEMBERS) colorScheme.primary else colorScheme.onSurfaceVariant
                )
            }
        }

        if (uiState.splitMode == SplitMode.SELECTED_MEMBERS) {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = colorScheme.surfaceVariant.copy(alpha = 0.2f),
                border = androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    uiState.availablePayers.forEach { memberId ->
                        val isPayer = if (uiState.buyerMode == BuyerMode.SINGLE_BUYER) {
                            uiState.primaryBuyerId == memberId
                        } else {
                            uiState.selectedPayerIds.contains(memberId)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = uiState.selectedSplitMemberIds.contains(memberId),
                                enabled = canEditExpense && !isPayer,
                                onCheckedChange = { checked -> if (canEditExpense) viewModel.onSplitMemberToggled(memberId, checked) }
                            )
                            Text(
                                text = if (isPayer) "${labelForMember(memberId)} (Payer)" else labelForMember(memberId),
                                color = if (isPayer) colorScheme.primary else colorScheme.onSurface,
                                fontSize = 15.sp,
                                fontWeight = if (isPayer) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- Error Pill ---
        AnimatedVisibility(
            visible = uiState.errorMessage != null,
            enter = expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(300)),
            exit = shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(300))
        ) {
            uiState.errorMessage?.let { message ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .background(colorScheme.error.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = "Error", tint = colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = message, color = colorScheme.error, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // --- Save Action ---
        Button(
            onClick = { viewModel.submitExpense(onSuccess = onNavigateBack) },
            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
            shape = RoundedCornerShape(12.dp),
            enabled = canEditExpense && !uiState.isLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            val actionText = if (uiState.isEditing) "Save Changes" else "Create Expense"
            val loadingText = if (uiState.isEditing) "Saving..." else "Creating..."
            Text(
                text = if (uiState.isLoading) loadingText else actionText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
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