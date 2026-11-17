package com.esp.bora.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.esp.bora.data.Fair
import com.esp.bora.data.FairInvitation
import com.esp.bora.data.FairRepository
import com.esp.bora.data.DemoContent
import com.esp.bora.data.toUserMessage
import com.google.firebase.Timestamp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class FairsUiState(
    val loading: Boolean = true,
    val fairs: List<Fair> = emptyList(),
    val invitations: List<FairInvitation> = emptyList(),
    val message: String? = null,
    val error: String? = null,
)

class FairsViewModel(private val repo: FairRepository = FairRepository()) : ViewModel() {
    private var fairsJob: Job? = null
    private var invitationsJob: Job? = null
    private var currentUid: String? = null
    private val _state = MutableStateFlow(FairsUiState())
    val state: StateFlow<FairsUiState> = _state

    fun start(uid: String?) {
        if (currentUid == uid && fairsJob?.isActive == true) return
        currentUid = uid
        fairsJob?.cancel()
        invitationsJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        fairsJob = viewModelScope.launch {
            repo.observeUpcomingFairs()
                .catch { e -> _state.update { it.copy(loading = false, error = e.toUserMessage()) } }
                .collect { fairs -> _state.update { it.copy(loading = false, fairs = fairs) } }
        }
        if (uid != null) invitationsJob = viewModelScope.launch {
            repo.observeMyInvitations(uid)
                .catch { e -> _state.update { it.copy(error = e.toUserMessage()) } }
                .collect { invitations -> _state.update { it.copy(invitations = invitations) } }
        }
    }

    fun stop() {
        fairsJob?.cancel()
        invitationsJob?.cancel()
        fairsJob = null
        invitationsJob = null
    }

    fun respond(fairId: String, response: String) {
        viewModelScope.launch {
            try {
                repo.respondToInvitation(fairId, response)
                _state.update { it.copy(message = if (response == "confirmada") "Confirmaste tu participación." else "Rechazaste la invitación.") }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.toUserMessage()) }
            }
        }
    }

    fun reportError(message: String) = _state.update { it.copy(error = message) }

    fun clearFeedback() = _state.update { it.copy(message = null, error = null) }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun FairsScreen(
    uid: String?,
    onBack: () -> Unit,
    vm: FairsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val useDemoFairs = uid == null && state.fairs.isEmpty()
    val displayedFairs = if (useDemoFairs) remember { DemoContent.upcomingFairs() } else state.fairs
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var deviceLocation by remember { mutableStateOf<Location?>(null) }
    var detailFair by remember { mutableStateOf<Fair?>(null) }
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) {
            val manager = context.getSystemService(LocationManager::class.java)
            deviceLocation = runCatching {
                manager.getProviders(true)
                    .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                    .maxByOrNull { it.time }
            }.getOrNull()
        }
    }
    val orderedFairs = deviceLocation?.let { location ->
        displayedFairs.sortedBy { fair -> distanceMeters(location, fair) }
    } ?: displayedFairs
    DisposableEffect(uid) {
        vm.start(uid)
        onDispose { vm.stop() }
    }
    LaunchedEffect(state.message, state.error) {
        val feedback = state.message ?: state.error
        if (feedback != null) {
            snackbar.showSnackbar(feedback)
            vm.clearFeedback()
        }
    }

    detailFair?.let { fair ->
        FairPublicDetailsDialog(
            fair = fair,
            onDismiss = { detailFair = null },
            onDirections = {
                val query = Uri.encode("${fair.latitude},${fair.longitude} ${fair.address}")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + query))) }
                    .onFailure { vm.reportError("No se encontró una aplicación de mapas en este teléfono.") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ferias") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Volver") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading && !useDemoFairs -> CircularProgressIndicator(Modifier.padding(padding).padding(24.dp))
            state.error != null && !useDemoFairs && displayedFairs.isEmpty() -> Text("No se pudieron cargar las ferias. Revisa tu conexión.", Modifier.padding(padding).padding(24.dp), color = MaterialTheme.colorScheme.error)
            displayedFairs.isEmpty() -> Text("No hay próximas ferias publicadas.", Modifier.padding(padding).padding(24.dp))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (useDemoFairs) item(key = "demo-fairs-banner") {
                    Text("Modo de demostración: ferias y direcciones de ejemplo en Puerto Montt. No son ubicaciones verificadas ni se guardan en Firebase.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (state.error != null) item(key = "fairs-offline-banner") {
                    Text("No hay conexión con las ferias; revisa los datos cuando vuelva la conexión.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                item {
                    FairOverviewMap(fairs = orderedFairs, userLocation = deviceLocation)
                    TextButton(onClick = {
                        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        if (fine || coarse) {
                            val manager = context.getSystemService(LocationManager::class.java)
                            deviceLocation = runCatching {
                                manager.getProviders(true)
                                    .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
                                    .maxByOrNull { it.time }
                            }.getOrNull()
                        } else {
                            locationPermission.launch(arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ))
                        }
                    }) {
                        Text(if (deviceLocation == null) "Ordenar por cercanía (usa tu ubicación ahora)" else "Ferias ordenadas por cercanía")
                    }
                    if (deviceLocation == null) {
                        Text("Si rechazas el permiso o no hay ubicación reciente, las ferias se ordenan por fecha.", style = MaterialTheme.typography.bodySmall)
                    }
                }
                items(orderedFairs, key = { it.id }) { fair ->
                    val invitation = state.invitations.firstOrNull { it.fairId == fair.id }
                    FairCard(
                        fair = fair,
                        invitation = invitation,
                        onDetails = { detailFair = fair },
                        onRespond = { response -> vm.respond(fair.id, response) },
                        onDirections = {
                            val query = Uri.encode("${fair.latitude},${fair.longitude} ${fair.address}")
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$query"))
                            runCatching { context.startActivity(intent) }
                                .onFailure { vm.reportError("No se encontró una aplicación de mapas en este teléfono.") }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FairCard(
    fair: Fair,
    invitation: FairInvitation?,
    onDetails: () -> Unit,
    onRespond: (String) -> Unit,
    onDirections: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            if (fair.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = fair.imageUrl,
                    contentDescription = fair.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
            } else if (fair.id.startsWith("demo-")) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Text("Feria de muestra", color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fair.name, style = MaterialTheme.typography.titleLarge)
            if (fair.description.isNotBlank()) Text(fair.description)
            Text("Lugar: ${fair.address.ifBlank { "Ubicación por confirmar" }}")
            Text("Fecha: ${fair.startsAt?.let(::formatFairDate) ?: "Por confirmar"}")
            if (fair.organizer.isNotBlank()) Text("Organiza: ${fair.organizer}")
            if (invitation != null) {
                Text("Tu invitación: ${invitation.status.replaceFirstChar(Char::uppercase)}")
                if (invitation.status == "invitada") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onRespond("confirmada") }) { Text("Participo") }
                        TextButton(onClick = { onRespond("rechazada") }) { Text("No participo") }
                    }
                }
            }
            TextButton(onClick = onDetails) { Text("Ver ficha y mapa") }
            TextButton(onClick = onDirections) { Text("Cómo llegar") }
            }
        }
    }
}

