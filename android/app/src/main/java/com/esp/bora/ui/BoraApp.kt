package com.esp.bora.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.esp.bora.data.AccountMode
import com.esp.bora.data.BiometricPreferences
import com.esp.bora.data.Product
import kotlinx.coroutines.launch

@Composable
fun BoraApp(
    activity: FragmentActivity,
    authVm: AuthViewModel = viewModel(),
) {
    val auth by authVm.state.collectAsStateWithLifecycle()
    val biometricAvailable = remember(activity) { Biometrics.isAvailable(activity) }
    var biometricEnabled by rememberSaveable(auth.uid) {
        mutableStateOf(auth.uid?.let { BiometricPreferences.isEnabled(activity, it) } ?: false)
    }
    LaunchedEffect(auth.uid) {
        biometricEnabled = auth.uid?.let { BiometricPreferences.isEnabled(activity, it) } ?: false
    }
    val credentialManager = remember(activity) { CredentialManager.create(activity) }
    val scope = rememberCoroutineScope()
    val signOut: () -> Unit = {
        authVm.signOut()
        scope.launch {
            runCatching { credentialManager.clearCredentialState(ClearCredentialStateRequest()) }
        }
        Unit
    }
    val uid = auth.uid
    val onBiometricEnabledChange: (Boolean) -> Unit = { enabled ->
        val accountUid = uid
        if (!enabled) {
            if (accountUid != null) BiometricPreferences.setEnabled(activity, accountUid, false)
            biometricEnabled = false
        } else if (accountUid != null && biometricAvailable) {
            Biometrics.authenticate(
                activity = activity,
                onSuccess = {
                    BiometricPreferences.setEnabled(activity, accountUid, true)
                    biometricEnabled = true
                },
                onUsePassword = {},
                onError = {},
                promptTitle = "Activar acceso con huella",
                promptSubtitle = "Confirma tu huella para activar el acceso rápido",
                negativeButtonText = "Cancelar",
            )
        }
    }

    LaunchedEffect(uid, auth.mode) {
        if (uid != null && auth.mode == null) authVm.signOut()
    }

    when {
        uid == null && auth.guest -> BuyerHomeHost(
            uid = null,
            email = null,
            onSignOut = authVm::leaveGuest,
            onRequestSignIn = authVm::requestSignInForFollow,
        )
        uid == null && auth.anonymousNavigation == "prompt" && !auth.promptSeen -> AnonymousAccessPrompt(
            onSignIn = authVm::acknowledgeAnonymousPrompt,
            onSkip = authVm::enterGuest,
        )
        uid == null && auth.selectedMode == null -> AccountModeScreen(
            onChoose = authVm::selectMode,
            onExploreAnonymously = authVm::enterGuest,
            message = auth.policyError,
        )
        uid == null && auth.selectedMode == AccountMode.BUYER -> BuyerSignInScreen(
            activity = activity,
            busy = auth.busy,
            error = auth.error ?: auth.policyError,
            onGoogleToken = authVm::signInBuyerWithGoogle,
            onBack = authVm::backToModeSelection,
        )
        uid == null -> LoginScreen(
            busy = auth.busy,
            error = auth.error ?: auth.policyError,
            resetBusy = auth.resetBusy,
            resetMessage = auth.resetMessage,
            resetError = auth.resetError,
            onSignIn = authVm::signInEntrepreneur,
            onResetPassword = authVm::sendPasswordReset,
            onClearResetFeedback = authVm::clearPasswordResetFeedback,
            onBackToModes = authVm::backToModeSelection,
        )
        auth.mode == AccountMode.ADMIN -> AdminScreen(onSignOut = signOut)
        auth.mode == AccountMode.BUYER -> BuyerHomeHost(
            uid = uid,
            email = auth.email,
            onSignOut = signOut,
            onRequestSignIn = authVm::requestSignInForFollow,
            pendingFollowStoreId = auth.pendingFollowStoreId,
            onPendingFollowHandled = authVm::completePendingFollow,
        )
        auth.mode == AccountMode.ENTREPRENEUR && !auth.unlocked && biometricAvailable && biometricEnabled -> {
            val askFingerprint = {
                Biometrics.authenticate(
                    activity = activity,
                    onSuccess = authVm::unlock,
                    onUsePassword = signOut,
                    onError = authVm::showError,
                )
            }
            var prompted by rememberSaveable(uid) { mutableStateOf(false) }
            LaunchedEffect(uid) {
                if (!prompted) {
                    prompted = true
                    askFingerprint()
                }
            }
            LockScreen(
                error = auth.error,
                onUseFingerprint = askFingerprint,
                onUsePassword = signOut,
            )
        }
        auth.mode == AccountMode.ENTREPRENEUR -> HomeHost(
            uid = uid,
            onSignOut = signOut,
            biometricAvailable = biometricAvailable,
            biometricEnabled = biometricEnabled,
            onBiometricEnabledChange = onBiometricEnabledChange,
        )
        else -> AccountModeScreen(authVm::selectMode)
    }
}

