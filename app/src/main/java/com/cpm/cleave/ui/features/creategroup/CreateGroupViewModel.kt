package com.cpm.cleave.ui.features.creategroup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cpm.cleave.domain.usecase.RequestCreateGroupUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Currency
import java.util.Locale

class CreateGroupViewModel (
    private val requestCreateGroupUseCase: RequestCreateGroupUseCase
) : ViewModel() {

    // The internal state that can be changed
    private val _uiState = MutableStateFlow(CreateGroupUiState())

    // The public state the UI reads (read-only)
    val uiState: StateFlow<CreateGroupUiState> = _uiState.asStateFlow()
    private var selectedImageBytes: ByteArray? = null

    init {
        val options = buildCurrencyOptions()
        val defaultCurrency = "EUR"
        val defaultLabel = options.firstOrNull { it.first == defaultCurrency }?.second ?: defaultCurrency
        _uiState.update {
            it.copy(
                selectedCurrencyCode = defaultCurrency,
                currencyQuery = defaultLabel,
                isCurrencySelectedFromDropdown = true,
                currencyOptions = options
            )
        }
    }

    fun onNameChanged(newName: String) {
        _uiState.update { it.copy(Name = newName, errorMessage = null) }
    }

    fun onCurrencyChanged(newCurrency: String) {
        val allowedCurrencies = _uiState.value.currencyOptions.map { it.first }.toSet()
        if (newCurrency !in allowedCurrencies) {
            _uiState.update { it.copy(errorMessage = "Please select a currency from the list.") }
            return
        }

        val label = _uiState.value.currencyOptions
            .firstOrNull { it.first == newCurrency }
            ?.second
            ?: newCurrency

        _uiState.update {
            it.copy(
                selectedCurrencyCode = newCurrency,
                currencyQuery = label,
                isCurrencySelectedFromDropdown = true,
                errorMessage = null
            )
        }
    }

    fun onCurrencyInputChanged(input: String) {
        _uiState.update {
            it.copy(
                currencyQuery = input,
                selectedCurrencyCode = "",
                isCurrencySelectedFromDropdown = false,
                errorMessage = null
            )
        }
    }

    fun onGroupImageSelected(uri: String?, imageBytes: ByteArray?) {
        selectedImageBytes = imageBytes
        _uiState.update {
            it.copy(
                selectedImageUri = uri,
                uploadedImageUrl = null,
                errorMessage = null
            )
        }
    }

    fun createGroup(onSuccess: () -> Unit) {
        val state = _uiState.value
        if (state.Name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Group name is required") }
            return
        }

        val allowedCurrencies = state.currencyOptions.map { it.first }.toSet()
        if (state.selectedCurrencyCode !in allowedCurrencies) {
            _uiState.update { it.copy(errorMessage = "Please select a currency from the list.") }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            val uploadedImageUrl = if (state.uploadedImageUrl.isNullOrBlank() && selectedImageBytes != null) {
                requestCreateGroupUseCase.uploadGroupImage(selectedImageBytes!!)
                    .onSuccess { url ->
                        _uiState.update { current -> current.copy(uploadedImageUrl = url) }
                    }
                    .getOrElse { error ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                errorMessage = error.message ?: "Could not upload group image"
                            )
                        }
                        return@launch
                    }
            } else {
                state.uploadedImageUrl
            }

            val result = requestCreateGroupUseCase.execute(
                state.Name,
                state.selectedCurrencyCode,
                uploadedImageUrl
            )
            if (result.isSuccess) {
                _uiState.update { it.copy(isLoading = false) }
                onSuccess()
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Could not create group"
                    )
                }
            }
        }
    }

    private fun buildCurrencyOptions(): List<Pair<String, String>> {
        return Currency.getAvailableCurrencies()
            .asSequence()
            .map { it.currencyCode }
            .distinct()
            .sorted()
            .map { code ->
                val name = runCatching {
                    Currency.getInstance(code).getDisplayName(Locale.getDefault())
                }.getOrDefault(code)

                code to "$code - $name"
            }
            .toList()
    }
}