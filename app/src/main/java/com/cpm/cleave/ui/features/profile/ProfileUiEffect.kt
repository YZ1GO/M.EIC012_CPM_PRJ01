package com.cpm.cleave.ui.features.profile

sealed interface ProfileUiEffect {
    data class ShowMessage(val message: String) : ProfileUiEffect
    data object NavigateToSignIn : ProfileUiEffect
    data object NavigateToRegister : ProfileUiEffect
    data object SignedOut : ProfileUiEffect
    // TODO(debug-cleanup): remove this effect when debug switch-user tools are removed.
    data object DebugUserSwitched : ProfileUiEffect
    // TODO(debug-cleanup): remove this effect when debug clear-data tools are removed.
    data object DebugDataCleared : ProfileUiEffect
}
