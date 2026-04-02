package com.cpm.cleave.ui.features.creategroup

data class CreateGroupUiState(
    val Name: String = "",
    val selectedCurrencyCode: String = "EUR",
    val currencyQuery: String = "",
    val isCurrencySelectedFromDropdown: Boolean = true,
    val currencyOptions: List<Pair<String, String>> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)