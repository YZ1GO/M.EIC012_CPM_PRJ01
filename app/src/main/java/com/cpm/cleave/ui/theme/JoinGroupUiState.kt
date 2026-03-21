package com.cpm.cleave.ui.theme

data class JoinGroupUiState(
    val joinCode: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null
)
