package com.cpm.cleave.ui.features.profile

sealed interface ProfileUiEffect {
    data class ShowMessage(val message: String) : ProfileUiEffect
    data object NavigateToSignIn : ProfileUiEffect
    data object NavigateToRegister : ProfileUiEffect
    data object SignedOut : ProfileUiEffect
}
