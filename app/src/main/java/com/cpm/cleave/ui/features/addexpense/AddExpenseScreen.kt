package com.cpm.cleave.ui.features.addexpense

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState

@Composable
fun AddExpenseScreen(viewModel: AddExpenseViewModel, onNavigateBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

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
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Add Expense",
            color = Color.Blue,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFDCE2EA), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text("Amount", fontSize = 14.sp)
            OutlinedTextField(
                value = uiState.amountInput,
                onValueChange = { viewModel.onAmountChanged(it) },
                placeholder = { Text("0.00") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFDCE2EA), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text("Description", fontSize = 14.sp)
            OutlinedTextField(
                value = uiState.description,
                onValueChange = { viewModel.onDescriptionChanged(it) },
                placeholder = { Text("Dinner, groceries, fuel...") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFDCE2EA), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text("Who paid and how much?", fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.onBuyerModeChanged(BuyerMode.SINGLE_BUYER) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.buyerMode == BuyerMode.SINGLE_BUYER) Color.Blue else Color.LightGray
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("You paid", color = Color.White)
                }

                Button(
                    onClick = { viewModel.onBuyerModeChanged(BuyerMode.SELECT_BUYERS) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.buyerMode == BuyerMode.SELECT_BUYERS) Color.Blue else Color.LightGray
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Multiple payers", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.buyerMode == BuyerMode.SINGLE_BUYER) {
                Text(
                    text = "Buyer: ${uiState.primaryBuyerId.ifBlank { "(unknown)" }} pays full amount",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            } else {
                uiState.availablePayers.forEach { payer ->
                    val selected = uiState.selectedPayerIds.contains(payer)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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
                            text = payer,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = uiState.payerAmountInputs[payer].orEmpty(),
                            onValueChange = { value ->
                                if (selected) {
                                    viewModel.onPayerAmountChanged(payer, value)
                                }
                            },
                            enabled = selected,
                            placeholder = { Text("0.00") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Contributions: $contributionTotal / ${uiState.amountInput.ifBlank { "0.0" }}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFDCE2EA), RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text("How to split?", fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.onSplitModeChanged(SplitMode.ALL_MEMBERS) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.splitMode == SplitMode.ALL_MEMBERS) Color.Blue else Color.LightGray
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("All members", color = Color.White)
                }

                Button(
                    onClick = { viewModel.onSplitModeChanged(SplitMode.SELECTED_MEMBERS) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.splitMode == SplitMode.SELECTED_MEMBERS) Color.Blue else Color.LightGray
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Selected members", color = Color.White)
                }
            }

            if (uiState.splitMode == SplitMode.SELECTED_MEMBERS) {
                Spacer(modifier = Modifier.height(8.dp))
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
                            text = if (isPayer) "$memberId (payer, required)" else memberId,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        uiState.errorMessage?.let {
            Text(text = it, color = Color.Red, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = { viewModel.createExpense(onSuccess = onNavigateBack) },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Blue),
            shape = RoundedCornerShape(8.dp),
            enabled = !uiState.isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(
                if (uiState.isLoading) "Creating..." else "Create Expense",
                color = Color.White
            )
        }
    }
}
