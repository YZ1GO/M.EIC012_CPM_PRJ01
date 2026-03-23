package com.cpm.cleave.ui.features.groups

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.usecase.GetGroupsUseCase
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class GroupsViewModel(
    private val getGroupsUseCase: GetGroupsUseCase
) : ViewModel() {

    // The internal state that can be changed
    private val _uiState = MutableStateFlow(GroupsUiState())

    // The public state the UI reads (read-only)
    val uiState: StateFlow<GroupsUiState> = _uiState.asStateFlow()

    init {
        observeGroups()
        loadGroups()
    }

    private fun observeGroups() {
        viewModelScope.launch {
            getGroupsUseCase.observe()
                .catch { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = error.message ?: "Could not load groups"
                        )
                    }
                }
                .collect { observedGroups ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            groups = observedGroups,
                            errorMessage = null
                        )
                    }
                }
        }
    }

    fun loadGroups() {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            getGroupsUseCase.execute()
                .onSuccess { savedGroups ->
                    _uiState.update {
                        it.copy(isLoading = false, groups = savedGroups, errorMessage = null)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            groups = emptyList(),
                            errorMessage = error.message ?: "Could not load groups"
                        )
                    }
                }
        }
    }

    fun onSearchQueryChanged(newQuery: String) {
        _uiState.update { currentState ->
            currentState.copy(searchQuery = newQuery)
        }
        // TODO: Later, tell the repository to filter the groups based on this query
    }
}