@Composable
private fun BuyerHomeHost(
    uid: String?,
    email: String?,
    onSignOut: () -> Unit,
    onRequestSignIn: (String) -> Unit,
    pendingFollowStoreId: String? = null,
    onPendingFollowHandled: (String) -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf("catalog") }
    LaunchedEffect(pendingFollowStoreId) {
        if (pendingFollowStoreId?.startsWith("association:") == true) page = "directory"
    }
    when (page) {
        "fairs" -> FairsScreen(uid = uid, onBack = { page = "catalog" })
        "directory" -> PublicDirectoryScreen(
            uid = uid,
            onBack = { page = "catalog" },
            onRequestSignIn = onRequestSignIn,
            pendingAssociationId = pendingFollowStoreId?.takeIf { it.startsWith("association:") }?.removePrefix("association:"),
            onPendingFollowHandled = onPendingFollowHandled,
        )
        else -> BuyerCatalogScreen(
            uid = uid,
            email = email,
            onSignOut = onSignOut,
            onFairs = { page = "fairs" },
            onDirectory = { page = "directory" },
            onRequestSignIn = onRequestSignIn,
            pendingFollowStoreId = pendingFollowStoreId,
            onPendingFollowHandled = onPendingFollowHandled,
        )
    }
}

@Composable
private fun HomeHost(
    uid: String,
    onSignOut: () -> Unit,
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    onBiometricEnabledChange: (Boolean) -> Unit,
) {
    var page by rememberSaveable { mutableStateOf("products") }
    var editingProduct by remember { mutableStateOf<Product?>(null) }
    var saleProduct by remember { mutableStateOf<Product?>(null) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    when (page) {
        "store_settings" -> StoreSettingsScreen(
            uid = uid,
            onBack = { page = "products" },
            biometricAvailable = biometricAvailable,
            biometricEnabled = biometricEnabled,
            onBiometricEnabledChange = onBiometricEnabledChange,
        )
        "editor" -> NewProductScreen(
            product = editingProduct,
            onClose = { saved ->
                page = "products"
                if (saved) message = if (editingProduct == null) "Producto publicado." else "Cambios guardados."
                editingProduct = null
            },
        )
        "sales" -> SalesScreen(
            uid = uid,
            initialProduct = saleProduct,
            onBack = {
                page = "products"
                saleProduct = null
            },
        )
        "fairs" -> FairsScreen(uid = uid, onBack = { page = "products" })
        else -> ProductsScreen(
            uid = uid,
            message = message,
            onMessageShown = { message = null },
            onNewProduct = {
                editingProduct = null
                page = "editor"
            },
            onEditProduct = { product ->
                editingProduct = product
                page = "editor"
            },
            onRecordSale = { product ->
                saleProduct = product
                page = "sales"
            },
            onSales = { page = "sales" },
            onFairs = { page = "fairs" },
            onSignOut = onSignOut,
            onStoreSettings = { page = "store_settings" },
        )
    }
}