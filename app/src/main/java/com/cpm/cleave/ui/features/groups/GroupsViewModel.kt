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
                            errorMessage = error.message ?: "Could not load groups"
                        )
                    }
                }
                .collect { observedGroups ->
                    _uiState.update {
                        it.copy(
                            groups = observedGroups,
                            errorMessage = null
                        )
                    }
                }
        }
    }

    fun loadGroups() {
        val cachedGroups = _uiState.value.groups
        val shouldBlockUi = cachedGroups.isEmpty()

        _uiState.update {
            it.copy(
                isLoading = shouldBlockUi,
                errorMessage = if (shouldBlockUi) null else it.errorMessage
            )
        }

        viewModelScope.launch {
            getGroupsUseCase.execute()
                .onSuccess { savedGroups ->
                    _uiState.update {
                        it.copy(isLoading = false, groups = savedGroups, errorMessage = null)
                    }
                    _uiState.update {
                        it.copy(loadCompletionToken = System.currentTimeMillis())
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            // Keep cached groups visible if sync fails.
                            groups = if (cachedGroups.isNotEmpty()) cachedGroups else emptyList(),
                            errorMessage = if (cachedGroups.isEmpty()) {
                                error.message ?: "Could not load groups"
                            } else {
                                null
                            }
                        )
                    }
                    _uiState.update {
                        it.copy(loadCompletionToken = System.currentTimeMillis())
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