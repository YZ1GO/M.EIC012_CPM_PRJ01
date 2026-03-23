package com.cpm.cleave.ui.features.auth

data class AuthUiState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val isRegisterMode: Boolean = false,
    val mergeGuestDataOnSignIn: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isAuthenticated: Boolean = false
)
