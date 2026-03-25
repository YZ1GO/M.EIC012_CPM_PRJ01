package com.cpm.cleave.ui.features.groups

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
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
    val titleTopSpacing = 24.dp
    val titleBottomSpacing = 32.dp
    val sectionSpacing = 16.dp

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
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(titleTopSpacing))
        Text("Group Details", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(titleBottomSpacing))

        uiState.errorMessage?.let { Text(it, color = Color.Red) }

        val currentGroup = uiState.group
        if (currentGroup == null) {
            Text("Loading group...", color = Color.Gray, fontSize = 13.sp)
            return@Column
        }

        Column(modifier = sectionModifier) {
            SectionTitle("Group")
            Spacer(modifier = Modifier.height(6.dp))
            TopicRow("Name", currentGroup.name)
            TopicRow("Currency", currentGroup.currency)
            TopicRow("Code", currentGroup.joinCode)
        }

        Spacer(modifier = Modifier.height(sectionSpacing))
        Column(modifier = sectionModifier) {
            SectionTitle("Members (${currentGroup.members.size})")
            Spacer(modifier = Modifier.height(6.dp))

            if (currentGroup.members.isEmpty()) {
                Text("No members in this group yet.", color = Color.Gray, fontSize = 13.sp)
            } else {
                currentGroup.members.forEach { memberId ->
                    val memberName = uiState.userDisplayNames[memberId] ?: memberId
                    TopicRow("Member", memberName)
                }
            }
        }

        Spacer(modifier = Modifier.height(sectionSpacing))
        Column(modifier = sectionModifier) {
            SectionTitle("Expenses (${uiState.expenses.size})")
            Spacer(modifier = Modifier.height(6.dp))

            if (uiState.expenses.isEmpty()) {
                Text("No expenses yet.", color = Color.Gray, fontSize = 13.sp)
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
            SectionTitle("Debts (${uiState.debts.size})")
            Spacer(modifier = Modifier.height(6.dp))

            if (uiState.debtsWithReason.isEmpty()) {
                Text("No debts yet.", color = Color.Gray, fontSize = 13.sp)
            } else {
                uiState.debtsWithReason.forEach { debtWithReason ->
                    val debt = debtWithReason.debt
                    val fromName = uiState.userDisplayNames[debt.fromUser] ?: debt.fromUser
                    val toName = uiState.userDisplayNames[debt.toUser] ?: debt.toUser
                    val reasonText = debtWithReason.reasons
                        .joinToString(", ") { reason -> "${reason.expenseLabel}: ${reason.amount}" }
                        .ifBlank { "No expense details" }
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        TopicRow("From", fromName)
                        TopicRow("To", toName)
                        TopicRow("Amount", debt.amount.toString())
                        TopicRow("Reason", reasonText)
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

    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        TopicRow("Description", desc)
        TopicRow("Total", expense.amount.toString())
        TopicRow("Payers", payerText)
        TopicRow("Date", dateText)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, fontWeight = FontWeight.Medium, fontSize = 16.sp)
}

@Composable
private fun TopicRow(topic: String, value: String) {
    Text(text = "$topic: $value", color = Color.Gray, fontSize = 13.sp)
}

private fun formatDate(timestamp: Long): String {
    if (timestamp <= 0L) return "-"
    return runCatching {
        val formatter = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        formatter.format(Date(timestamp))
    }.getOrElse { "-" }
}
