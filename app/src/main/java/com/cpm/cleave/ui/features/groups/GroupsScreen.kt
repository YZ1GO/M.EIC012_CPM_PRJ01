package com.cpm.cleave.ui.features.groups

import android.content.ClipData
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cpm.cleave.model.Group
import com.cpm.cleave.model.Expense
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

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
            Text(text = message, color = Color.Red)
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = { groupsViewModel.loadGroups() }) {
                Text("Retry")
            }
            return@Column
        }

        if (displayedGroups.isEmpty()) {
            Text(
                text = if (uiState.searchQuery.isBlank()) "No groups yet." else "No matching groups.",
                color = Color.Gray
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
        // TODO CHANGE BOX TO IMAGE
        Box(
            modifier = Modifier.size(64.dp).border(1.dp, Color.Gray, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("?", fontSize = 32.sp, color = Color.Gray)
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
                color = Color.Gray,
                fontSize = 14.sp
            )
            Text(
                text = "Code: ${group.joinCode}",
                color = Color.Gray,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun GroupDetailsScreen(
    viewModel: GroupDetailsViewModel,
    onAddExpenseClick: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val titleTopSpacing = 20.dp
    val titleBottomSpacing = 18.dp
    val sectionSpacing = 16.dp
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

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
        .background(Color(0x14FFFFFF), RoundedCornerShape(16.dp))
        .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(16.dp))
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
            Text("Loading group...", color = Color.Gray, fontSize = 13.sp)
            return@Column
        }

        Text(currentGroup.name, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeaderChip(label = currentGroup.currency)
            HeaderChip(
                label = "Code ${currentGroup.joinCode}",
                onClick = {
                    coroutineScope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("join_code", currentGroup.joinCode))
                        )
                    }
                }
            )
        }
        Spacer(modifier = Modifier.height(titleBottomSpacing))

        uiState.errorMessage?.let { Text(it, color = Color.Red) }

        Column(modifier = sectionModifier) {
            SectionTitle("Members")
            Text(
                text = "${currentGroup.members.size} members",
                color = Color.Gray,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (currentGroup.members.isEmpty()) {
                Text("No members in this group yet.", color = Color.White, fontSize = 13.sp)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    currentGroup.members.forEach { memberId ->
                        val memberName = uiState.userDisplayNames[memberId] ?: memberId
                        MemberAvatar(name = memberName)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(sectionSpacing))
        Column(modifier = sectionModifier) {
            SectionTitle("Expenses")
            Text(
                text = "${uiState.expenses.size} total",
                color = Color.White,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (uiState.expenses.isEmpty()) {
                Text("No expenses yet.", color = Color.White, fontSize = 13.sp)
            } else {
                uiState.expenses.forEach { expense ->
                    ExpenseDetailsItem(
                        expense = expense,
                        userDisplayNames = uiState.userDisplayNames
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(sectionSpacing))
        Column(modifier = sectionModifier) {
            SectionTitle("Debts")
            Text(
                text = "${uiState.debts.size} open",
                color = Color.Gray,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            if (uiState.debtsWithReason.isEmpty()) {
                Text("No debts yet.", color = Color.Black, fontSize = 13.sp)
            } else {
                uiState.debtsWithReason.forEach { debtWithReason ->
                    val debt = debtWithReason.debt
                    val fromName = uiState.userDisplayNames[debt.fromUser] ?: debt.fromUser
                    val toName = uiState.userDisplayNames[debt.toUser] ?: debt.toUser
                    val reasonText = debtWithReason.reasons
                        .joinToString(", ") { reason -> "${reason.expenseLabel}: ${reason.amount}" }
                        .ifBlank { "No expense details" }
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = "$fromName owes $toName: ${"%.2f".format(Locale.getDefault(), debt.amount)}",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Black
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = reasonText,
                            color = Color.Black,
                            fontSize = 12.sp,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(sectionSpacing))

        Button(
            onClick = { onAddExpenseClick(currentGroup.id) },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Add Expense", color = Color.White)
        }

        Spacer(modifier = Modifier.height(sectionSpacing))
    }
}

@Composable
private fun ExpenseDetailsItem(expense: Expense, userDisplayNames: Map<String, String>) {
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
            .background(Color(0x1AFFFFFF), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(14.dp))
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
                    color = Color.Black
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = payerText,
                    fontSize = 12.sp,
                    color = Color.Black
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = expense.amount.toString(),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = dateText, color = Color.Black, fontSize = 11.sp)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
}

@Composable
private fun HeaderChip(label: String, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .background(Color(0x14FFFFFF), RoundedCornerShape(999.dp))
            .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(999.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun MemberAvatar(name: String) {
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
            .background(avatarColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(text = initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    return runCatching {
        val formatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        formatter.format(Date(timestamp))
    }.getOrElse { "-" }
}
