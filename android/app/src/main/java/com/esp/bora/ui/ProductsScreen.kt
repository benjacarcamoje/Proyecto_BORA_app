package com.esp.bora.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.esp.bora.data.Product
import com.esp.bora.data.ProductOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(
    uid: String,
    message: String?,
    onMessageShown: () -> Unit,
    onNewProduct: () -> Unit,
    onEditProduct: (Product) -> Unit,
    onRecordSale: (Product) -> Unit,
    onSales: () -> Unit,
    onFairs: () -> Unit,
    onSignOut: () -> Unit,
    onStoreSettings: () -> Unit = {},
    vm: ProductsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var productToDelete by remember { mutableStateOf<Product?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var showBulkDialog by remember { mutableStateOf(false) }
    var selectedProductIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var bulkTarget by remember { mutableStateOf("selected") }
    var bulkCategory by remember { mutableStateOf(ProductOptions.categories.first()) }
    var bulkAvailable by remember { mutableStateOf(true) }
    var showNotificationPrompt by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(uid) {
        vm.start(uid)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            showNotificationPrompt = true
        }
    }
    LaunchedEffect(message, state.message, state.error) {
        val feedback = message ?: state.message ?: state.error
        if (feedback != null) {
            snackbarHost.showSnackbar(feedback)
            if (message != null) onMessageShown() else vm.clearFeedback()
        }
    }

    if (showNotificationPrompt) {
        AlertDialog(
            onDismissRequest = { showNotificationPrompt = false },
            title = { Text("Recibe avisos de ferias") },
            text = { Text("Permite las notificaciones para enterarte cuando te inviten a una feria o te envíen un recordatorio.") },
            confirmButton = {
                TextButton(onClick = {
                    showNotificationPrompt = false
                    if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Activar avisos") }
            },
            dismissButton = { TextButton(onClick = { showNotificationPrompt = false }) { Text("Ahora no") } },
        )
    }

    if (showBulkDialog) {
        BulkAvailabilityDialog(
            target = bulkTarget,
            category = bulkCategory,
            available = bulkAvailable,
            selectedCount = selectedProductIds.size,
            busy = state.updatingAvailability,
            onTargetChange = { bulkTarget = it },
            onCategoryChange = { bulkCategory = it },
            onAvailableChange = { bulkAvailable = it },
            onDismiss = { if (!state.updatingAvailability) showBulkDialog = false },
            onConfirm = {
                val targets = when (bulkTarget) {
                    "category" -> state.products.filter { it.category == bulkCategory && !it.sold }
                    "all" -> state.products.filterNot { it.sold }
                    else -> state.products.filter { it.id in selectedProductIds && !it.sold }
                }
                vm.setAvailability(targets, bulkAvailable)
                showBulkDialog = false
                selectionMode = false
                selectedProductIds = emptySet()
            },
        )
    }

    productToDelete?.let { product ->
        AlertDialog(
            onDismissRequest = { productToDelete = null },
            title = { Text("¿Eliminar producto?") },
            text = { Text("Se quitará «${product.name}» de tu catálogo.") },
            confirmButton = {
                TextButton(onClick = {
                    productToDelete = null
                    vm.deleteProduct(product)
                }) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { productToDelete = null }) { Text("Cancelar") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mis productos") },
                actions = {
                    if (selectionMode) {
                        TextButton(onClick = { showBulkDialog = true }) { Text("Disponibilidad") }
                        TextButton(onClick = {
                            selectionMode = false
                            selectedProductIds = emptySet()
                        }) { Text("Cancelar") }
                    } else {
                        TextButton(onClick = { selectionMode = true }) { Text("Seleccionar") }
                    }
                    TextButton(onClick = onSales) { Text("Ventas") }
                    TextButton(onClick = onFairs) { Text("Ferias") }
                    TextButton(onClick = onStoreSettings) { Text("Mi tienda") }
                    TextButton(onClick = onSignOut) { Text("Salir") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewProduct,
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Nuevo producto")
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null && state.products.isEmpty() -> CenteredMessage(state.error!!, isError = true)
                state.products.isEmpty() -> CenteredMessage(
                    "Aún no tienes productos.\nToca «Nuevo producto» para comenzar.",
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.products, key = { it.id }) { product ->
                        ProductCard(
                            product = product,
                            onEdit = { onEditProduct(product) },
                            onDelete = { productToDelete = product },
                            onRecordSale = { onRecordSale(product) },
                            selectionMode = selectionMode,
                            selected = product.id in selectedProductIds,
                            onToggleSelected = {
                                selectedProductIds = if (product.id in selectedProductIds) {
                                    selectedProductIds - product.id
                                } else {
                                    selectedProductIds + product.id
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
private fun BulkAvailabilityDialog(
    target: String,
    category: String,
    available: Boolean,
    selectedCount: Int,
    busy: Boolean,
    onTargetChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onAvailableChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cambiar disponibilidad") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Aplicar a")
                BulkRadioChoice("Productos seleccionados ($selectedCount)", target == "selected") {
                    onTargetChange("selected")
                }
                BulkRadioChoice("Una categoría", target == "category") {
                    onTargetChange("category")
                }
                if (target == "category") {
                    Box {
                        OutlinedButton(
                            onClick = { categoryMenuExpanded = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(category) }
                        DropdownMenu(
                            expanded = categoryMenuExpanded,
                            onDismissRequest = { categoryMenuExpanded = false },
                        ) {
                            ProductOptions.categories.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        onCategoryChange(option)
                                        categoryMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
                BulkRadioChoice("Todo mi catálogo", target == "all") {
                    onTargetChange("all")
                }
                Spacer(Modifier.height(8.dp))
                Text("Nuevo estado")
                BulkRadioChoice("Disponible", available) { onAvailableChange(true) }
                BulkRadioChoice("No disponible", !available) { onAvailableChange(false) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !busy && (target != "selected" || selectedCount > 0),
            ) { Text(if (busy) "Aplicando…" else "Aplicar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancelar") }
        },
    )
}

@Composable
private fun BulkRadioChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 4.dp))
    }
}


private val onRecordSaleBlank: () -> Unit = {}

@Composable
private fun CenteredMessage(text: String, isError: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProductCard(
    product: Product,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRecordSale: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelected: () -> Unit,
) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column {
            AsyncImage(
                model = product.imageUrl,
                contentDescription = product.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
            Column(Modifier.padding(16.dp)) {
                if (selectionMode) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { onToggleSelected() },
                            enabled = !product.sold,
                        )
                        Text(if (product.sold) "Producto vendido" else "Incluir en selección")
                    }
                }
                Text(product.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    formatClp(product.price),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${product.category} · ${product.productType} · ${product.modality}" +
                        if (product.managesStock) " · Stock: ${product.totalStock}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!product.available || product.sold || product.isSoldOut) {
                    Text(
                        if (product.sold) "Vendida" else if (!product.available) "No disponible" else "Agotado",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                product.typeSpecificFields.forEach { (label, value) ->
                    if (value.isNotBlank()) {
                        Text("$label: $value", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (product.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        product.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!selectionMode) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onEdit) { Text("Editar") }
                        TextButton(onClick = onRecordSale) { Text("Registrar venta") }
                        TextButton(onClick = onDelete) { Text("Eliminar") }
                    }
                }
            }
        }
    }
}

