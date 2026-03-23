package com.cpm.cleave.ui.features.groups

import com.cpm.cleave.model.Group

data class GroupsUiState(
    val isLoading: Boolean = true,
    val groups: List<Group> = emptyList(),
    val searchQuery: String = "",
    val errorMessage: String? = null
)