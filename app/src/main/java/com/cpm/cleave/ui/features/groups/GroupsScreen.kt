package com.cpm.cleave.ui.features.groups

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.cpm.cleave.R
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.Group
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupsScreen(
    groupsViewModel: GroupsViewModel,
    refreshNonce: Int = 0,
    onGroupClick: (String) -> Unit
) {
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
    var isRefreshing by remember { mutableStateOf(false) }
    var refreshStartedAtMs by remember { mutableStateOf(0L) }
    val minRefreshVisibleMs = 900L

    LaunchedEffect(uiState.loadCompletionToken) {
        if (uiState.loadCompletionToken > 0L && isRefreshing) {
            val elapsed = System.currentTimeMillis() - refreshStartedAtMs
            val remaining = (minRefreshVisibleMs - elapsed).coerceAtLeast(0L)
            if (remaining > 0L) delay(remaining)
            isRefreshing = false
        }
    }

    LaunchedEffect(refreshNonce) {
        if (refreshNonce > 0) {
            refreshStartedAtMs = System.currentTimeMillis()
            isRefreshing = true
            groupsViewModel.loadGroups()
        }
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            refreshStartedAtMs = System.currentTimeMillis()
            isRefreshing = true
            groupsViewModel.loadGroups()
        },
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "My Groups",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { groupsViewModel.onSearchQueryChanged(it) },
                    placeholder = { Text("Search groups...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = { focusManager.clearFocus() }
                    )
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (uiState.isLoading && !isRefreshing && uiState.groups.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Syncing groups...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    }
                }
                return@PullToRefreshBox
            }

            uiState.errorMessage?.let { message ->
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { groupsViewModel.loadGroups() },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Retry")
                    }
                }
                return@PullToRefreshBox
            }

            if (displayedGroups.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = "No Groups",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (uiState.searchQuery.isBlank()) "You haven't joined any groups yet." else "No matching groups found.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }
                return@PullToRefreshBox
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(displayedGroups) { group ->
                    GroupListItem(group = group, onClick = { onGroupClick(group.id) })
                }
            }
        }
    }
}

