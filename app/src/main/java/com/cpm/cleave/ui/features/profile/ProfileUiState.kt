package com.cpm.cleave.ui.features.profile

import com.cpm.cleave.model.User

data class ProfileUiState(
    val isLoading: Boolean = true,
    val currentUser: User? = null,
    val errorMessage: String? = null,
    val maxGroups: Int = 0,
    val isBusy: Boolean = false,
    val pendingPhotoUri: String? = null,
    val isUploadingPhoto: Boolean = false
)
