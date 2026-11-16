package com.esp.bora.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.esp.bora.data.*
import com.google.firebase.functions.FirebaseFunctions
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream

private const val PUBLIC_WEB_URL = "https://bora-database.web.app"

@Composable
fun PublicDirectoryScreen(
    uid: String?,
    onBack: () -> Unit,
    onRequestSignIn: (String) -> Unit,
    pendingAssociationId: String? = null,
    onPendingFollowHandled: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val directory = remember { PublicDirectoryRepository() }
    val storesRepo = remember { StoreRepository() }
    val productsRepo = remember { ProductRepository() }
    val follows = remember { FollowRepository() }
    val snackbar = remember { SnackbarHostState() }
    val stores by storesRepo.observePublicProfiles().catch { snackbar.showSnackbar("No se pudieron cargar las tiendas.") }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val associations by directory.observeAssociations().catch { snackbar.showSnackbar("No se pudieron cargar las asociaciones.") }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val products by productsRepo.observePublicProducts().catch { snackbar.showSnackbar("No se pudieron cargar los productos.") }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var followedAssociations by remember(uid) { mutableStateOf<Set<String>>(emptySet()) }
    var alertedAssociations by remember(uid) { mutableStateOf<Set<String>>(emptySet()) }
    var associationNotificationOverrides by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var search by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("") }
    var selectedSector by remember { mutableStateOf("") }
    var selectedAssociationId by remember { mutableStateOf("") }
    var associationByUid by remember { mutableStateOf<Map<String, Set<String>>>(emptyMap()) }
    var contactOptionsStore by remember { mutableStateOf<StoreProfile?>(null) }
    var selectedStore by remember { mutableStateOf<StoreProfile?>(null) }
    var selectedAssociation by remember { mutableStateOf<PublicAssociation?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        selectedAssociation?.id?.let { associationId ->
            associationNotificationOverrides = associationNotificationOverrides + (associationId to granted)
        }
    }
    var associationMembers by remember { mutableStateOf<List<AssociationMember>>(emptyList()) }
    var contactStore by remember { mutableStateOf<StoreProfile?>(null) }
    var qr by remember { mutableStateOf<Pair<String, String>?>(null) }
    var senderName by remember { mutableStateOf("") }
    var senderEmail by remember { mutableStateOf("") }
    var senderPhone by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(uid) {
        if (uid == null) {
            followedAssociations = emptySet()
            alertedAssociations = emptySet()
        } else {
            follows.observeFollowedAssociations(uid).collect { followedAssociations = it }
        }
    }
    LaunchedEffect(uid) {
        if (uid != null) follows.observeAssociationNotifications(uid).collect { alertedAssociations = it }
    }
    LaunchedEffect(uid, pendingAssociationId) {
        val associationId = pendingAssociationId
        if (uid != null && !associationId.isNullOrBlank()) {
            runCatching {
                follows.setAssociationFollow(uid, associationId, following = true, notificationsEnabled = false)
            }
            onPendingFollowHandled("association:" + associationId)
        }
    }

    LaunchedEffect(associations) {
        val membership = mutableMapOf<String, MutableSet<String>>()
        associations.forEach { association ->
            val members = runCatching { directory.getMembers(association.id) }.getOrDefault(emptyList())
            members.forEach { member -> membership.getOrPut(member.uid) { mutableSetOf() }.add(association.id) }
        }
        associationByUid = membership.mapValues { it.value.toSet() }
    }
    val types = stores.map { it.businessType }.filter(String::isNotBlank).distinct().sorted()
    val sectors = (stores.map { it.sector } + associations.map { it.sector }).filter(String::isNotBlank).distinct().sorted()
    val visibleStores = stores.filter {
        (search.isBlank() || it.name.contains(search, true) || it.description.contains(search, true) ||
            it.businessType.contains(search, true) || it.sector.contains(search, true)) &&
            (selectedType.isBlank() || it.businessType == selectedType) &&
            (selectedSector.isBlank() || it.sector == selectedSector) &&
            (selectedAssociationId.isBlank() || selectedAssociationId in associationByUid[it.ownerUid].orEmpty())
    }
    val visibleAssociations = associations.filter {
        (search.isBlank() || it.name.contains(search, true) || it.description.contains(search, true) ||
            it.sector.contains(search, true)) &&
            (selectedSector.isBlank() || it.sector == selectedSector) &&
            (selectedAssociationId.isBlank() || it.id == selectedAssociationId)
    }

    fun share(title: String, url: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, "$title · $url")
        }
        context.startActivity(Intent.createChooser(send, "Compartir en BORA"))
    }

    selectedAssociation?.let { association ->
        AlertDialog(
            onDismissRequest = { selectedAssociation = null },
            title = { Text(association.name) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    if (association.logoUrl.isNotBlank()) AsyncImage(association.logoUrl, association.name, modifier = Modifier.fillMaxWidth().height(150.dp))
                    Text(association.description)
                    Text("Sector: " + association.sector)
                    if (association.contact.isNotBlank()) Text("Contacto: " + association.contact)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            checked = associationNotificationOverrides[association.id] ?: (association.id in alertedAssociations),
                            onCheckedChange = { enabled ->
                                if (!enabled) associationNotificationOverrides = associationNotificationOverrides + (association.id to false)
                                else if (Build.VERSION.SDK_INT < 33 ||
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                ) associationNotificationOverrides = associationNotificationOverrides + (association.id to true)
                                else notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            },
                        )
                        Text("Avisarme cuando participe en una feria")
                    }
                    Text("Integrantes", style = MaterialTheme.typography.titleMedium)
                    associationMembers.forEach { member ->
                        val memberStore = stores.firstOrNull { it.ownerUid == member.uid }
                        if (memberStore != null) {
                            TextButton(onClick = {
                                selectedAssociation = null
                                selectedStore = memberStore
                            }) { Text(member.name + " · " + member.businessType) }
                        } else {
                            Text(member.name + " · " + member.businessType)
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        if (uid == null) onRequestSignIn("association:" + association.id)
                        else scope.launch {
                            runCatching {
                                if (association.id in followedAssociations) {
                                    follows.setAssociationNotifications(
                                        uid, association.id,
                                        associationNotificationOverrides[association.id] ?: (association.id in alertedAssociations),
                                    )
                                } else {
                                    follows.setAssociationFollow(
                                        uid, association.id, true,
                                        associationNotificationOverrides[association.id] ?: (association.id in alertedAssociations),
                                    )
                                }
                            }.onFailure { snackbar.showSnackbar(it.message ?: "No se pudo actualizar el seguimiento.") }
                        }
                    }) { Text(if (association.id in followedAssociations) "Guardar avisos" else "Seguir asociación") }
                    if (uid != null && association.id in followedAssociations) {
                        TextButton(onClick = {
                            scope.launch {
                                runCatching { follows.setAssociationFollow(uid, association.id, false, false) }
                                    .onFailure { snackbar.showSnackbar(it.message ?: "No se pudo dejar de seguir.") }
                            }
                        }) { Text("Dejar de seguir") }
                    }
                    TextButton(onClick = { share(association.name, publicUrl("association", association.id)) }) { Text("Compartir") }
                }
            },
            dismissButton = { TextButton(onClick = { qr = association.name to publicUrl("association", association.id) }) { Text("Código QR") } },
        )
    }

    selectedStore?.let { store ->
        val storeProducts = products.filter { it.storeId == store.id && it.visibleInPublicCatalog }
        AlertDialog(
            onDismissRequest = { selectedStore = null },
            title = { Text(store.name) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    if (store.logoUrl.isNotBlank()) AsyncImage(store.logoUrl, store.name, modifier = Modifier.fillMaxWidth().height(150.dp))
                    Text(store.description)
                    Text(store.businessType + " · " + store.sector)
                    if (store.pickupAddress.isNotBlank()) {
                        Text("Punto de venta o retiro: " + store.pickupAddress)
                        TextButton(onClick = {
                            val query = Uri.encode(store.pickupLatitude.toString() + "," + store.pickupLongitude + " " + store.pickupAddress)
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + query))) }
                                .onFailure { scope.launch { snackbar.showSnackbar("No se encontró una aplicación de mapas.") } }
                        }) { Text("Cómo llegar") }
                    }
                    storeProducts.forEach { product ->
                        Text(product.name, style = MaterialTheme.typography.titleSmall)
                        Text(if (store.showPrices) formatClp(product.price) else "Consultar precio")
                        if (product.description.isNotBlank()) Text(product.description)
                        TextButton(onClick = { share(product.name, publicUrl("product", product.id)) }) { Text("Compartir producto") }
                    }
                    if (storeProducts.isEmpty()) Text("No hay productos publicados.")
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { contactOptionsStore = store }) { Text("Contactar") }
                    TextButton(onClick = { share(store.name, publicUrl("store", store.id)) }) { Text("Compartir") }
                }
            },
            dismissButton = { TextButton(onClick = { qr = store.name to publicUrl("store", store.id) }) { Text("Código QR") } },
        )
    }

    contactOptionsStore?.let { store ->
        AlertDialog(
            onDismissRequest = { contactOptionsStore = null },
            title = { Text("Elige cómo contactar") },
            text = { Text("La emprendedora configuró: " + if (store.contactMode == "both") "WhatsApp y formulario" else store.contactMode) },
            confirmButton = {
                Row {
                    if ((store.contactMode == "whatsapp" || store.contactMode == "both") && store.whatsappPublic.isNotBlank()) {
                        TextButton(onClick = {
                            contactOptionsStore = null
                            val phone = store.whatsappPublic.filter(Char::isDigit)
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + phone))
                            runCatching { context.startActivity(intent) }
                                .onFailure { scope.launch { snackbar.showSnackbar("No se pudo abrir WhatsApp.") } }
                        }) { Text("WhatsApp") }
                    }
                    if (store.contactMode == "form" || store.contactMode == "both") {
                        TextButton(onClick = {
                            contactOptionsStore = null
                            contactStore = store
                        }) { Text("Formulario") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { contactOptionsStore = null }) { Text("Cancelar") } },
        )
    }

    contactStore?.let { store ->
        AlertDialog(
            onDismissRequest = { if (!sending) contactStore = null },
            title = { Text("Contactar a " + store.name) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(senderName, { senderName = it.take(100) }, label = { Text("Tu nombre") })
                    OutlinedTextField(senderEmail, { senderEmail = it.take(254) }, label = { Text("Tu correo (opcional)") })
                    OutlinedTextField(senderPhone, { senderPhone = it.take(32) }, label = { Text("Tu teléfono (opcional)") })
                    OutlinedTextField(message, { message = it.take(2000) }, label = { Text("Mensaje") })
                }
            },
            confirmButton = {
                TextButton(enabled = !sending && senderName.isNotBlank() && message.isNotBlank(), onClick = {
                    scope.launch {
                        sending = true
                        runCatching {
                            FirebaseFunctions.getInstance().getHttpsCallable("submitStoreContact").call(
                                mapOf("storeId" to store.id, "senderName" to senderName.trim(),
                                    "senderEmail" to senderEmail.trim(), "senderPhone" to senderPhone.trim(),
                                    "message" to message.trim()),
                            ).await()
                        }.onSuccess {
                            contactStore = null
                            senderName = ""; senderEmail = ""; senderPhone = ""; message = ""
                            snackbar.showSnackbar("Mensaje enviado.")
                        }.onFailure { snackbar.showSnackbar(it.message ?: "No se pudo enviar el mensaje.") }
                        sending = false
                    }
                }) { Text(if (sending) "Enviando…" else "Enviar") }
            },
            dismissButton = { TextButton(onClick = { contactStore = null }) { Text("Cancelar") } },
        )
    }

    qr?.let { (title, url) ->
        QrDialog(
            title, url,
            onDismiss = { qr = null },
            onSave = { bitmap ->
                runCatching {
                    val folder = File(context.filesDir, "qr").apply { mkdirs() }
                    FileOutputStream(File(folder, safeName(title) + ".png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    scope.launch { snackbar.showSnackbar("QR guardado en los archivos de BORA.") }
                }
            },
            onShare = { bitmap ->
                runCatching {
                    val folder = File(context.filesDir, "qr").apply { mkdirs() }
                    val file = File(folder, safeName(title) + ".png")
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, title + " · " + url)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Compartir código QR"))
                }.onFailure { scope.launch { snackbar.showSnackbar(it.message ?: "No se pudo compartir el QR.") } }
            },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Directorio BORA") }, navigationIcon = { TextButton(onClick = onBack) { Text("Volver") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(search, { search = it }, label = { Text("Buscar tiendas y asociaciones") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selectedType.isBlank(), { selectedType = "" }, label = { Text("Todos los tipos") })
                types.forEach { type -> FilterChip(selectedType == type, { selectedType = type }, label = { Text(type) }) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selectedSector.isBlank(), { selectedSector = "" }, label = { Text("Todos los sectores") })
                sectors.forEach { sector -> FilterChip(selectedSector == sector, { selectedSector = sector }, label = { Text(sector) }) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selectedAssociationId.isBlank(), { selectedAssociationId = "" }, label = { Text("Todas las asociaciones") })
                associations.forEach { association ->
                    FilterChip(selectedAssociationId == association.id, { selectedAssociationId = association.id }, label = { Text(association.name) })
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Tiendas", style = MaterialTheme.typography.titleLarge) }
                items(visibleStores, key = { "store:" + it.id }) { store ->
                    Card(onClick = { selectedStore = store }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(store.name, style = MaterialTheme.typography.titleMedium)
                            Text(store.businessType + " · " + store.sector)
                            Text(store.description, maxLines = 2)
                        }
                    }
                }
                item { Text("Asociaciones", style = MaterialTheme.typography.titleLarge) }
                items(visibleAssociations, key = { "association:" + it.id }) { association ->
                    Card(onClick = {
                        selectedAssociation = association
                        scope.launch { associationMembers = runCatching { directory.getMembers(association.id) }.getOrDefault(emptyList()) }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(association.name, style = MaterialTheme.typography.titleMedium)
                            Text(association.sector)
                            Text(association.description, maxLines = 2)
                        }
                    }
                }
                if (visibleStores.isEmpty() && visibleAssociations.isEmpty()) item { Text("No hay resultados.") }
            }
        }
    }
}

@Composable
internal fun QrDialog(
    title: String,
    url: String,
    onDismiss: () -> Unit,
    onSave: (Bitmap) -> Unit,
    onShare: (Bitmap) -> Unit,
) {
    val bitmap = remember(url) {
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 640, 640)
        Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 640) for (y in 0 until 640) {
                setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Código QR · " + title) },
        text = { Image(bitmap.asImageBitmap(), "Código QR de " + title, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Row { TextButton(onClick = { onSave(bitmap) }) { Text("Guardar") }; TextButton(onClick = { onShare(bitmap) }) { Text("Compartir imagen") } } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

private fun publicUrl(kind: String, id: String): String =
    PUBLIC_WEB_URL + "/?" + kind + "=" + Uri.encode(id)

private fun safeName(value: String): String =
    value.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(50).ifBlank { "bora_qr" }
