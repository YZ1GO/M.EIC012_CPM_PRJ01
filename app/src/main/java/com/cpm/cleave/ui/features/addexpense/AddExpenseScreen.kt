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
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
    val titleTopSpacing = 24.dp
    val titleBottomSpacing = 32.dp
    val sectionSpacing = 16.dp
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
            color = Color.Unspecified,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(titleBottomSpacing))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x14FFFFFF), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(16.dp))
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
                    focusedContainerColor = Color(0x1AFFFFFF),
                    unfocusedContainerColor = Color(0x1AFFFFFF),
                    disabledContainerColor = Color(0x10FFFFFF),
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
                .background(Color(0x14FFFFFF), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(16.dp))
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
                    focusedContainerColor = Color(0x1AFFFFFF),
                    unfocusedContainerColor = Color(0x1AFFFFFF),
                    disabledContainerColor = Color(0x10FFFFFF),
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
                .background(Color(0x14FFFFFF), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Text("Who paid and how much?", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))

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

            Spacer(modifier = Modifier.height(6.dp))

            if (uiState.buyerMode == BuyerMode.SINGLE_BUYER) {
                Text(
                    text = "Buyer: ${uiState.primaryBuyerId.takeIf { it.isNotBlank() }?.let(labelForMember) ?: "(unknown)"} pays full amount",
                    color = Color.Gray,
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
                                focusedContainerColor = Color(0x1AFFFFFF),
                                unfocusedContainerColor = Color(0x1AFFFFFF),
                                disabledContainerColor = Color(0x10FFFFFF),
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
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(sectionSpacing))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0x14FFFFFF), RoundedCornerShape(16.dp))
                .border(1.dp, Color(0x26FFFFFF), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Text("How to split?", fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))

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
