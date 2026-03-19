package com.cpm.cleave.ui.theme

import androidx.lifecycle.ViewModel
import com.cpm.cleave.data.Repository
import com.cpm.cleave.model.Group
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class GroupsViewModel(
    private val repository: Repository
) : ViewModel() {

    // The internal state that can be changed
    private val _uiState = MutableStateFlow(GroupsUiState())

    // The public state the UI reads (read-only)
    val uiState: StateFlow<GroupsUiState> = _uiState.asStateFlow()

    init {
        loadGroups()
    }
    fun loadGroups() {
        repository.getGroups()
            .onSuccess { savedGroups ->
                _uiState.update {
                    it.copy(isLoading = false, groups = savedGroups)
                }
            }
            .onFailure { error ->
                _uiState.update {
                    it.copy(isLoading = false, groups = emptyList())
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