@Composable
fun GroupListItem(group: Group, onClick: () -> Unit) {
    val currencyDisplay = formatCurrencySymbolAndCode(group.currency)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val groupImageUrl = group.imageUrl?.takeIf { it.isNotBlank() }
        
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = groupImageUrl ?: R.drawable.default_group_image,
                contentDescription = "Group image",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Code: ${group.joinCode}",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp
                    )
                }

                Text(
                    text = "•",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Text(
                    text = currencyDisplay,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "View Group",
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

private fun formatCurrencySymbolAndCode(code: String): String {
    val normalized = code.trim().uppercase(Locale.ROOT)
    if (normalized.isBlank()) return code
    val symbol = currencySymbolsByCode[normalized]
    return if (symbol.isNullOrBlank()) normalized else "$symbol $normalized"
}

private fun getCurrencySymbolOnly(code: String): String {
    val normalized = code.trim().uppercase(Locale.ROOT)
    return currencySymbolsByCode[normalized] ?: normalized
}

private val currencySymbolsByCode = mapOf(
    "AUD" to "$", "BRL" to "R$", "CAD" to "$", "CHF" to "₣", "CNY" to "¥",
    "EUR" to "€", "GBP" to "£", "HKD" to "$", "INR" to "₹", "JPY" to "¥",
    "KRW" to "₩", "MXN" to "$", "NOK" to "kr", "NZD" to "$", "PLN" to "zł",
    "SEK" to "kr", "SGD" to "$", "TWD" to "NT$", "USD" to "$", "ZAR" to "R"
)

@Composable
fun GroupDetailsScreen(
    viewModel: GroupDetailsViewModel,
    onAddExpenseClick: (String, String?) -> Unit,
    onGroupDeleted: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        val currentGroup = uiState.group
        if (currentGroup == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colorScheme.primary)
            }
            return@Column
        }

        val currencySymbol = getCurrencySymbolOnly(currentGroup.currency)

        // --- Header Section ---
        Text(
            text = currentGroup.name, 
            fontSize = 32.sp, 
            fontWeight = FontWeight.ExtraBold,
            color = colorScheme.primary,
            lineHeight = 36.sp
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HeaderChip(
                label = formatCurrencySymbolAndCode(currentGroup.currency),
                backgroundColor = colorScheme.primaryContainer,
                textColor = colorScheme.onPrimaryContainer
            )
            HeaderChip(
                label = "Code: ${currentGroup.joinCode}",
                icon = Icons.Default.ContentCopy,
                onClick = {
                    coroutineScope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("join_code", currentGroup.joinCode)))
                    }
                }
            )
            HeaderChip(
                label = "QR Code",
                icon = Icons.Default.QrCode,
                onClick = { showQrDialog = true }
            )
            HeaderChip(
                label = "Share",
                icon = Icons.Default.Share,
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

        Spacer(modifier = Modifier.height(32.dp))

        // --- Dialogs ---
        if (showQrDialog) {
            GroupQrDialog(groupName = currentGroup.name, joinCode = currentGroup.joinCode, onDismiss = { showQrDialog = false })
        }
        if (showDeleteConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { if (!uiState.isDeleting) showDeleteConfirmationDialog = false },
                title = { Text("Delete group") },
                text = { Text("Are you sure you want to delete this group? This action cannot be undone.") },
                dismissButton = { TextButton(onClick = { showDeleteConfirmationDialog = false }, enabled = !uiState.isDeleting) { Text("Cancel") } },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.onDeleteGroupClicked { showDeleteConfirmationDialog = false; onGroupDeleted() } },
                        enabled = !uiState.isDeleting
                    ) { Text(if (uiState.isDeleting) "Deleting..." else "Delete", color = colorScheme.error) }
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
                        Text("Are you sure you want to remove $memberName from this group? You can only remove members who are not part of any expense.")
                        uiState.errorMessage?.let { message ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = message, color = colorScheme.error, fontSize = 13.sp)
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { viewModel.dismissMemberExpulsionDialog() }, enabled = !uiState.isExpellingMember) { Text("Cancel") } },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmMemberExpulsion() }, enabled = !uiState.isExpellingMember) { Text(if (uiState.isExpellingMember) "Removing..." else "Remove", color = colorScheme.error) }
                }
            )
        }
        uiState.selectedMemberForProfileId?.let { memberId ->
            val memberName = uiState.userDisplayNames[memberId] ?: memberId
            val photoUrl = resolveMemberPhotoUrl(uiState.userPhotoUrls, memberId)
            val lastSeen = uiState.userLastSeen[memberId]
            
            MemberProfileDialog(
                memberName = memberName,
                photoUrl = photoUrl,
                lastSeenMs = lastSeen,
                onDismiss = { viewModel.dismissMemberProfileDialog() }
            )
        }
        uiState.selectedExpenseForDeletionId?.let { expenseId ->
            val expenseName = uiState.expenses.firstOrNull { it.id == expenseId }?.description?.ifBlank { "this expense" } ?: "this expense"
            AlertDialog(
                onDismissRequest = { viewModel.dismissExpenseDeletionDialog() },
                title = { Text("Delete expense") },
                text = {
                    Column {
                        Text("Are you sure you want to delete $expenseName? This action cannot be undone.")
                        uiState.errorMessage?.let { message ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = message, color = colorScheme.error, fontSize = 13.sp)
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { viewModel.dismissExpenseDeletionDialog() }, enabled = !uiState.isDeletingExpense) { Text("Cancel") } },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmExpenseDeletion() }, enabled = !uiState.isDeletingExpense) { Text(if (uiState.isDeletingExpense) "Deleting..." else "Delete", color = colorScheme.error) }
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
                        Text(text = "Maximum: ${currencySymbol}${"%.2f".format(Locale.getDefault(), selectedDebt.amount)}", color = colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        uiState.errorMessage?.let { message ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = message, color = colorScheme.error, fontSize = 13.sp)
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { viewModel.dismissDebtPaymentDialog() }, enabled = !uiState.isSettlingDebt) { Text("Cancel") } },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmDebtPayment() }, enabled = !uiState.isSettlingDebt) { Text(if (uiState.isSettlingDebt) "Paying..." else "Pay") }
                }
            )
        }
        selectedReceiptUrl?.let { receiptUrl ->
            ReceiptImageDialog(receiptUrl = receiptUrl, onDismiss = { selectedReceiptUrl = null })
        }

        if (uiState.selectedMemberForExpulsionId == null && uiState.selectedDebtForPayment == null && uiState.selectedExpenseForDeletionId == null) {
            uiState.errorMessage?.let { Text(it, color = colorScheme.error) }
        }

        // --- Members Section ---
        SectionTitle("Members (${currentGroup.members.size})")
        Spacer(modifier = Modifier.height(12.dp))
        if (currentGroup.members.isEmpty()) {
            Text("No members in this group yet.", color = colorScheme.onSurfaceVariant, fontSize = 14.sp)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                currentGroup.members.forEach { memberId ->
                    val memberName = uiState.userDisplayNames[memberId] ?: memberId
                    val memberPhotoUrl = resolveMemberPhotoUrl(uiState.userPhotoUrls, memberId)
                    val canExpelMember = uiState.canDeleteGroup && memberId != currentGroup.ownerId
                    MemberAvatar(
                        name = memberName,
                        photoUrl = memberPhotoUrl,
                        onTap = { viewModel.onMemberClicked(memberId) },
                        onLongPress = if (canExpelMember) { { viewModel.onMemberLongPressed(memberId) } } else null
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- Debts Section ---
        SectionTitle("Debts")
        Spacer(modifier = Modifier.height(12.dp))
        
        val currentUserId = uiState.currentUserId
        if (!currentUserId.isNullOrBlank()) {
            val totalYouOwe = uiState.debts.filter { it.fromUser == currentUserId }.sumOf { it.amount }
            val totalOwedToYou = uiState.debts.filter { it.toUser == currentUserId }.sumOf { it.amount }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = colorScheme.errorContainer.copy(alpha = if (totalYouOwe > 0) 1f else 0.4f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("You owe", fontSize = 12.sp, color = colorScheme.onErrorContainer.copy(alpha = 0.8f))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${currencySymbol}${"%.2f".format(Locale.getDefault(), totalYouOwe)}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colorScheme.onErrorContainer)
                    }
                }
                Surface(
                    color = colorScheme.primaryContainer.copy(alpha = if (totalOwedToYou > 0) 1f else 0.4f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Owed to you", fontSize = 12.sp, color = colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("${currencySymbol}${"%.2f".format(Locale.getDefault(), totalOwedToYou)}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = colorScheme.onPrimaryContainer)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (uiState.debtsWithReason.isEmpty()) {
            Text("No open debts. Everything is settled!", color = colorScheme.onSurfaceVariant, fontSize = 14.sp)
        } else {
            uiState.debtsWithReason.forEach { debtWithReason ->
                val debt = debtWithReason.debt
                val fromName = uiState.userDisplayNames[debt.fromUser] ?: debt.fromUser
                val toName = uiState.userDisplayNames[debt.toUser] ?: debt.toUser
                val canSettleDebt = uiState.currentUserId == debt.fromUser
                val reasonText = debtWithReason.reasons.joinToString(", ") { "${it.expenseLabel}" }.ifBlank { "No expense details" }
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .then(if (canSettleDebt) Modifier.clickable { viewModel.onDebtClicked(debt) } else Modifier)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fromName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = colorScheme.onSurface)
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "owes", modifier = Modifier.padding(horizontal = 6.dp).size(16.dp), tint = colorScheme.onSurfaceVariant)
                            Text(toName, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = colorScheme.onSurface)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(reasonText, color = colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        text = "${currencySymbol}${"%.2f".format(Locale.getDefault(), debt.amount)}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // --- Expenses Section ---
        SectionTitle("Expenses (${uiState.expenses.size})")
        Spacer(modifier = Modifier.height(12.dp))
        
        if (uiState.expenses.isEmpty()) {
            Text("No expenses recorded yet.", color = colorScheme.onSurfaceVariant, fontSize = 14.sp)
        } else {
            uiState.expenses.forEach { expense ->
                ExpenseDetailsItem(
                    expense = expense,
                    userDisplayNames = uiState.userDisplayNames,
                    currencySymbol = currencySymbol,
                    onClick = { onAddExpenseClick(currentGroup.id, expense.id) },
                    onLongPress = if (uiState.canDeleteGroup) { { viewModel.onExpenseLongPressed(expense.id) } } else null,
                    onViewReceipt = { receiptUrl -> selectedReceiptUrl = receiptUrl }
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // --- Action Buttons ---
        Button(
            onClick = { onAddExpenseClick(currentGroup.id, null) },
            colors = ButtonDefaults.buttonColors(containerColor = colorScheme.primary),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text("Add Expense", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        if (uiState.canDeleteGroup) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { showDeleteConfirmationDialog = true },
                enabled = !uiState.isDeleting,
                colors = ButtonDefaults.buttonColors(containerColor = colorScheme.errorContainer, contentColor = colorScheme.onErrorContainer),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(if (uiState.isDeleting) "Deleting..." else "Delete Group", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
private fun ReceiptImageDialog(receiptUrl: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Expense receipt") },
        text = {
            AsyncImage(
                model = receiptUrl,
                contentDescription = "Receipt image",
                modifier = Modifier.fillMaxWidth().height(320.dp)
            )
        }
    )
}

@Composable
private fun GroupQrDialog(groupName: String, joinCode: String, onDismiss: () -> Unit) {
    val qrPayload = "https://cpmcleave.netlify.app/join?joinCode=$joinCode"
    val qrBitmap = remember(qrPayload) { generateQrBitmap(qrPayload, size = 900) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("$groupName QR") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (qrBitmap != null) {
                    Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "Group QR code", modifier = Modifier.fillMaxWidth().height(260.dp))
                } else {
                    Text("Could not generate QR code")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text("Join code: $joinCode", fontWeight = FontWeight.Medium)
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
    currencySymbol: String,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onViewReceipt: (String) -> Unit
) {
    val desc = expense.description.ifBlank { "(No description)" }
    val payerText = expense.payerContributions
        .joinToString(separator = ", ") { payer ->
            val name = userDisplayNames[payer.userId] ?: payer.userId
            "$name: ${currencySymbol}${"%.2f".format(Locale.getDefault(), payer.amount)}"
        }
        .ifBlank { userDisplayNames[expense.paidByUserId] ?: expense.paidByUserId }

    val dateText = formatDate(expense.date)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .then(
                if (onClick != null || onLongPress != null) {
                    Modifier.pointerInput(onLongPress) {
                        detectTapGestures(
                            onTap = { onClick?.invoke() },
                            onLongPress = { onLongPress?.invoke() }
                        )
                    }
                } else {
                    Modifier
                }
            )
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = desc,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = payerText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "${currencySymbol}${"%.2f".format(Locale.getDefault(), expense.amount)}",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = dateText, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 12.sp)

        if (expense.receiptItems.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "Receipt Items",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    expense.receiptItems.take(4).forEach { item ->
                        val qtyPrefix = String.format(Locale.US, "%.3f", (item.quantity ?: 1.0)).trimEnd('0').trimEnd('.') + " x "
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = "$qtyPrefix${item.name}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${currencySymbol}${"%.2f".format(Locale.getDefault(), item.amount)}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                    }
                    if (expense.receiptItems.size > 4) {
                        Text(
                            text = "+${expense.receiptItems.size - 4} more",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        val receiptUrl = expense.imagePath?.takeIf { it.isNotBlank() }
        if (receiptUrl != null) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = { onViewReceipt(receiptUrl) },
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.height(24.dp)
            ) {
                Text("View original receipt", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text, 
        fontWeight = FontWeight.Bold, 
        fontSize = 20.sp, 
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun HeaderChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(backgroundColor)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon, 
                contentDescription = null, 
                tint = textColor, 
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = label, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun resolveMemberPhotoUrl(
    userPhotoUrls: Map<String, String>,
    memberId: String
): String? {
    val normalizedId = memberId.trim().substringAfterLast('/')
    return userPhotoUrls[memberId]
        ?: userPhotoUrls[normalizedId]
        ?: userPhotoUrls.entries.firstOrNull { entry ->
            entry.key.trim().substringAfterLast('/') == normalizedId
        }?.value
}

@Composable
private fun MemberAvatar(
    name: String,
    photoUrl: String? = null,
    onTap: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                .then(
                    if (onTap != null || onLongPress != null) {
                        Modifier.pointerInput(onTap, onLongPress) {
                            detectTapGestures(
                                onTap = { onTap?.invoke() },
                                onLongPress = { onLongPress?.invoke() }
                            )
                        }
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = photoUrl?.takeIf { it.isNotBlank() } ?: R.drawable.default_user_image,
                contentDescription = "Member photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(CircleShape)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = name.split(" ").first(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MemberProfileDialog(
    memberName: String,
    photoUrl: String?,
    lastSeenMs: Long?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = photoUrl?.takeIf { it.isNotBlank() } ?: R.drawable.default_user_image,
                        contentDescription = "Member photo",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(80.dp).clip(CircleShape)
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = memberName,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Last active",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatLastSeen(lastSeenMs),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    )
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    return runCatching {
        val formatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        formatter.format(Date(timestamp))
    }.getOrElse { "-" }
}

private fun formatLastSeen(timestamp: Long?): String {
    if (timestamp == null || timestamp <= 0L) return "Unavailable"
    return runCatching {
        val formatter = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        formatter.format(Date(timestamp))
    }.getOrElse { "Unavailable" }
}