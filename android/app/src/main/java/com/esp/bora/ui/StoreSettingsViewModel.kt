package com.esp.bora.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.esp.bora.data.ImageUtils
import com.esp.bora.data.StoreContact
import com.esp.bora.data.StorePrivateSettings
import com.esp.bora.data.StoreProfile
import com.esp.bora.data.StoreRepository
import com.esp.bora.data.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class StoreSettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val storeId: String = "",
    val profile: StoreProfile = StoreProfile(),
    val settings: StorePrivateSettings = StorePrivateSettings(),
    val startTimeText: String = "09:00",
    val endTimeText: String = "18:00",
    val selectedLogoUri: Uri? = null,
    val contacts: List<StoreContact> = emptyList(),
    val error: String? = null,
    val message: String? = null,
)

class StoreSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = StoreRepository()
    private val _state = MutableStateFlow(StoreSettingsUiState())
    val state: StateFlow<StoreSettingsUiState> = _state.asStateFlow()

    private var currentUid: String? = null
    private var profileJob: Job? = null
    private var settingsJob: Job? = null
    private var contactsJob: Job? = null

    fun start(uid: String) {
        if (currentUid == uid && profileJob?.isActive == true) return
        currentUid = uid
        profileJob?.cancel()
        settingsJob?.cancel()
        contactsJob?.cancel()
        viewModelScope.launch {
            try {
                val storeId = repository.resolveStoreId(uid)
                _state.value = StoreSettingsUiState(
                    loading = false,
                    storeId = storeId,
                    profile = StoreProfile(id = storeId, ownerUid = uid),
                    settings = StorePrivateSettings(ownerUid = uid),
                )
                profileJob = viewModelScope.launch {
                    repository.observeProfile(storeId)
                        .catch { error -> _state.update { it.copy(error = error.toUserMessage()) } }
                        .collect { profile ->
                            _state.update {
                                it.copy(
                                    profile = profile ?: it.profile.copy(id = storeId, ownerUid = uid),
                                    loading = false,
                                )
                            }
                        }
                }
                settingsJob = viewModelScope.launch {
                    repository.observePrivateSettings(uid)
                        .catch { error -> _state.update { it.copy(error = error.toUserMessage()) } }
                        .collect { settings ->
                            _state.update {
                                it.copy(
                                    settings = settings,
                                    startTimeText = formatTime(settings.contactNotificationStartMinutes),
                                    endTimeText = formatTime(settings.contactNotificationEndMinutes),
                                )
                            }
                        }
                }
                contactsJob = viewModelScope.launch {
                    repository.observeContacts(uid)
                        .catch { error -> _state.update { it.copy(error = error.toUserMessage()) } }
                        .collect { contacts -> _state.update { it.copy(contacts = contacts) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.toUserMessage()) }
            }
        }
    }

    fun updateProfile(transform: (StoreProfile) -> StoreProfile) {
        _state.update { it.copy(profile = transform(it.profile), error = null, message = null) }
    }

    fun updateSettings(transform: (StorePrivateSettings) -> StorePrivateSettings) {
        _state.update { it.copy(settings = transform(it.settings), error = null, message = null) }
    }

    fun updateStartTime(value: String) {
        _state.update { it.copy(startTimeText = value.filter { char -> char.isDigit() || char == ':' }.take(5), error = null) }
    }

    fun updateEndTime(value: String) {
        _state.update { it.copy(endTimeText = value.filter { char -> char.isDigit() || char == ':' }.take(5), error = null) }
    }

    fun selectLogo(uri: Uri) {
        _state.update { it.copy(selectedLogoUri = uri, error = null, message = null) }
    }

    fun save(uid: String) {
        val current = _state.value
        if (current.saving) return
        if (current.profile.name.isBlank()) {
            _state.update { it.copy(error = "Escribe el nombre de tu tienda.") }
            return
        }
        val startMinutes = parseTime(current.startTimeText)
        val endMinutes = parseTime(current.endTimeText)
        if (current.profile.pickupAddress.isNotBlank() &&
            (current.profile.pickupLatitude !in -90.0..90.0 ||
                current.profile.pickupLongitude !in -180.0..180.0 ||
                (current.profile.pickupLatitude == 0.0 && current.profile.pickupLongitude == 0.0))
        ) {
            _state.update { it.copy(error = "Ingresa coordenadas válidas para mostrar el punto de venta.") }
            return
        }
        if (current.settings.contactNotificationsEnabled &&
            (startMinutes == null || endMinutes == null || startMinutes >= endMinutes)
        ) {
            _state.update { it.copy(error = "Revisa el horario: usa HH:MM y una hora de término posterior.") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null, message = null) }
            try {
                val logoBytes = current.selectedLogoUri?.let { uri ->
                    withContext(Dispatchers.IO) {
                        ImageUtils.compressToJpeg(getApplication(), uri)
                    }
                }
                val settings = current.settings.copy(
                    ownerUid = uid,
                    contactNotificationStartMinutes = startMinutes ?: current.settings.contactNotificationStartMinutes,
                    contactNotificationEndMinutes = endMinutes ?: current.settings.contactNotificationEndMinutes,
                )
                repository.save(
                    uid = uid,
                    storeId = current.storeId,
                    profile = current.profile,
                    settings = settings,
                    logoBytes = logoBytes,
                )
                _state.update {
                    it.copy(
                        saving = false,
                        selectedLogoUri = null,
                        message = "Datos públicos de la tienda guardados.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.toUserMessage()) }
            }
        }
    }

    fun clearFeedback() {
        _state.update { it.copy(error = null, message = null) }
    }

    private fun parseTime(value: String): Int? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun formatTime(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)
}
