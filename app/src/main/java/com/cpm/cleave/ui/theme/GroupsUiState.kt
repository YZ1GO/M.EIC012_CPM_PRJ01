package com.cpm.cleave.ui.theme

import com.cpm.cleave.model.Group

data class GroupsUiState(
    val isLoading: Boolean = true,
    val groups: List<Group> = emptyList(),
    val searchQuery: String = ""
)