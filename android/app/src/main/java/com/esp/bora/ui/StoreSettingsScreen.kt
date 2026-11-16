package com.esp.bora.ui

import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.esp.bora.data.StoreContact
import com.esp.bora.data.StorePrivateSettings
import com.esp.bora.data.StoreProfile
import com.esp.bora.data.ProductOptions
import com.esp.bora.ui.theme.BoraTheme
import java.text.DateFormat
import java.util.Date
import androidx.compose.ui.tooling.preview.Preview

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreSettingsScreen(
    uid: String,
    onBack: () -> Unit,
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    onBiometricEnabledChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    vm: StoreSettingsViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            vm.selectLogo(uri)
        }
    }

    LaunchedEffect(uid) { vm.start(uid) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Mi tienda y configuración") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Volver") } },
            )
        },
    ) { padding ->
        StoreSettingsContent(
            state = state,
            biometricAvailable = biometricAvailable,
            biometricEnabled = biometricEnabled,
            onBiometricEnabledChange = onBiometricEnabledChange,
            onChooseLogo = { logoPicker.launch(arrayOf("image/*")) },
            onNameChange = { value -> vm.updateProfile { it.copy(name = value.take(100)) } },
            onDescriptionChange = { value -> vm.updateProfile { it.copy(description = value.take(1000)) } },
            onBusinessTypeChange = { value -> vm.updateProfile { it.copy(businessType = value.take(100)) } },
            onSectorChange = { value -> vm.updateProfile { it.copy(sector = value.take(100)) } },
            onPickupAddressChange = { value -> vm.updateProfile { it.copy(pickupAddress = value) } },
            onPickupLatitudeChange = { value -> vm.updateProfile { it.copy(pickupLatitude = value) } },
            onPickupLongitudeChange = { value -> vm.updateProfile { it.copy(pickupLongitude = value) } },
            onContactModeChange = { value -> vm.updateProfile { it.copy(contactMode = value) } },
            onShowPricesChange = { value -> vm.updateProfile { it.copy(showPrices = value) } },
            onPhoneChange = { value -> vm.updateSettings { it.copy(contactPhone = value.take(32)) } },
            onEmailChange = { value -> vm.updateSettings { it.copy(contactEmail = value.take(254)) } },
            onWhatsappChange = { value -> vm.updateSettings { it.copy(whatsapp = value.take(32)) } },
            onContactNotificationsChange = { value ->
                vm.updateSettings { it.copy(contactNotificationsEnabled = value) }
            },
            onStartTimeChange = vm::updateStartTime,
            onEndTimeChange = vm::updateEndTime,
            onRatingsChange = { value -> vm.updateSettings { it.copy(ratingsEnabled = value) } },
            onSalesHistoryChange = { value -> vm.updateSettings { it.copy(salesHistoryEnabled = value) } },
            onProductDefaultsChange = vm::updateSettings,
            onSave = { vm.save(uid) },
            onClearFeedback = vm::clearFeedback,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
private fun StoreSettingsContent(
    state: StoreSettingsUiState,
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    onBiometricEnabledChange: (Boolean) -> Unit,
    onChooseLogo: () -> Unit,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onBusinessTypeChange: (String) -> Unit,
    onSectorChange: (String) -> Unit,
    onPickupAddressChange: (String) -> Unit,
    onPickupLatitudeChange: (Double) -> Unit,
    onPickupLongitudeChange: (Double) -> Unit,
    onContactModeChange: (String) -> Unit,
    onShowPricesChange: (Boolean) -> Unit,
    onPhoneChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onWhatsappChange: (String) -> Unit,
    onContactNotificationsChange: (Boolean) -> Unit,
    onStartTimeChange: (String) -> Unit,
    onEndTimeChange: (String) -> Unit,
    onRatingsChange: (Boolean) -> Unit,
    onSalesHistoryChange: (Boolean) -> Unit,
    onProductDefaultsChange: ((StorePrivateSettings) -> StorePrivateSettings) -> Unit,
    onSave: () -> Unit,
    onClearFeedback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pickupLatitudeText by rememberSaveable(state.storeId) {
        mutableStateOf(state.profile.pickupLatitude.takeIf { it != 0.0 }?.toString().orEmpty())
    }
    var pickupLongitudeText by rememberSaveable(state.storeId) {
        mutableStateOf(state.profile.pickupLongitude.takeIf { it != 0.0 }?.toString().orEmpty())
    }
    if (state.loading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ficha de la tienda", style = MaterialTheme.typography.titleLarge)
        val logoModel = state.selectedLogoUri ?: state.profile.logoUrl.takeIf(String::isNotBlank)
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (logoModel != null) {
                    AsyncImage(
                        model = logoModel,
                        contentDescription = "Logo de ${state.profile.name}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    )
                } else {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Agrega una foto o logo")
                    }
                }
                OutlinedButton(
                    onClick = onChooseLogo,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Elegir foto o logo") }
            }
        }
        OutlinedTextField(
            value = state.profile.name,
            onValueChange = onNameChange,
            label = { Text("Nombre de la tienda") },
            singleLine = true,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.profile.description,
            onValueChange = onDescriptionChange,
            label = { Text("Descripción") },
            minLines = 2,
            maxLines = 5,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.profile.businessType,
            onValueChange = onBusinessTypeChange,
            label = { Text("Tipo de emprendimiento") },
            singleLine = true,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.profile.sector,
            onValueChange = onSectorChange,
            label = { Text("Sector de Puerto Montt") },
            singleLine = true,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Punto de venta o retiro (opcional)", style = MaterialTheme.typography.titleMedium)
        Text("Si quieres que aparezca en tu tienda, ingresa la dirección y las coordenadas del punto.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = state.profile.pickupAddress,
            onValueChange = { value -> onPickupAddressChange(value.take(200)) },
            label = { Text("Dirección del punto") },
            singleLine = true,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pickupLatitudeText,
                onValueChange = { value ->
                    pickupLatitudeText = value.filter { it.isDigit() || it == '.' || it == '-' }.take(16)
                    pickupLatitudeText.toDoubleOrNull()?.let { onPickupLatitudeChange(it.coerceIn(-90.0, 90.0)) }
                },
                label = { Text("Latitud") },
                enabled = !state.saving,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = pickupLongitudeText,
                onValueChange = { value ->
                    pickupLongitudeText = value.filter { it.isDigit() || it == '.' || it == '-' }.take(16)
                    pickupLongitudeText.toDoubleOrNull()?.let { onPickupLongitudeChange(it.coerceIn(-180.0, 180.0)) }
                },
                label = { Text("Longitud") },
                enabled = !state.saving,
                modifier = Modifier.weight(1f),
            )
        }

        StoreLocationPickerMap(
            latitude = state.profile.pickupLatitude,
            longitude = state.profile.pickupLongitude,
            onLocationSelected = { latitude, longitude ->
                onPickupLatitudeChange(latitude)
                onPickupLongitudeChange(longitude)
            },
        )

        Text("Forma de contacto", style = MaterialTheme.typography.titleMedium)
        Text("Elige cómo pueden contactarte las personas.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.profile.contactMode == "whatsapp",
                onClick = { onContactModeChange("whatsapp") },
                label = { Text("WhatsApp") },
                enabled = !state.saving,
            )
            FilterChip(
                selected = state.profile.contactMode == "form",
                onClick = { onContactModeChange("form") },
                label = { Text("Formulario") },
                enabled = !state.saving,
            )
            FilterChip(
                selected = state.profile.contactMode == "both",
                onClick = { onContactModeChange("both") },
                label = { Text("Ambos") },
                enabled = !state.saving,
            )
        }
        OutlinedTextField(
            value = state.settings.whatsapp,
            onValueChange = onWhatsappChange,
            label = { Text("WhatsApp público") },
            supportingText = { Text("Solo se publica si eliges WhatsApp o Ambos.") },
            singleLine = true,
            enabled = !state.saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.settings.contactPhone,
            onValueChange = onPhoneChange,
            label = { Text("Teléfono de contacto privado") },
            singleLine = true,
            enabled = !state.saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.settings.contactEmail,
            onValueChange = onEmailChange,
            label = { Text("Correo para contacto") },
            singleLine = true,
            enabled = !state.saving,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )

        SettingSwitch(
            title = "Mostrar precios",
            description = "Si lo desactivas, el catálogo mostrará «Consultar precio».",
            checked = state.profile.showPrices,
            enabled = !state.saving,
            onCheckedChange = onShowPricesChange,
        )
        SettingSwitch(
            title = "Avisos de nuevos contactos",
            description = "Configura si quieres recibir avisos y en qué horario.",
            checked = state.settings.contactNotificationsEnabled,
            enabled = !state.saving,
            onCheckedChange = onContactNotificationsChange,
        )
        if (state.settings.contactNotificationsEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.startTimeText,
                    onValueChange = onStartTimeChange,
                    label = { Text("Desde (HH:MM)") },
                    singleLine = true,
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = state.endTimeText,
                    onValueChange = onEndTimeChange,
                    label = { Text("Hasta (HH:MM)") },
                    singleLine = true,
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        SettingSwitch(
            title = "Permitir calificaciones",
            description = "Activa o desactiva las calificaciones de tu tienda.",
            checked = state.settings.ratingsEnabled,
            enabled = !state.saving,
            onCheckedChange = onRatingsChange,
        )
        SettingSwitch(
            title = "Guardar historial de ventas",
            description = "Si lo desactivas, las ventas seguirán registrándose y el historial dejará de mostrarse.",
            checked = state.settings.salesHistoryEnabled,
            enabled = !state.saving,
            onCheckedChange = onSalesHistoryChange,
        )

        Text("Valores predeterminados de productos nuevos", style = MaterialTheme.typography.titleLarge)
        Text(
            "Se aplican al crear productos nuevos. Puedes cambiar cada valor antes de publicar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DefaultProductChoice(
            label = "Categoría predeterminada",
            selected = state.settings.defaultProductCategory,
            options = ProductOptions.categories,
            enabled = !state.saving,
            onSelect = { value -> onProductDefaultsChange { it.copy(defaultProductCategory = value) } },
        )
        OutlinedTextField(
            value = state.settings.defaultProductType,
            onValueChange = { value -> onProductDefaultsChange { it.copy(defaultProductType = value.take(60)) } },
            label = { Text("Tipo predeterminado") },
            supportingText = { Text("Se puede cambiar al crear el producto.") },
            singleLine = true,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth(),
        )
        DefaultProductChoice(
            label = "Modalidad predeterminada",
            selected = state.settings.defaultProductModality,
            options = ProductOptions.modalities,
            enabled = !state.saving,
            onSelect = { value -> onProductDefaultsChange { it.copy(defaultProductModality = value) } },
        )
        SettingSwitch(
            title = "Manejar stock por defecto",
            description = "Activa el control de unidades al iniciar cada producto.",
            checked = state.settings.defaultProductManagesStock,
            enabled = !state.saving,
            onCheckedChange = { value -> onProductDefaultsChange { it.copy(defaultProductManagesStock = value) } },
        )
        if (state.settings.defaultProductManagesStock) {
            OutlinedTextField(
                value = state.settings.defaultProductStock.toString(),
                onValueChange = { value ->
                    val stock = value.filter(Char::isDigit).take(7).toIntOrNull() ?: 0
                    onProductDefaultsChange { it.copy(defaultProductStock = stock) }
                },
                label = { Text("Stock inicial predeterminado") },
                singleLine = true,
                enabled = !state.saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            DefaultProductChoice(
                label = "Al agotarse el stock",
                selected = state.settings.defaultProductExhaustedBehavior,
                options = ProductOptions.exhaustedBehaviors,
                enabled = !state.saving,
                onSelect = { value -> onProductDefaultsChange { it.copy(defaultProductExhaustedBehavior = value) } },
            )
        }
        SettingSwitch(
            title = "Disponible al crear",
            description = "Define si el producto nuevo parte visible en tu catálogo.",
            checked = state.settings.defaultProductAvailable,
            enabled = !state.saving,
            onCheckedChange = { value -> onProductDefaultsChange { it.copy(defaultProductAvailable = value) } },
        )

        Text("Acceso con huella", style = MaterialTheme.typography.titleMedium)
        if (biometricAvailable) {
            SettingSwitch(
                title = "Usar huella para ingresar",
                description = "La huella se valida en el teléfono; BORA no guarda datos biométricos.",
                checked = biometricEnabled,
                enabled = !state.saving,
                onCheckedChange = onBiometricEnabledChange,
            )
        } else {
            Text(
                "Este teléfono no tiene huella configurada. Puedes seguir ingresando con tu contraseña.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text("Mensajes recibidos por formulario", style = MaterialTheme.typography.titleLarge)
        if (state.contacts.isEmpty()) {
            Text("Todavía no hay mensajes recibidos.")
        } else {
            state.contacts.forEach { contact -> StoreContactCard(contact) }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Button(
            onClick = onSave,
            enabled = !state.saving,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (state.saving) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Guardando…")
            } else {
                Text("Guardar cambios")
            }
        }
        TextButton(onClick = onClearFeedback, enabled = state.error != null || state.message != null) {
            Text("Limpiar mensaje")
        }
    }
}

@Composable
private fun StoreLocationPickerMap(
    latitude: Double,
    longitude: Double,
    onLocationSelected: (Double, Double) -> Unit,
) {
    val safeLatitude = latitude.takeIf { it != 0.0 } ?: -41.4689
    val safeLongitude = longitude.takeIf { it != 0.0 } ?: -72.9411
    val html = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
        <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
        <style>html,body,#map{height:100%;margin:0}</style></head><body><div id="map"></div>
        <script>
          var lat = $safeLatitude, lon = $safeLongitude;
          var map = L.map('map').setView([lat,lon], 14);
          L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19, attribution: '&copy; OpenStreetMap'
          }).addTo(map);
          var marker = L.marker([lat,lon], {draggable:true}).addTo(map);
          function report(p) { BoraLocation.onSelected(p.lat, p.lng); }
          marker.on('dragend', function(e) { report(e.target.getLatLng()); });
          map.on('click', function(e) { marker.setLatLng(e.latlng); report(e.latlng); });
        </script></body></html>
    """.trimIndent()
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(220.dp),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                addJavascriptInterface(StoreLocationBridge(onLocationSelected), "BoraLocation")
                loadDataWithBaseURL("https://www.openstreetmap.org", html, "text/html", "UTF-8", null)
            }
        },
        update = { webView ->
            if (webView.tag != "$safeLatitude,$safeLongitude") {
                webView.tag = "$safeLatitude,$safeLongitude"
                webView.loadDataWithBaseURL("https://www.openstreetmap.org", html, "text/html", "UTF-8", null)
            }
        },
    )
}

private class StoreLocationBridge(
    private val onLocationSelected: (Double, Double) -> Unit,
) {
    @JavascriptInterface
    fun onSelected(latitude: Double, longitude: Double) {
        onLocationSelected(latitude, longitude)
    }
}

@Composable
private fun DefaultProductChoice(
    label: String,
    selected: String,
    options: List<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(selected) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun StoreContactCard(contact: StoreContact) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(contact.senderName.ifBlank { "Persona interesada" }, style = MaterialTheme.typography.titleMedium)
            if (contact.senderEmail.isNotBlank()) Text(contact.senderEmail)
            if (contact.senderPhone.isNotBlank()) Text(contact.senderPhone)
            Text(contact.message, style = MaterialTheme.typography.bodyMedium)
            contact.createdAt?.let {
                Text(
                    DateFormat.getDateTimeInstance().format(Date(it.seconds * 1000L)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun StoreSettingsContentPreview() {
    BoraTheme {
        StoreSettingsContent(
            state = StoreSettingsUiState(
                loading = false,
                profile = StoreProfile(name = "Tienda de ejemplo", showPrices = true),
                settings = StorePrivateSettings(),
            ),
            biometricAvailable = true,
            biometricEnabled = false,
            onBiometricEnabledChange = {},
            onChooseLogo = {},
            onNameChange = {},
            onDescriptionChange = {},
            onBusinessTypeChange = {},
            onSectorChange = {},
            onContactModeChange = {},
            onShowPricesChange = {},
            onPhoneChange = {},
            onEmailChange = {},
            onWhatsappChange = {},
            onContactNotificationsChange = {},
            onStartTimeChange = {},
            onEndTimeChange = {},
            onRatingsChange = {},
            onSalesHistoryChange = {},
            onProductDefaultsChange = {},
            onSave = {},
            onClearFeedback = {},
        )
    }
}
