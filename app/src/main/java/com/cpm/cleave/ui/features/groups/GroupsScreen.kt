package com.cpm.cleave.ui.features.groups

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Group Details", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)

        uiState.errorMessage?.let { Text(it, color = Color.Red) }

        val currentGroup = uiState.group
        if (currentGroup == null) {
            Text("Loading group...")
            return@Column
        }

        Text("Name: ${currentGroup.name}")
        Text("Currency: ${currentGroup.currency}")
        Text("Code: ${currentGroup.joinCode}")
        Spacer(modifier = Modifier.height(8.dp))
        Text("Members (${currentGroup.members.size})", fontWeight = FontWeight.Medium)

        if (currentGroup.members.isEmpty()) {
            Text("No members in this group yet.")
        } else {
            currentGroup.members.forEach { memberId ->
                Text("- $memberId", color = Color.Gray)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Expenses (${uiState.expenses.size})", fontWeight = FontWeight.Medium)

        if (uiState.expenses.isEmpty()) {
            Text("No expenses yet.")
        } else {
            uiState.expenses.forEach { expense ->
                val desc = expense.description.ifBlank { "(No description)" }
                Text(
                    text = "- $desc: ${expense.amount} (${expense.paidByUserId})",
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Debts (${uiState.debts.size})", fontWeight = FontWeight.Medium)

        if (uiState.debts.isEmpty()) {
            Text("No debts yet.")
        } else {
            uiState.debts.forEach { debt ->
                Text(
                    text = "- ${debt.fromUser} owes ${debt.toUser}: ${debt.amount}",
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { onAddExpenseClick(currentGroup.id) },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Add Expense", color = Color.White)
        }
    }
}
