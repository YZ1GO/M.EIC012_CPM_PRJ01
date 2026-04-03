package com.cpm.cleave.ui.features.groups

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cpm.cleave.R
import com.cpm.cleave.model.Group
import com.cpm.cleave.model.Expense
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import coil.compose.AsyncImage

@Composable
fun GroupsScreen(groupsViewModel: GroupsViewModel, onGroupClick: (String) -> Unit) {
    val uiState by groupsViewModel.uiState.collectAsState()
    val displayedGroups = if (uiState.searchQuery.isBlank()) {
        uiState.groups
    } else {
        val query = uiState.searchQuery.trim()
        uiState.groups.filter { group ->
            group.name.contains(query, ignoreCase = true) ||
                group.joinCode.contains(query, ignoreCase = true)
        }
    }

    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { groupsViewModel.onSearchQueryChanged(it) },
                placeholder = { Text("Search") },
                trailingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).height(56.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                    }
                )
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (uiState.isLoading) {
            Text("Loading groups...")
            return@Column
        }

        uiState.errorMessage?.let { message ->
            Text(text = message, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { groupsViewModel.loadGroups() }) {
                Text("Retry")
            }
            return@Column
        }

        if (displayedGroups.isEmpty()) {
            Text(
                text = if (uiState.searchQuery.isBlank()) "No groups yet." else "No matching groups.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            items(displayedGroups) { group ->
                GroupListItem(group = group, onClick = { onGroupClick(group.id) })
            }
        }
    }
}

