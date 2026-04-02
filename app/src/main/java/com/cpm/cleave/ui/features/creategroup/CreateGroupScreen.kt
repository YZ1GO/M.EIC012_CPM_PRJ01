package com.cpm.cleave.ui.features.creategroup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// ExposedDropdownMenuBox is experimental yet
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupScreen(viewModel: CreateGroupViewModel, onNavigateBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var expanded by remember { mutableStateOf(false) }
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) {
            viewModel.onGroupImageSelected(null, null)
            return@rememberLauncherForActivityResult
        }
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        viewModel.onGroupImageSelected(uri.toString(), bytes)
    }

    val filteredCurrencyOptions = remember(uiState.currencyQuery, uiState.currencyOptions) {
        val query = uiState.currencyQuery.trim()
        if (query.isBlank()) {
            uiState.currencyOptions
        } else {
            uiState.currencyOptions.filter { option ->
                option.first.contains(query, ignoreCase = true) ||
                    option.second.contains(query, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "Create Group",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(48.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Group image (optional)", fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { pickImageLauncher.launch("image/*") },
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(if (uiState.selectedImageUri == null) "Choose image" else "Change image")
            }

            val imageModel = uiState.selectedImageUri ?: uiState.uploadedImageUrl
            if (!imageModel.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                AsyncImage(
                    model = imageModel,
                    contentDescription = "Group image preview",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Name Input
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Group name", fontSize = 14.sp)
            OutlinedTextField(
                value = uiState.Name,
                onValueChange = { viewModel.onNameChanged(it) },
                placeholder = { Text("Value") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Currency Dropdown
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Currency", fontSize = 14.sp)
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = uiState.currencyQuery,
                    onValueChange = {
                        viewModel.onCurrencyInputChanged(it)
                        expanded = true
                    },
                    readOnly = false,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryEditable),
                    placeholder = { Text("Select currency") },
                    shape = RoundedCornerShape(8.dp)
                )

                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    filteredCurrencyOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.second) },
                            onClick = {
                                viewModel.onCurrencyChanged(option.first)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(48.dp))

        uiState.errorMessage?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = {
                viewModel.createGroup(onSuccess = onNavigateBack)
            },
            enabled = !uiState.isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.height(48.dp)
        ) {
            Text(
                text = if (uiState.isLoading) "Creating..." else "Create Group",
                fontSize = 16.sp
            )
        }
    }
}
