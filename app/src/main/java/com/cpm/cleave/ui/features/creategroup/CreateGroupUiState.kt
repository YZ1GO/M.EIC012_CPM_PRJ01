package com.cpm.cleave.ui.features.creategroup

data class CreateGroupUiState(
    val Name: String = "",
    val Currency: String = "Euro",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)