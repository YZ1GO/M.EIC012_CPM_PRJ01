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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.cpm.cleave.data.repository.contracts.IExpenseRepository
import com.cpm.cleave.data.repository.contracts.IGroupRepository
import com.cpm.cleave.model.Debt
import com.cpm.cleave.model.Expense
import com.cpm.cleave.model.Group
import kotlinx.coroutines.launch

@Composable
fun GroupsScreen(groupsViewModel: GroupsViewModel, onGroupClick: (String) -> Unit) {
    LaunchedEffect(Unit) { groupsViewModel.loadGroups() }

    val uiState by groupsViewModel.uiState.collectAsState()

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

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            items(uiState.groups) { group ->
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
    groupRepository: IGroupRepository,
    expenseRepository: IExpenseRepository,
    groupId: String,
    onAddExpenseClick: (String) -> Unit
) {
    var group by remember { mutableStateOf<Group?>(null) }
    var expenses by remember { mutableStateOf<List<Expense>>(emptyList()) }
    var debts by remember { mutableStateOf<List<Debt>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    fun refreshGroupData() {
        coroutineScope.launch {
            groupRepository.getGroupById(groupId)
                .onSuccess { group = it }
                .onFailure { loadError = it.message ?: "Could not load group" }

            expenseRepository.getExpensesByGroup(groupId)
                .onSuccess {
                    expenses = it.sortedByDescending { expense -> expense.date }
                }
                .onFailure {
                    loadError = it.message ?: "Could not load expenses" }

            expenseRepository.getDebtsByGroup(groupId)
                .onSuccess { debts = it }
                .onFailure {
                    loadError = it.message ?: "Could not calculate debts"
                }
        }
    }

    LaunchedEffect(groupId) {
        refreshGroupData()
    }

    DisposableEffect(lifecycleOwner, groupId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshGroupData()
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

        loadError?.let { Text(it, color = Color.Red) }

        val currentGroup = group
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
        Text("Expenses (${expenses.size})", fontWeight = FontWeight.Medium)

        if (expenses.isEmpty()) {
            Text("No expenses yet.")
        } else {
            expenses.forEach { expense ->
                val desc = expense.description.ifBlank { "(No description)" }
                Text(
                    text = "- $desc: ${expense.amount} (${expense.paidByUserId})",
                    color = Color.Gray
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("Debts (${debts.size})", fontWeight = FontWeight.Medium)

        if (debts.isEmpty()) {
            Text("No debts yet.")
        } else {
            debts.forEach { debt ->
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
