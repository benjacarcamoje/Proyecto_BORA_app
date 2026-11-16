package com.esp.bora.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.esp.bora.data.Product
import com.esp.bora.data.ProductRepository
import com.esp.bora.data.FairRepository
import com.esp.bora.data.Sale
import com.esp.bora.data.SaleRequest
import com.esp.bora.data.SalesRepository
import com.esp.bora.data.StoreRepository
import com.esp.bora.data.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SalesUiState(
    val loading: Boolean = true,
    val products: List<Product> = emptyList(),
    val sales: List<Sale> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val salesHistoryEnabled: Boolean = false,
    val suggestedFairName: String? = null,
    val locationSuggestedFairName: String? = null,
)

class SalesViewModel(
    private val productsRepo: ProductRepository = ProductRepository(),
    private val salesRepo: SalesRepository = SalesRepository(),
    private val storeRepo: StoreRepository = StoreRepository(),
    private val fairRepo: FairRepository = FairRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(SalesUiState())
    val state: StateFlow<SalesUiState> = _state.asStateFlow()
    private var productsJob: Job? = null
    private var salesJob: Job? = null
    private var settingsJob: Job? = null
    private var fairSuggestionsJob: Job? = null
    private var currentUid: String? = null

    fun start(uid: String) {
        if (uid == currentUid && productsJob?.isActive == true && settingsJob?.isActive == true) return
        currentUid = uid
        productsJob?.cancel()
        salesJob?.cancel()
        settingsJob?.cancel()
        fairSuggestionsJob?.cancel()
        _state.value = SalesUiState()
        productsJob = viewModelScope.launch {
            productsRepo.observeMyProducts(uid)
                .catch { e -> _state.update { it.copy(loading = false, error = e.toUserMessage()) } }
                .collect { products ->
                    _state.update { it.copy(loading = false, products = products) }
                }
        }
        fairSuggestionsJob = viewModelScope.launch {
            combine(fairRepo.observeUpcomingFairs(), fairRepo.observeMyInvitations(uid)) { fairs, invitations ->
                val confirmedIds = invitations.filter { it.status == "confirmada" }.map { it.fairId }.toSet()
                val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                fairs.firstOrNull { fair ->
                    fair.id in confirmedIds && fair.startsAt?.toDate()?.toInstant()
                        ?.atZone(java.time.ZoneId.systemDefault())?.toLocalDate() == today
                }?.name
            }.catch { error -> _state.update { it.copy(error = error.toUserMessage()) } }
                .collect { name -> _state.update { it.copy(suggestedFairName = name) } }
        }
        settingsJob = viewModelScope.launch {
            storeRepo.observePrivateSettings(uid)
                .catch { e -> _state.update { it.copy(error = e.toUserMessage()) } }
                .collect { settings ->
                    _state.update {
                        it.copy(
                            salesHistoryEnabled = settings.salesHistoryEnabled,
                            sales = if (settings.salesHistoryEnabled) it.sales else emptyList(),
                        )
                    }
                    if (settings.salesHistoryEnabled && salesJob?.isActive != true) {
                        salesJob = viewModelScope.launch {
                            salesRepo.observeMySales(uid)
                                .catch { e -> _state.update { it.copy(error = e.toUserMessage()) } }
                                .collect { sales ->
                                    if (_state.value.salesHistoryEnabled) {
                                        _state.update { it.copy(sales = sales) }
                                    }
                                }
                        }
                    } else if (!settings.salesHistoryEnabled) {
                        salesJob?.cancel()
                        salesJob = null
                    }
                }
        }
    }

    fun suggestFairNear(latitude: Double, longitude: Double) {
        val uid = currentUid ?: return
        viewModelScope.launch {
            runCatching { fairRepo.suggestFairNear(uid, latitude, longitude) }
                .onSuccess { name ->
                    _state.update {
                        it.copy(
                            locationSuggestedFairName = name,
                            message = if (name == null) "No se encontró una feria confirmada cerca de tu ubicación." else null,
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(error = error.toUserMessage()) } }
        }
    }

    fun registerSale(request: SaleRequest) {
        if (_state.value.busy) return
        val uid = currentUid ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, message = null) }
            try {
                val settings = storeRepo.getPrivateSettings(uid)
                val effectiveRequest = request.copy(
                    saveHistory = request.saveHistory && settings.salesHistoryEnabled,
                )
                _state.update { it.copy(salesHistoryEnabled = settings.salesHistoryEnabled) }
                salesRepo.registerSale(effectiveRequest)
                _state.update {
                    it.copy(
                        busy = false,
                        message = if (effectiveRequest.saveHistory) "Venta guardada en el historial."
                        else "Venta registrada y stock actualizado.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.toUserMessage()) }
            }
        }
    }

    fun clearFeedback() = _state.update { it.copy(error = null, message = null) }
}
