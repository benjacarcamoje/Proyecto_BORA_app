package com.esp.bora.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.esp.bora.data.AccountMode
import com.esp.bora.data.AuthRepository
import com.esp.bora.data.FollowRepository
import com.esp.bora.data.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val uid: String? = null,
    val email: String? = null,
    val mode: AccountMode? = null,
    val selectedMode: AccountMode? = null,
    val guest: Boolean = false,
    val anonymousNavigation: String = "require_login",
    val policyLoading: Boolean = true,
    val policyError: String? = null,
    val promptSeen: Boolean = false,
    val pendingFollowStoreId: String? = null,
    val unlocked: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val resetBusy: Boolean = false,
    val resetMessage: String? = null,
    val resetError: String? = null,
) {
    val signedIn: Boolean get() = uid != null
}

class AuthViewModel(
    private val repo: AuthRepository = AuthRepository(),
) : ViewModel() {
    private val _state = MutableStateFlow(
        AuthUiState(
            uid = repo.currentUser?.uid,
            email = repo.currentUser?.email,
            mode = repo.currentMode,
            unlocked = repo.currentMode == AccountMode.BUYER,
        ),
    )
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val policy = repo.loadAnonymousNavigation()
                _state.update { it.copy(anonymousNavigation = policy, policyLoading = false, policyError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // La entrada como invitado está disponible aunque falle la política remota.
                _state.update {
                    it.copy(
                        anonymousNavigation = "require_login",
                        policyLoading = false,
                        policyError = "No se pudo cargar la configuración de acceso. Puedes ingresar sin iniciar sesión.",
                    )
                }
            }
        }
        repo.currentUser?.uid?.let { uid ->
            registerPushToken(uid)
            viewModelScope.launch {
                runCatching { repo.resolveCurrentMode() }.getOrNull()?.let { mode ->
                    if (_state.value.uid == uid) _state.update { it.copy(mode = mode) }
                }
            }
        }
    }

    private fun registerPushToken(uid: String) {
        viewModelScope.launch {
            runCatching { FollowRepository().registerCurrentDevice(uid) }
        }
    }

    fun selectMode(mode: AccountMode) {
        if (_state.value.uid != null) return
        _state.update { it.copy(selectedMode = mode, error = null) }
    }

    fun backToModeSelection() {
        if (_state.value.uid == null && !_state.value.busy) {
            _state.update { it.copy(selectedMode = null, pendingFollowStoreId = null, error = null) }
        }
    }

    fun requestSignInForFollow(storeId: String) {
        val current = _state.value
        if (
            storeId.isBlank() || current.uid != null || current.policyLoading ||
            current.anonymousNavigation == "require_login"
        ) return
        _state.update {
            it.copy(
                guest = false,
                selectedMode = AccountMode.BUYER,
                pendingFollowStoreId = storeId,
                promptSeen = true,
                error = null,
            )
        }
    }

    fun completePendingFollow(storeId: String) {
        _state.update {
            if (it.pendingFollowStoreId == storeId) it.copy(pendingFollowStoreId = null) else it
        }
    }

    fun acknowledgeAnonymousPrompt() {
        _state.update { it.copy(promptSeen = true) }
    }

    fun enterGuest() {
        _state.update {
            it.copy(
                guest = true,
                mode = null,
                selectedMode = null,
                promptSeen = true,
                error = null,
            )
        }
    }

    fun leaveGuest() {
        _state.update {
            it.copy(
                guest = false,
                mode = null,
                selectedMode = null,
                pendingFollowStoreId = null,
                promptSeen = true,
                error = null,
            )
        }
    }

    fun signInEntrepreneur(email: String, password: String) {
        if (_state.value.busy || _state.value.resetBusy) return
        if (email.isBlank() || password.isEmpty()) {
            _state.update { it.copy(error = "Ingresa tu correo y tu contraseña.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                repo.signInEntrepreneur(email, password)
                repo.currentUser?.uid?.let(::registerPushToken)
                val user = repo.currentUser
                val mode = repo.resolveCurrentMode() ?: AccountMode.ENTREPRENEUR
                _state.value = AuthUiState(
                    uid = user?.uid,
                    email = user?.email,
                    mode = mode,
                    selectedMode = mode,
                    unlocked = true,
                    anonymousNavigation = _state.value.anonymousNavigation,
                    policyLoading = _state.value.policyLoading,
                    policyError = _state.value.policyError,
                    promptSeen = _state.value.promptSeen,
                    pendingFollowStoreId = _state.value.pendingFollowStoreId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.toUserMessage()) }
            }
        }
    }

    fun signInBuyerWithGoogle(idToken: String) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                repo.signInBuyerWithGoogle(idToken)
                repo.currentUser?.uid?.let(::registerPushToken)
                val user = repo.currentUser
                _state.value = AuthUiState(
                    uid = user?.uid,
                    email = user?.email,
                    mode = AccountMode.BUYER,
                    selectedMode = AccountMode.BUYER,
                    unlocked = true,
                    anonymousNavigation = _state.value.anonymousNavigation,
                    policyLoading = _state.value.policyLoading,
                    policyError = _state.value.policyError,
                    promptSeen = _state.value.promptSeen,
                    pendingFollowStoreId = _state.value.pendingFollowStoreId,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.toUserMessage()) }
            }
        }
    }

    fun sendPasswordReset(email: String) {
        val normalizedEmail = email.trim()
        if (normalizedEmail.isBlank()) {
            _state.update {
                it.copy(
                    error = null,
                    resetMessage = null,
                    resetError = "Escribe tu correo para enviarte las instrucciones.",
                )
            }
            return
        }
        if (_state.value.resetBusy || _state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(error = null, resetBusy = true, resetMessage = null, resetError = null) }
            try {
                repo.sendPasswordResetEmail(normalizedEmail)
                _state.update {
                    it.copy(
                        resetBusy = false,
                        resetMessage = "Si existe una cuenta con ese correo, enviaremos instrucciones para recuperar el acceso.",
                        resetError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        resetBusy = false,
                        resetMessage = null,
                        resetError = "No se pudo enviar el correo. Revisa la dirección y tu conexión e inténtalo nuevamente.",
                    )
                }
            }
        }
    }

    fun clearPasswordResetFeedback() {
        _state.update { it.copy(resetMessage = null, resetError = null) }
    }

    fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    fun unlock() {
        _state.update { it.copy(unlocked = true, error = null) }
    }

    fun signOut() {
        repo.signOut()
        val current = _state.value
        _state.value = AuthUiState(
            anonymousNavigation = current.anonymousNavigation,
            policyLoading = current.policyLoading,
            policyError = current.policyError,
            promptSeen = current.promptSeen,
        )
    }
}