@Composable
private fun FairOverviewMap(fairs: List<Fair>, userLocation: Location?) {
    if (fairs.isEmpty()) return
    if (fairs.all { it.id.startsWith("demo-") }) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Ubicaciones de referencia", style = MaterialTheme.typography.titleMedium)
                fairs.forEach { fair ->
                    Text("• ${fair.name}: ${fair.address}", style = MaterialTheme.typography.bodySmall)
                }
                Text("El mapa en línea se mostrará cuando haya conexión.", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    val points = org.json.JSONArray().apply {
        fairs.forEach { fair ->
            put(org.json.JSONObject().put("lat", fair.latitude).put("lon", fair.longitude).put("name", fair.name))
        }
    }.toString().replace("</", "<\\/")
    val user = userLocation?.let { "[${it.latitude},${it.longitude}]" } ?: "null"
    val center = userLocation?.let { "[${it.latitude},${it.longitude}]" }
        ?: "[${fairs.first().latitude},${fairs.first().longitude}]"
    val html = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <style>html,body,#map{height:100%;margin:0}</style></head><body><div id="map"></div>
        <script>
          var fairs = $points;
          var user = $user;
          var map = L.map('map').setView($center, 12);
          L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19, attribution: '&copy; OpenStreetMap'
          }).addTo(map);
          fairs.forEach(function(point) {
            var marker = L.marker([point.lat, point.lon]).addTo(map);
            var label = document.createElement('span'); label.textContent = point.name;
            marker.bindPopup(label);
          });
          if (user) L.circleMarker(user, {radius:8,color:'#176b50'}).addTo(map).bindPopup('Tu ubicación');
        </script></body></html>
    """.trimIndent()
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxWidth().height(240.dp),
        factory = { viewContext ->
            android.webkit.WebView(viewContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                loadDataWithBaseURL("https://www.openstreetmap.org", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView -> if (webView.tag != html.hashCode()) { webView.tag = html.hashCode(); webView.loadDataWithBaseURL("https://www.openstreetmap.org", html, "text/html", "UTF-8", null) } },
    )
}

private data class FairPublicVendor(val uid: String, val name: String, val storeId: String, val products: List<com.esp.bora.data.Product>)

@Composable
private fun FairPublicDetailsDialog(
    fair: Fair,
    onDismiss: () -> Unit,
    onDirections: () -> Unit,
) {
    val context = LocalContext.current
    val db = remember { com.google.firebase.firestore.FirebaseFirestore.getInstance() }
    val scope = rememberCoroutineScope()
    var vendors by remember(fair.id) { mutableStateOf<List<FairPublicVendor>>(emptyList()) }
    var loading by remember(fair.id) { mutableStateOf(true) }
    var qrVisible by remember { mutableStateOf(false) }

    LaunchedEffect(fair.id) {
        if (fair.id.startsWith("demo-")) {
            loading = false
            return@LaunchedEffect
        }
        runCatching {
            val participantDocs = db.collection("fairs").document(fair.id).collection("participants")
                .whereEqualTo("status", "confirmada").get().await().documents
            val allProducts = db.collection("products").get().await().documents
                .mapNotNull { document -> document.toObject(com.esp.bora.data.Product::class.java)?.copy(id = document.id) }
                .filter { it.visibleInPublicCatalog }
            participantDocs.map { participant ->
                val uid = participant.id
                val stores = db.collection("stores").whereEqualTo("ownerUid", uid).limit(1).get().await()
                val storeId = stores.documents.firstOrNull()?.id ?: uid
                FairPublicVendor(
                    uid = uid,
                    name = participant.getString("entrepreneurName").orEmpty(),
                    storeId = storeId,
                    products = allProducts.filter { it.storeId == storeId }.take(4),
                )
            }
        }.onSuccess { vendors = it }
        loading = false
    }

    if (qrVisible) {
        val url = "https://bora-database.web.app/?fair=" + Uri.encode(fair.id)
        QrDialog(
            title = fair.name,
            url = url,
            onDismiss = { qrVisible = false },
            onSave = { bitmap ->
                runCatching {
                    val folder = java.io.File(context.filesDir, "qr").apply { mkdirs() }
                    java.io.FileOutputStream(java.io.File(folder, "feria_" + fair.id + ".png")).use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            },
            onShare = { bitmap ->
                runCatching {
                    val folder = java.io.File(context.filesDir, "qr").apply { mkdirs() }
                    val file = java.io.File(folder, "feria_" + fair.id + ".png")
                    java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, fair.name + " · " + url)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Compartir código QR"))
                }
            },
        )
    }

    if (!qrVisible) AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(fair.name) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (fair.description.isNotBlank()) Text(fair.description)
                Text(fair.address)
                Text("Fecha: " + (fair.startsAt?.let(::formatFairDate) ?: "Por confirmar"))
                Text("Organiza: " + fair.organizer)
                FairMapPreview(fair)
                TextButton(onClick = onDirections) { Text("Cómo llegar") }
                Text("Participantes confirmados", style = MaterialTheme.typography.titleMedium)
                if (fair.id.startsWith("demo-")) Text("La ficha de muestra no incluye participantes reales.")
                else if (loading) CircularProgressIndicator()
                else if (vendors.isEmpty()) Text("Aún no hay participantes confirmados.")
                vendors.forEach { vendor ->
                    Text(vendor.name, style = MaterialTheme.typography.titleSmall)
                    vendor.products.forEach { product ->
                        Text("• " + product.name + if (product.isSoldOut) " · Agotado" else "")
                    }
                    if (vendor.products.isEmpty()) Text("Todavía no hay productos públicos.")
                }
            }
        },
        confirmButton = {
            if (fair.id.startsWith("demo-")) {
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            } else Row {
                TextButton(onClick = {
                    val url = "https://bora-database.web.app/?fair=" + Uri.encode(fair.id)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, fair.name + " · " + url)
                    }
                    context.startActivity(Intent.createChooser(send, "Compartir feria"))
                }) { Text("Compartir") }
                TextButton(onClick = { qrVisible = true }) { Text("Código QR") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun FairMapPreview(fair: Fair) {
    if (fair.id.startsWith("demo-")) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Mapa de referencia sin conexión", style = MaterialTheme.typography.titleSmall)
                Text("Puerto Montt · ${fair.latitude}, ${fair.longitude}", style = MaterialTheme.typography.bodySmall)
                Text("Las coordenadas son demostrativas.", style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }
    val context = LocalContext.current
    val lat = fair.latitude
    val lon = fair.longitude
    val margin = 0.01
    val mapUrl = "https://www.openstreetmap.org/export/embed.html?bbox=" +
        "${lon - margin}%2C${lat - margin}%2C${lon + margin}%2C${lat + margin}" +
        "&layer=mapnik&marker=$lat%2C$lon"
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        factory = { viewContext ->
            android.webkit.WebView(viewContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                loadUrl(mapUrl)
            }
        },
        update = { view -> if (view.url != mapUrl) view.loadUrl(mapUrl) },
    )
}

private fun distanceMeters(from: Location, fair: Fair): Float {
    val result = FloatArray(1)
    Location.distanceBetween(from.latitude, from.longitude, fair.latitude, fair.longitude, result)
    return result[0]
}

private fun formatFairDate(timestamp: Timestamp): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(timestamp.seconds * 1000L))
