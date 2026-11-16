package com.esp.bora.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.esp.bora.data.Product
import com.esp.bora.data.ProductRepository
import com.esp.bora.data.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProductsUiState(
    val loading: Boolean = true,
    val products: List<Product> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val updatingAvailability: Boolean = false,
)

class ProductsViewModel(
    private val repo: ProductRepository = ProductRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(ProductsUiState())
    val state: StateFlow<ProductsUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var currentUid: String? = null

    fun start(uid: String) {
        if (uid == currentUid && job?.isActive == true) return
        currentUid = uid
        job?.cancel()
        _state.value = ProductsUiState()
        job = viewModelScope.launch {
            repo.observeMyProducts(uid)
                .catch { e -> _state.update { it.copy(loading = false, error = e.toUserMessage()) } }
                .collect { list -> _state.value = ProductsUiState(loading = false, products = list) }
        }
    }

    fun setAvailability(products: List<Product>, available: Boolean) {
        val uid = currentUid ?: return
        if (_state.value.updatingAvailability) return
        if (products.isEmpty()) {
            _state.update { it.copy(error = "Selecciona productos para actualizar.") }
            return
        }
        if (products.any { it.ownerUid != uid }) {
            _state.update { it.copy(error = "Solo puedes cambiar la disponibilidad de tus productos.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(updatingAvailability = true, error = null, message = null) }
            try {
                repo.setAvailability(products.map { it.id }, available)
                _state.update {
                    it.copy(
                        updatingAvailability = false,
                        message = if (available) "Productos marcados como disponibles."
                        else "Productos ocultos del catálogo.",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(updatingAvailability = false, error = e.toUserMessage()) }
            }
        }
    }

    fun deleteProduct(product: Product) {
        viewModelScope.launch {
            try {
                repo.deleteProduct(product)
                _state.update { it.copy(message = "Producto eliminado.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    fun clearFeedback() {
        _state.update { it.copy(error = null, message = null) }
    }
}