@Composable
fun GroupListItem(group: Group, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val groupImageUrl = group.imageUrl?.takeIf { it.isNotBlank() }
        Box(
            modifier = Modifier
                .size(64.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = groupImageUrl ?: R.drawable.default_group_image,
                contentDescription = "Group image",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column {
            Text(
                text = group.name,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
            Text(
                text = "Currency: ${group.currency}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
            Text(
                text = "Code: ${group.joinCode}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun GroupDetailsScreen(
    viewModel: GroupDetailsViewModel,
    onAddExpenseClick: (String, String?) -> Unit,
    onGroupDeleted: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val titleTopSpacing = 20.dp
    val titleBottomSpacing = 18.dp
    val sectionSpacing = 16.dp
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showQrDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmationDialog by remember { mutableStateOf(false) }
    var selectedReceiptUrl by remember { mutableStateOf<String?>(null) }
    val colorScheme = MaterialTheme.colorScheme

    LaunchedEffect(Unit) { viewModel.refreshGroupData() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshGroupData()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sectionModifier = Modifier
        .fillMaxWidth()
        .background(colorScheme.surfaceVariant.copy(alpha = 0.24f), RoundedCornerShape(16.dp))
        .border(1.dp, colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
        .padding(12.dp)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(titleTopSpacing))

        val currentGroup = uiState.group
        if (currentGroup == null) {
            Text("Loading group...", color = colorScheme.onSurfaceVariant, fontSize = 13.sp)
            return@Column
        }

        Text(currentGroup.name, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeaderChip(label = currentGroup.currency)
            HeaderChip(
                label = "Code ${currentGroup.joinCode}",
                textColor = colorScheme.onSurface,
                onClick = {
                    coroutineScope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("join_code", currentGroup.joinCode))
                        )
                    }
                }
            )
            HeaderChip(
                label = "Show QR",
                onClick = { showQrDialog = true }
            )
            HeaderChip(
                label = "Share",
                onClick = {
                    val inviteText = buildString {
                        appendLine("Join my Cleave group: ${currentGroup.name}")
                        appendLine("Join code: ${currentGroup.joinCode}")
                        append("Link: https://cpmcleave.netlify.app/join?joinCode=${currentGroup.joinCode}")
                    }
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Join ${currentGroup.name} on Cleave")
                        putExtra(Intent.EXTRA_TEXT, inviteText)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share group invite"))
                }
            )
        }
        Spacer(modifier = Modifier.height(titleBottomSpacing))

        if (showQrDialog) {
            GroupQrDialog(
                groupName = currentGroup.name,
                joinCode = currentGroup.joinCode,
                onDismiss = { showQrDialog = false }
            )
        }

        if (showDeleteConfirmationDialog) {
            AlertDialog(
                onDismissRequest = {
                    if (!uiState.isDeleting) showDeleteConfirmationDialog = false
                },
                title = { Text("Delete group") },
                text = { Text("Are you sure you want to delete this group? This action cannot be undone.") },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteConfirmationDialog = false },
                        enabled = !uiState.isDeleting
                    ) {
                        Text("Cancel")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.onDeleteGroupClicked {
                                showDeleteConfirmationDialog = false
                                onGroupDeleted()
                            }
                        },
                        enabled = !uiState.isDeleting
                    ) {
                        Text(if (uiState.isDeleting) "Deleting..." else "Delete")
                    }
                }
            )
        }

        uiState.selectedMemberForExpulsionId?.let { memberId ->
            val memberName = uiState.userDisplayNames[memberId] ?: memberId
            AlertDialog(
                onDismissRequest = { viewModel.dismissMemberExpulsionDialog() },
                title = { Text("Remove member") },
                text = {
                    Column {
                        Text(
                            "Are you sure you want to remove $memberName from this group? " +
                                "You can only remove members who are not part of any expense."
                        )
                        uiState.errorMessage?.let { message ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissMemberExpulsionDialog() },
                        enabled = !uiState.isExpellingMember
                    ) {
                        Text("Cancel")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.confirmMemberExpulsion() },
                        enabled = !uiState.isExpellingMember
                    ) {
                        Text(if (uiState.isExpellingMember) "Removing..." else "Remove")
                    }
                }
            )
        }

        uiState.selectedExpenseForDeletionId?.let { expenseId ->
            val expenseName = uiState.expenses.firstOrNull { it.id == expenseId }
                ?.description
                ?.ifBlank { "this expense" }
                ?: "this expense"

            AlertDialog(
                onDismissRequest = { viewModel.dismissExpenseDeletionDialog() },
                title = { Text("Delete expense") },
                text = {
                    Column {
                        Text("Are you sure you want to delete $expenseName? This action cannot be undone.")
                        uiState.errorMessage?.let { message ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissExpenseDeletionDialog() },
                        enabled = !uiState.isDeletingExpense
                    ) {
                        Text("Cancel")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.confirmExpenseDeletion() },
                        enabled = !uiState.isDeletingExpense
                    ) {
                        Text(if (uiState.isDeletingExpense) "Deleting..." else "Delete")
                    }
                }
            )
        }

        uiState.selectedDebtForPayment?.let { selectedDebt ->
            val fromName = uiState.userDisplayNames[selectedDebt.fromUser] ?: selectedDebt.fromUser
            val toName = uiState.userDisplayNames[selectedDebt.toUser] ?: selectedDebt.toUser

            AlertDialog(
                onDismissRequest = { viewModel.dismissDebtPaymentDialog() },
                title = { Text("Pay debt") },
                text = {
                    Column {
                        Text("$fromName will pay $toName")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = uiState.debtPaymentAmountInput,
                            onValueChange = { viewModel.onDebtPaymentAmountChanged(it) },
                            label = { Text("Amount") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Maximum: ${"%.2f".format(Locale.getDefault(), selectedDebt.amount)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        uiState.errorMessage?.let { message ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 13.sp
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { viewModel.dismissDebtPaymentDialog() },
                        enabled = !uiState.isSettlingDebt
                    ) {
                        Text("Cancel")
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.confirmDebtPayment() },
                        enabled = !uiState.isSettlingDebt
                    ) {
                        Text(if (uiState.isSettlingDebt) "Paying..." else "Pay")
                    }
                }
            )
        }

        selectedReceiptUrl?.let { receiptUrl ->
            ReceiptImageDialog(
                receiptUrl = receiptUrl,
                onDismiss = { selectedReceiptUrl = null }
            )
        }

        if (uiState.selectedMemberForExpulsionId == null && uiState.selectedDebtForPayment == null) {
            uiState.errorMessage?.let { Text(it, color = colorScheme.error) }
        }

        Column(modifier = sectionModifier) {
            SectionTitle("Members")
            Text(
                text = "${currentGroup.members.size} members",
                color = colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (currentGroup.members.isEmpty()) {
                Text("No members in this group yet.", color = colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    currentGroup.members.forEach { memberId ->
                        val memberName = uiState.userDisplayNames[memberId] ?: memberId
                        val memberPhotoUrl = uiState.userPhotoUrls[memberId]
                        val canExpelMember = uiState.canDeleteGroup && memberId != currentGroup.ownerId
                        MemberAvatar(
                            name = memberName,
                            photoUrl = memberPhotoUrl,
                            onLongPress = if (canExpelMember) {
                                { viewModel.onMemberLongPressed(memberId) }
                            } else {
                                null
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(sectionSpacing))
        Column(modifier = sectionModifier) {
            SectionTitle("Expenses")
            Text(
                text = "${uiState.expenses.size} total",
                color = colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (uiState.expenses.isEmpty()) {
                Text("No expenses yet.", color = colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                uiState.expenses.forEach { expense ->
                    ExpenseDetailsItem(
                        expense = expense,
                        userDisplayNames = uiState.userDisplayNames,
                        onClick = { onAddExpenseClick(currentGroup.id, expense.id) },
                        onLongPress = if (uiState.canDeleteGroup) {
                            { viewModel.onExpenseLongPressed(expense.id) }
                        } else {
                            null
                        },
                        onViewReceipt = { receiptUrl ->
                            selectedReceiptUrl = receiptUrl
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(sectionSpacing))
        Column(modifier = sectionModifier) {
            SectionTitle("Debts")
            Text(
                text = "${uiState.debts.size} open",
                color = colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
            val currentUserId = uiState.currentUserId
            if (!currentUserId.isNullOrBlank()) {
                val totalYouOwe = uiState.debts
                    .filter { debt -> debt.fromUser == currentUserId }
                    .sumOf { debt -> debt.amount }
                val totalOwedToYou = uiState.debts
                    .filter { debt -> debt.toUser == currentUserId }
                    .sumOf { debt -> debt.amount }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "You owe (total): ${"%.2f".format(Locale.getDefault(), totalYouOwe)}",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                Text(
                    text = "Owed to you (total): ${"%.2f".format(Locale.getDefault(), totalOwedToYou)}",
                    color = colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (uiState.debtsWithReason.isEmpty()) {
                Text("No debts yet.", color = colorScheme.onSurfaceVariant, fontSize = 13.sp)
            } else {
                uiState.debtsWithReason.forEach { debtWithReason ->
                    val debt = debtWithReason.debt
                    val fromName = uiState.userDisplayNames[debt.fromUser] ?: debt.fromUser
                    val toName = uiState.userDisplayNames[debt.toUser] ?: debt.toUser
                    val canSettleDebt = uiState.currentUserId == debt.fromUser
                    val reasonText = debtWithReason.reasons
                        .joinToString(", ") { reason -> "${reason.expenseLabel}: ${reason.amount}" }
                        .ifBlank { "No expense details" }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (canSettleDebt) {
                                    Modifier.clickable { viewModel.onDebtClicked(debt) }
                                } else {
                                    Modifier
                                }
                            )
                            .padding(vertical = 6.dp)
                    ) {
                        Text(
                            text = "$fromName owes $toName: ${"%.2f".format(Locale.getDefault(), debt.amount)}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = reasonText,
                            color = colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(sectionSpacing))

        Button(
            onClick = { onAddExpenseClick(currentGroup.id, null) },
            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Add Expense")
        }

        if (uiState.canDeleteGroup) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { showDeleteConfirmationDialog = true },
                enabled = !uiState.isDeleting,
                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.error),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(if (uiState.isDeleting) "Deleting..." else "Delete Group")
            }
        }

        Spacer(modifier = Modifier.height(sectionSpacing))
    }
}

@Composable
private fun ReceiptImageDialog(
    receiptUrl: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("Expense receipt") },
        text = {
            AsyncImage(
                model = receiptUrl,
                contentDescription = "Receipt image",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
            )
        }
    )
}

@Composable
private fun GroupQrDialog(
    groupName: String,
    joinCode: String,
    onDismiss: () -> Unit
) {
    val qrPayload = "https://cpmcleave.netlify.app/join?joinCode=$joinCode"
    val qrBitmap = remember(qrPayload) { generateQrBitmap(qrPayload, size = 900) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = { Text("$groupName QR") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Group QR code",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    )
                } else {
                    Text("Could not generate QR code")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Join code: $joinCode",
                    fontWeight = FontWeight.Medium
                )
            }
        }
    )
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? {
    return runCatching {
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    }.getOrNull()
}

@Composable
private fun ExpenseDetailsItem(
    expense: Expense,
    userDisplayNames: Map<String, String>,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onViewReceipt: (String) -> Unit
) {
    val desc = expense.description.ifBlank { "(No description)" }
    val payerText = expense.payerContributions
        .joinToString(separator = ", ") { payer ->
            val name = userDisplayNames[payer.userId] ?: payer.userId
            "$name: ${payer.amount}"
        }
        .ifBlank { userDisplayNames[expense.paidByUserId] ?: expense.paidByUserId }

    val dateText = formatDate(expense.date)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .then(
                if (onClick != null || onLongPress != null) {
                    Modifier.pointerInput(onLongPress) {
                        detectTapGestures(
                            onTap = {
                                onClick?.invoke()
                            },
                            onLongPress = {
                                onLongPress?.invoke()
                            }
                        )
                    }
                } else {
                    Modifier
                }
            )
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = desc,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = payerText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = expense.amount.toString(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = dateText, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)

        if (expense.receiptItems.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Receipt items",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            expense.receiptItems.take(4).forEach { item ->
                val qtyPrefix = String.format(Locale.US, "%.3f", (item.quantity ?: 1.0)).trimEnd('0').trimEnd('.') + " x "
                Text(
                    text = "• $qtyPrefix${item.name} - ${String.format(Locale.US, "%.2f", item.amount)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expense.receiptItems.size > 4) {
                Text(
                    text = "+${expense.receiptItems.size - 4} more",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val receiptUrl = expense.imagePath?.takeIf { it.isNotBlank() }
        if (receiptUrl != null) {
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = { onViewReceipt(receiptUrl) }) {
                Text("View receipt")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
}

@Composable
private fun HeaderChip(
    label: String,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f), RoundedCornerShape(999.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(999.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MemberAvatar(
    name: String,
    photoUrl: String? = null,
    onLongPress: (() -> Unit)? = null
) {
    val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val avatarColors = listOf(
        Color(0xFF2563EB),
        Color(0xFF0D9488),
        Color(0xFF059669),
        Color(0xFFEA580C),
        Color(0xFFDC2626),
        Color(0xFF7C3AED)
    )
    val avatarColor = avatarColors[(name.hashCode().absoluteValue) % avatarColors.size]

    Box(
        modifier = Modifier
            .size(40.dp)
            .background(avatarColor, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), CircleShape)
            .then(
                if (onLongPress != null) {
                    Modifier.pointerInput(onLongPress) {
                        detectTapGestures(
                            onLongPress = { onLongPress() }
                        )
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        val model = photoUrl?.takeIf { it.isNotBlank() }
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = "Member photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
        } else {
            Text(text = initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    return runCatching {
        val formatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        formatter.format(Date(timestamp))
    }.getOrElse { "-" }
}
