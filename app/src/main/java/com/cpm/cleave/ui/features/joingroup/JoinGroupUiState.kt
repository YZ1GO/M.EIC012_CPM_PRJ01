package com.cpm.cleave.ui.features.joingroup

data class JoinGroupUiState(
    val joinCode: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isScannerVisible: Boolean = false
)
