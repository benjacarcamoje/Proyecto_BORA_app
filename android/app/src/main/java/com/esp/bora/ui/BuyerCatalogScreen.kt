package com.esp.bora.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.esp.bora.data.DemoContent
import com.esp.bora.data.Product
import com.esp.bora.data.ProductRepository
import com.esp.bora.data.FollowRepository
import com.esp.bora.data.StoreProfile
import com.esp.bora.data.StoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class BuyerCatalogUiState(
    val loading: Boolean = true,
    val products: List<Product> = emptyList(),
    val error: String? = null,
    val followedStoreIds: Set<String> = emptySet(),
    val storeProfiles: Map<String, StoreProfile> = emptyMap(),
)

class BuyerCatalogViewModel(
    private val repo: ProductRepository = ProductRepository(),
    private val followRepository: FollowRepository = FollowRepository(),
    private val storeRepository: StoreRepository = StoreRepository(),
) : ViewModel() {
    private var followedUid: String? = null
    private val _state = MutableStateFlow(BuyerCatalogUiState())
    val state: StateFlow<BuyerCatalogUiState> = _state

    fun startFollowing(uid: String) {
        if (followedUid == uid) return
        followedUid = uid
        viewModelScope.launch {
            followRepository.observeFollowedStores(uid)
                .catch { error -> _state.value = _state.value.copy(error = error.message) }
                .collect { ids -> _state.value = _state.value.copy(followedStoreIds = ids) }
        }
    }

    suspend fun ensureStoreFollow(uid: String, storeId: String, notificationsEnabled: Boolean) {
        followRepository.setStoreFollow(uid, storeId, following = true, notificationsEnabled = notificationsEnabled)
    }

    fun toggleFollow(uid: String, storeId: String, notificationsEnabled: Boolean) {
        viewModelScope.launch {
            val following = storeId !in _state.value.followedStoreIds
            runCatching {
                followRepository.setStoreFollow(uid, storeId, following, notificationsEnabled)
            }.onFailure { error -> _state.value = _state.value.copy(error = error.message) }
        }
    }

    init {
        viewModelScope.launch {
            storeRepository.observePublicProfiles()
                .catch { }
                .collect { profiles ->
                    _state.value = _state.value.copy(storeProfiles = profiles.associateBy { it.id })
                }
        }
        viewModelScope.launch {
            repo.observePublicProducts()
                .catch { error -> _state.value = _state.value.copy(loading = false, error = error.message) }
                .collect { products -> _state.value = _state.value.copy(loading = false, products = products) }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BuyerCatalogScreen(
    uid: String?,
    email: String?,
    onSignOut: () -> Unit,
    onFairs: () -> Unit,
    onDirectory: () -> Unit,
    onRequestSignIn: (String) -> Unit,
    pendingFollowStoreId: String? = null,
    onPendingFollowHandled: (String) -> Unit = {},
    vm: BuyerCatalogViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val useDemoCatalog = uid == null && state.products.isEmpty()
    val catalogProducts = if (useDemoCatalog) DemoContent.products else state.products
    val visible = catalogProducts.filter { it.visibleInPublicCatalog }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingStoreId by remember { mutableStateOf<String?>(null) }
    var permissionRequestIsForPendingFollow by remember { mutableStateOf(false) }
    var pendingFollowAttemptedId by remember { mutableStateOf<String?>(null) }
    var pendingFollowFailed by remember { mutableStateOf(false) }
    var pendingFollowNotice by remember { mutableStateOf<String?>(null) }
    var pendingFollowRetry by remember { mutableStateOf(0) }
    var showSignInPrompt by remember { mutableStateOf(false) }

    fun completePendingFollow(storeId: String, notificationsEnabled: Boolean) {
        pendingStoreId = null
        scope.launch {
            val userId = uid ?: return@launch
            try {
                vm.ensureStoreFollow(userId, storeId, notificationsEnabled)
                pendingFollowFailed = false
                pendingFollowNotice = if (notificationsEnabled) {
                    "Listo, ya sigues esta tienda y recibirás avisos."
                } else {
                    "Sigues esta tienda. Revisa los permisos de notificaciones del teléfono para recibir avisos."
                }
                onPendingFollowHandled(storeId)
            } catch (_: Exception) {
                pendingFollowFailed = true
            }
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val storeId = pendingStoreId
        pendingStoreId = null
        if (uid != null && storeId != null) {
            if (permissionRequestIsForPendingFollow) {
                permissionRequestIsForPendingFollow = false
                completePendingFollow(storeId, granted)
            } else {
                vm.toggleFollow(uid, storeId, granted)
            }
        }
    }
    LaunchedEffect(uid) { if (uid != null) vm.startFollowing(uid) }
    LaunchedEffect(uid, pendingFollowStoreId, pendingFollowRetry) {
        val storeId = pendingFollowStoreId
        if (uid != null && storeId != null && pendingFollowAttemptedId != storeId) {
            pendingFollowAttemptedId = storeId
            val needsPermission = Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) {
                permissionRequestIsForPendingFollow = true
                pendingStoreId = storeId
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                completePendingFollow(storeId, notificationsEnabled = true)
            }
        }
    }

    if (showSignInPrompt) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showSignInPrompt = false
                pendingStoreId = null
            },
            title = { Text("Inicia sesión para seguir tiendas") },
            text = { Text("Puedes explorar el catálogo sin cuenta. Para seguir una tienda necesitas iniciar sesión con Google.") },
            confirmButton = {
                TextButton(onClick = {
                    showSignInPrompt = false
                    pendingStoreId?.let(onRequestSignIn)
                }) { Text("Iniciar sesión") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSignInPrompt = false
                    pendingStoreId = null
                }) { Text("Ahora no") }
            },
        )
    }
    if (pendingFollowFailed && pendingFollowStoreId != null) {
        androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.errorContainer) {
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("No se pudo completar la acción. Revisa tu conexión y vuelve a intentar.", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    pendingFollowFailed = false
                    pendingFollowAttemptedId = null
                    pendingFollowRetry++
                }) { Text("Reintentar") }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Explorar BORA") },
                actions = {
                    TextButton(onClick = onDirectory) { Text("Directorio") }
                    TextButton(onClick = onFairs) { Text("Ferias") }
                    TextButton(onClick = onSignOut) { Text(if (uid == null) "Salir de invitado" else "Salir") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            pendingFollowNotice?.let { notice ->
                Text(
                    notice,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (!email.isNullOrBlank()) {
                Text(
                    "Sesión de comprador · $email",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when {
                state.loading && !useDemoCatalog -> CircularProgressIndicator(Modifier.padding(24.dp))
                state.error != null && !useDemoCatalog && visible.isEmpty() -> Text(
                    "No se pudo cargar el catálogo. Revisa tu conexión.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp),
                )
                visible.isEmpty() -> Text(
                    "Todavía no hay productos disponibles.",
                    modifier = Modifier.padding(24.dp),
                )
                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (useDemoCatalog) {
                        item(key = "demo-catalog-banner") {
                            Text(
                                "Modo de demostración: estos productos son ejemplos locales y no se publican ni guardan en Firebase.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (state.error != null) {
                        item(key = "catalog-offline-banner") {
                            Text(
                                "No hay conexión con el catálogo; se muestran los datos disponibles en el teléfono.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    items(visible, key = { it.id }) { product ->
                        BuyerProductCard(
                            product = product,
                            isFollowing = product.storeId in state.followedStoreIds,
                            showPrice = state.storeProfiles[product.storeId]?.showPrices ?: true,
                            onShare = {
                                val url = "https://bora-database.web.app/?product=" + android.net.Uri.encode(product.id)
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(android.content.Intent.EXTRA_TEXT, product.name + " · " + url)
                                }
                                context.startActivity(android.content.Intent.createChooser(send, "Compartir producto"))
                            },
                            onToggleFollow = {
                                if (uid == null) {
                                    showSignInPrompt = true
                                    pendingStoreId = product.storeId
                                } else if (product.storeId in state.followedStoreIds) {
                                    vm.toggleFollow(uid, product.storeId, notificationsEnabled = false)
                                } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    pendingStoreId = product.storeId
                                    permissionRequestIsForPendingFollow = false
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    vm.toggleFollow(uid, product.storeId, notificationsEnabled = true)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BuyerProductCard(
    product: Product,
    isFollowing: Boolean,
    showPrice: Boolean,
    onShare: () -> Unit,
    onToggleFollow: () -> Unit,
) {
    var selectedVariantId by remember(product.id) {
        mutableStateOf(product.variants.firstOrNull()?.id.orEmpty())
    }
    val selectedVariant = product.variants.firstOrNull { it.id == selectedVariantId }
    val image = selectedVariant?.imageUrl?.takeIf(String::isNotBlank) ?: product.imageUrl
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column {
            if (image.isNotBlank()) {
                AsyncImage(
                    model = image,
                    contentDescription = product.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                )
            } else {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Text("Producto de demostración", color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Column(Modifier.padding(16.dp)) {
                Text(product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (showPrice) formatClp(selectedVariant?.price ?: product.price) else "Consultar precio",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    when {
                        product.isSoldOut -> "Agotado"
                        product.isMadeToOrder -> "A pedido"
                        else -> "${product.category} · ${product.productType}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (product.variants.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Opciones", style = MaterialTheme.typography.labelLarge)
                    product.variants.forEach { variant ->
                        TextButton(onClick = { selectedVariantId = variant.id }) {
                            Text(
                                buildString {
                                    if (variant.id == selectedVariantId) append("✓ ")
                                    append(variant.name)
                                    if (showPrice) append(" · ${formatClp(variant.price)}")
                                    if (product.managesStock) append(" · Stock ${variant.stock}")
                                },
                            )
                        }
                    }
                }
                product.typeSpecificFields.forEach { (label, value) ->
                    if (value.isNotBlank()) {
                        Text("$label: $value", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (!product.managesStock && product.modality == "Pieza única") {
                    Text("Pieza única", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onShare) { Text("Compartir producto") }
                if (product.storeId.isNotBlank()) {
                    TextButton(onClick = onToggleFollow) {
                        Text(if (isFollowing) "Dejar de seguir tienda" else "Seguir tienda y recibir avisos")
                    }
                }
            }
        }
    }
}
