package com.esp.bora.ui

import android.location.Geocoder
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.esp.bora.data.Fair
import com.esp.bora.data.FairInvitation
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

private data class FairAdminAccount(
    val uid: String,
    val name: String,
    val email: String,
    val role: String,
    val businessType: String = "",
)
private data class FairParticipantRow(
    val uid: String,
    val name: String,
    val status: String,
    val attended: Boolean,
    val businessType: String = "",
    val associationId: String = "",
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AdminProgramsScreen(onBack: () -> Unit) {
    var section by remember { mutableStateOf("home") }
    when (section) {
        "fairs" -> FairAdminScreen(onBack = { section = "home" })
        "associations" -> AssociationAdminScreen(onBack = { section = "home" })
        else -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Ferias y asociaciones") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("Administración") } },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = { section = "fairs" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Administrar ferias e invitaciones")
                }
                Button(onClick = { section = "associations" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Administrar asociaciones e integrantes")
                }
            }
        }
    }
}

@Composable
private fun AdminFilterChoice(
    label: String,
    selectedValue: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedValue }?.second ?: "Todos"
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: $selectedLabel") }
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (value, text) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FairAdminScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val functions = remember { FirebaseFunctions.getInstance() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var fairs by remember { mutableStateOf<List<Fair>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<FairAdminAccount>>(emptyList()) }
    var associations by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var participants by remember { mutableStateOf<List<FairParticipantRow>>(emptyList()) }
    var selectedFair by remember { mutableStateOf<Fair?>(null) }
    var selectedAccountIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedAssociationId by remember { mutableStateOf("") }
    var fairAssociationMenu by remember { mutableStateOf(false) }
    var inviteAsDirectAdd by remember { mutableStateOf(false) }
    var participantStatusFilter by remember { mutableStateOf("Todos") }
    var participantTypeFilter by remember { mutableStateOf("Todos") }
    var participantAssociationFilter by remember { mutableStateOf("Todos") }
    var search by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var imageUrl by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf("") }
    var longitude by remember { mutableStateOf("") }
    var startsAt by remember { mutableStateOf("") }
    var endsAt by remember { mutableStateOf("") }
    var organizer by remember { mutableStateOf("") }
    var published by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun loadAll() {
        scope.launch {
            busy = true
            try {
                fairs = db.collection("fairs").get().await().documents.mapNotNull { doc ->
                    doc.toObject(Fair::class.java)?.copy(id = doc.id)
                }.sortedBy { it.startsAt?.seconds ?: Long.MAX_VALUE }
                val storeTypes = db.collection("stores").get().await().documents.associate { doc ->
                    doc.getString("ownerUid").orEmpty() to doc.getString("businessType").orEmpty()
                }
                val accountData = functions.getHttpsCallable("adminListAccounts").call().await().getData() as? Map<*, *>
                accounts = (accountData?.get("accounts") as? List<*>).orEmpty().mapNotNull { item ->
                    val row = item as? Map<*, *> ?: return@mapNotNull null
                    val uid = row["uid"] as? String ?: return@mapNotNull null
                    val role = row["role"] as? String ?: ""
                    if (role != "emprendedora" || row["active"] == false) return@mapNotNull null
                    FairAdminAccount(
                        uid,
                        row["displayName"] as? String ?: "",
                        row["email"] as? String ?: "",
                        role,
                        storeTypes[uid].orEmpty(),
                    )
                }
                associations = db.collection("associations").get().await().documents.map { doc ->
                    mapOf("id" to doc.id, "name" to doc.getString("name").orEmpty())
                }
                message = "Datos administrativos actualizados."
            } catch (error: Exception) {
                message = error.localizedMessage ?: "No se pudieron cargar ferias y participantes."
            }
            busy = false
        }
    }

    fun loadParticipants(fair: Fair?) {
        selectedFair = fair
        participants = emptyList()
        selectedAccountIds = emptySet()
        if (fair == null) return
        scope.launch {
            runCatching {
                db.collection("fairs").document(fair.id).collection("participants").get().await()
                    .documents.map { doc ->
                        FairParticipantRow(
                            uid = doc.id,
                            name = doc.getString("entrepreneurName").orEmpty(),
                            status = doc.getString("status").orEmpty(),
                            attended = doc.getBoolean("attended") == true,
                            businessType = doc.getString("businessType").orEmpty(),
                            associationId = doc.getString("associationId").orEmpty(),
                        )
                    }
            }.onSuccess { participants = it }
                .onFailure { message = it.localizedMessage ?: "No se pudo cargar la lista de participantes." }
        }
    }

    LaunchedEffect(Unit) { loadAll() }
    LaunchedEffect(message) {
        if (message.isNotBlank()) snackbar.showSnackbar(message)
    }

    fun editFair(fair: Fair?) {
        selectedFair = fair
        name = fair?.name.orEmpty()
        description = fair?.description.orEmpty()
        imageUrl = fair?.imageUrl.orEmpty()
        address = fair?.address.orEmpty()
        latitude = fair?.latitude?.takeIf { it != 0.0 }?.toString().orEmpty()
        longitude = fair?.longitude?.takeIf { it != 0.0 }?.toString().orEmpty()
        startsAt = fair?.startsAt?.toDate()?.toInstant()?.atZone(ZoneId.systemDefault())
            ?.toLocalDateTime()?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")).orEmpty()
        endsAt = fair?.endsAt?.toDate()?.toInstant()?.atZone(ZoneId.systemDefault())
            ?.toLocalDateTime()?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")).orEmpty()
        organizer = fair?.organizer.orEmpty()
        published = fair?.published ?: false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Administrar ferias") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Volver") } },
                actions = { TextButton(onClick = ::loadAll, enabled = !busy) { Text("Actualizar") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Ferias", style = MaterialTheme.typography.titleLarge)
            fairs.forEach { fair ->
                OutlinedButton(
                    onClick = { loadParticipants(fair); editFair(fair) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("${fair.name} · ${if (fair.cancelled) "Cancelada" else if (fair.published) "Publicada" else "Borrador"}") }
            }
            Button(onClick = { editFair(null); participants = emptyList() }) {
                Text("Crear feria")
            }
            HorizontalDivider()
            Text(if (selectedFair == null) "Nueva feria" else "Editar feria", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it.take(1000) }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(imageUrl, { imageUrl = it.take(2048) }, label = { Text("URL https de imagen") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(address, { address = it.take(200) }, label = { Text("Dirección en Puerto Montt") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(
                enabled = address.isNotBlank() && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val result = withContext(Dispatchers.IO) {
                                @Suppress("DEPRECATION")
                                Geocoder(context).getFromLocationName("$address, Puerto Montt, Chile", 1)?.firstOrNull()
                            }
                            if (result == null) message = "No se encontró esa dirección. Ingresa las coordenadas."
                            else {
                                latitude = result.latitude.toString()
                                longitude = result.longitude.toString()
                                message = "Ubicación encontrada: ${result.latitude}, ${result.longitude}"
                            }
                        } catch (error: Exception) {
                            message = error.localizedMessage ?: "No fue posible buscar la dirección en este teléfono."
                        }
                        busy = false
                    }
                },
            ) { Text("Buscar dirección y fijar coordenadas") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(latitude, { latitude = it.take(20) }, label = { Text("Latitud") }, modifier = Modifier.weight(1f))
                OutlinedTextField(longitude, { longitude = it.take(20) }, label = { Text("Longitud") }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(startsAt, { startsAt = it.take(16) }, label = { Text("Inicio (AAAA-MM-DDTHH:MM)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(endsAt, { endsAt = it.take(16) }, label = { Text("Término (AAAA-MM-DDTHH:MM)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(organizer, { organizer = it.take(100) }, label = { Text("Organizador") }, modifier = Modifier.fillMaxWidth())
            Row {
                Checkbox(checked = published, onCheckedChange = { published = it })
                Text("Publicar feria")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            val start = LocalDateTime.parse(startsAt).atZone(ZoneId.systemDefault()).toInstant()
                            val end = LocalDateTime.parse(endsAt).atZone(ZoneId.systemDefault()).toInstant()
                            require(end >= start) { "La fecha de término debe ser posterior al inicio." }
                            val lat = latitude.toDoubleOrNull() ?: error("Busca la dirección o escribe una latitud.")
                            val lon = longitude.toDoubleOrNull() ?: error("Busca la dirección o escribe una longitud.")
                            require(lat in -90.0..90.0 && lon in -180.0..180.0) { "Las coordenadas no son válidas." }
                            require(name.isNotBlank() && address.isNotBlank() && organizer.isNotBlank()) {
                                "Completa nombre, dirección y organizador."
                            }
                            require(imageUrl.startsWith("https://")) { "La imagen debe usar una URL https." }
                            val data = mapOf(
                                "name" to name.trim(),
                                "description" to description.trim(),
                                "imageUrl" to imageUrl.trim(),
                                "address" to address.trim(),
                                "latitude" to lat,
                                "longitude" to lon,
                                "startsAt" to Timestamp(Date.from(start)),
                                "endsAt" to Timestamp(Date.from(end)),
                                "organizer" to organizer.trim(),
                                "published" to published,
                                "cancelled" to (selectedFair?.cancelled ?: false),
                                "updatedAt" to FieldValue.serverTimestamp(),
                            )
                            if (selectedFair == null) {
                                db.collection("fairs").add(data + ("createdAt" to FieldValue.serverTimestamp())).await()
                            } else {
                                db.collection("fairs").document(selectedFair!!.id).update(data).await()
                            }
                            message = "Feria guardada."
                            fairs = db.collection("fairs").get().await().documents.mapNotNull { doc ->
                                doc.toObject(Fair::class.java)?.copy(id = doc.id)
                            }.sortedBy { it.startsAt?.seconds ?: Long.MAX_VALUE }
                        } catch (error: Exception) {
                            message = error.localizedMessage ?: "No se pudo guardar la feria."
                        }
                        busy = false
                    }
                }) { Text("Guardar feria") }
                if (selectedFair != null && !selectedFair!!.cancelled) {
                    OutlinedButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                db.collection("fairs").document(selectedFair!!.id).update(
                                    mapOf("cancelled" to true, "published" to false, "updatedAt" to FieldValue.serverTimestamp()),
                                ).await()
                                message = "Feria cancelada."
                                loadAll()
                            } catch (error: Exception) { message = error.localizedMessage ?: "No se pudo cancelar." }
                            busy = false
                        }
                    }) { Text("Cancelar feria") }
                }
            }

            selectedFair?.let { fair ->
                HorizontalDivider()
                Text("Invitaciones y participantes · ${fair.name}", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(inviteAsDirectAdd, { inviteAsDirectAdd = true }, label = { Text("Agregar directamente") })
                    FilterChip(!inviteAsDirectAdd, { inviteAsDirectAdd = false }, label = { Text("Enviar invitación") })
                }
                Text("Emprendedoras")
                accounts.forEach { account ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(account.name.ifBlank { account.email })
                            Text(account.email, style = MaterialTheme.typography.bodySmall)
                        }
                        Checkbox(
                            checked = account.uid in selectedAccountIds,
                            onCheckedChange = { checked ->
                                selectedAccountIds = if (checked) selectedAccountIds + account.uid else selectedAccountIds - account.uid
                            },
                            enabled = !busy,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && selectedAccountIds.isNotEmpty(), onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val batch = db.batch()
                                val adminUid = FirebaseAuth.getInstance().currentUser?.uid
                                    ?: error("La sesión de administrador expiró.")
                                selectedAccountIds.forEach { uid ->
                                    val account = accounts.first { it.uid == uid }
                                    val ref = db.collection("fairs").document(fair.id).collection("participants").document(uid)
                                    val existing = ref.get().await()
                                    if (existing.getString("status") != "confirmada") {
                                        batch.set(ref, mapOf(
                                            "entrepreneurUid" to uid,
                                            "entrepreneurName" to account.name.ifBlank { account.email },
                                            "status" to if (inviteAsDirectAdd) "agregada_admin" else "invitada",
                                            "invitedBy" to (existing.getString("invitedBy") ?: adminUid),
                                            "attended" to (existing.getBoolean("attended") == true),
                                            "businessType" to account.businessType,
                                            "associationId" to "",
                                            "associationName" to "",
                                            "updatedAt" to FieldValue.serverTimestamp(),
                                        ))
                                    }
                                }
                                batch.commit().await()
                                message = if (inviteAsDirectAdd) "Participantes agregados." else "Invitaciones enviadas."
                                loadParticipants(fair)
                                selectedAccountIds = emptySet()
                            } catch (error: Exception) { message = error.localizedMessage ?: "No se pudieron aplicar los cambios." }
                            busy = false
                        }
                    }) { Text(if (inviteAsDirectAdd) "Agregar seleccionadas" else "Invitar seleccionadas") }
                    if (selectedAssociationId.isNotBlank()) {
                        OutlinedButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val associationRef = db.collection("associations").document(selectedAssociationId)
                                    val members = associationRef.collection("members").get().await().documents
                                    val batch = db.batch()
                                    val adminUid = FirebaseAuth.getInstance().currentUser?.uid
                                        ?: error("La sesión de administrador expiró.")
                                    members.forEach { member ->
                                        val uid = member.id
                                        val ref = db.collection("fairs").document(fair.id).collection("participants").document(uid)
                                        val existing = ref.get().await()
                                        if (existing.getString("status") != "confirmada") {
                                            batch.set(ref, mapOf(
                                                "entrepreneurUid" to uid,
                                                "entrepreneurName" to member.getString("entrepreneurName").orEmpty(),
                                                "status" to if (inviteAsDirectAdd) "agregada_admin" else "invitada",
                                                "invitedBy" to (existing.getString("invitedBy") ?: adminUid),
                                                "attended" to (existing.getBoolean("attended") == true),
                                                "businessType" to member.getString("businessType").orEmpty(),
                                                "associationId" to selectedAssociationId,
                                                "associationName" to associations.firstOrNull { it["id"] == selectedAssociationId }?.get("name").orEmpty(),
                                                "updatedAt" to FieldValue.serverTimestamp(),
                                            ))
                                        }
                                    }
                                    batch.commit().await()
                                    message = "Integrantes de la asociación añadidas."
                                    loadParticipants(fair)
                                } catch (error: Exception) { message = error.localizedMessage ?: "No se pudo invitar a la asociación." }
                                busy = false
                            }
                        }) { Text("Invitar integrantes de asociación") }
                    }
                }
                if (associations.isNotEmpty()) {
                    val association = associations.firstOrNull { it["id"] == selectedAssociationId }
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { fairAssociationMenu = true }) {
                            Text(association?.get("name")?.let { "Asociación: $it" } ?: "Elegir asociación")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = fairAssociationMenu,
                            onDismissRequest = { fairAssociationMenu = false },
                        ) {
                            associations.forEach { item ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(item["name"].orEmpty()) },
                                    onClick = {
                                        selectedAssociationId = item["id"].orEmpty()
                                        fairAssociationMenu = false
                                    },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("Checklist de participantes")
                OutlinedTextField(search, { search = it.take(80) }, label = { Text("Buscar participante") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val types = listOf("Todos") + participants.map { it.businessType }.filter(String::isNotBlank).distinct().sorted()
                    AdminFilterChoice("Tipo", participantTypeFilter, types.map { it to it }) { participantTypeFilter = it }
                    val associationOptions = listOf("Todos" to "Todos", "__none" to "Sin asociación") +
                        associations.map { item -> item["id"].orEmpty() to item["name"].orEmpty() }
                    AdminFilterChoice(
                        "Asociación",
                        participantAssociationFilter,
                        associationOptions,
                    ) { participantAssociationFilter = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Todos", "invitada", "confirmada", "rechazada", "agregada_admin").forEach { status ->
                        FilterChip(participantStatusFilter == status, { participantStatusFilter = status }, label = { Text(status) })
                    }
                }
                val visibleParticipants = participants.filter {
                    (participantStatusFilter == "Todos" || it.status == participantStatusFilter) &&
                        (participantTypeFilter == "Todos" || it.businessType == participantTypeFilter) &&
                        (participantAssociationFilter == "Todos" ||
                            (participantAssociationFilter == "__none" && it.associationId.isBlank()) ||
                            it.associationId == participantAssociationFilter) &&
                        (search.isBlank() || it.name.contains(search, true) || it.uid.contains(search, true))
                }
                visibleParticipants.forEach { participant ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(participant.name)
                            Text(participant.status, style = MaterialTheme.typography.bodySmall)
                        }
                        if (participant.status == "confirmada" || participant.status == "agregada_admin") {
                            Row {
                                Text("Asistió")
                                Checkbox(
                                    checked = participant.attended,
                                    onCheckedChange = { attended ->
                                        scope.launch {
                                            try {
                                                db.collection("fairs").document(fair.id)
                                                    .collection("participants").document(participant.uid)
                                                    .update(mapOf("attended" to attended, "updatedAt" to FieldValue.serverTimestamp()))
                                                    .await()
                                                loadParticipants(fair)
                                            } catch (error: Exception) {
                                                message = error.localizedMessage ?: "No se pudo guardar la asistencia."
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                Button(enabled = !busy && fair.published, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            functions.getHttpsCallable("adminSendFairReminder").call(mapOf("fairId" to fair.id)).await()
                            message = "Avisos enviados a participantes pendientes y confirmados."
                        } catch (error: Exception) { message = error.localizedMessage ?: "No se pudieron enviar avisos." }
                        busy = false
                    }
                }) { Text("Enviar recordatorio/aviso") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}



private data class AssociationEntry(
    val id: String,
    val name: String,
    val logoUrl: String,
    val description: String,
    val sector: String,
    val contact: String,
    val active: Boolean,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AssociationAdminScreen(onBack: () -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    val functions = remember { FirebaseFunctions.getInstance() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var associations by remember { mutableStateOf<List<AssociationEntry>>(emptyList()) }
    var accounts by remember { mutableStateOf<List<FairAdminAccount>>(emptyList()) }
    var members by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var selected by remember { mutableStateOf<AssociationEntry?>(null) }
    var selectedUid by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var logoUrl by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var sector by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }

    fun load() {
        scope.launch {
            busy = true
            try {
                associations = db.collection("associations").get().await().documents.map { doc ->
                    AssociationEntry(
                        id = doc.id,
                        name = doc.getString("name").orEmpty(),
                        logoUrl = doc.getString("logoUrl").orEmpty(),
                        description = doc.getString("description").orEmpty(),
                        sector = doc.getString("sector").orEmpty(),
                        contact = doc.getString("contact").orEmpty(),
                        active = doc.getBoolean("active") != false,
                    )
                }
                val storeTypes = db.collection("stores").get().await().documents.associate { doc ->
                    doc.getString("ownerUid").orEmpty() to doc.getString("businessType").orEmpty()
                }
                val data = functions.getHttpsCallable("adminListAccounts").call().await().getData() as? Map<*, *>
                accounts = (data?.get("accounts") as? List<*>).orEmpty().mapNotNull { item ->
                    val row = item as? Map<*, *> ?: return@mapNotNull null
                    if (row["role"] != "emprendedora" || row["active"] == false) return@mapNotNull null
                    val uid = row["uid"] as? String ?: return@mapNotNull null
                    FairAdminAccount(
                        uid = uid,
                        name = row["displayName"] as? String ?: "",
                        email = row["email"] as? String ?: "",
                        role = "emprendedora",
                        businessType = storeTypes[uid].orEmpty(),
                    )
                }
                message = "Asociaciones y cuentas actualizadas."
            } catch (error: Exception) { message = error.localizedMessage ?: "No se pudieron cargar los datos." }
            busy = false
        }
    }

    fun selectAssociation(entry: AssociationEntry?) {
        selected = entry
        name = entry?.name.orEmpty()
        logoUrl = entry?.logoUrl.orEmpty()
        description = entry?.description.orEmpty()
        sector = entry?.sector.orEmpty()
        contact = entry?.contact.orEmpty()
        active = entry?.active ?: true
        members = emptyList()
        selectedUid = ""
        if (entry != null) scope.launch {
            runCatching {
                db.collection("associations").document(entry.id).collection("members").get().await()
                    .documents.map { it.id to it.getString("entrepreneurName").orEmpty() }
            }.onSuccess { members = it }
                .onFailure { message = it.localizedMessage ?: "No se pudieron cargar integrantes." }
        }
    }

    LaunchedEffect(Unit) { load() }
    LaunchedEffect(message) {
        if (message.isNotBlank()) snackbar.showSnackbar(message)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Administrar asociaciones") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Volver") } },
                actions = { TextButton(onClick = ::load, enabled = !busy) { Text("Actualizar") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Asociaciones", style = MaterialTheme.typography.titleLarge)
            associations.forEach { entry ->
                OutlinedButton(onClick = { selectAssociation(entry) }, modifier = Modifier.fillMaxWidth()) {
                    Text("${entry.name} · ${if (entry.active) "Activa" else "Inactiva"}")
                }
            }
            Button(onClick = { selectAssociation(null) }) { Text("Crear asociación") }
            HorizontalDivider()
            Text(if (selected == null) "Nueva asociación" else "Editar asociación", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it.take(100) }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(logoUrl, { logoUrl = it.take(2048) }, label = { Text("URL de logo (opcional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it.take(1000) }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sector, { sector = it.take(100) }, label = { Text("Sector de Puerto Montt") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(contact, { contact = it.take(200) }, label = { Text("Contacto") }, modifier = Modifier.fillMaxWidth())
            Row {
                Checkbox(checked = active, onCheckedChange = { active = it })
                Text("Asociación activa y visible")
            }
            Button(enabled = !busy && name.isNotBlank(), onClick = {
                scope.launch {
                    busy = true
                    try {
                        val ref = selected?.let { db.collection("associations").document(it.id) }
                            ?: db.collection("associations").document()
                        val data = mapOf(
                            "name" to name.trim(),
                            "logoUrl" to logoUrl.trim(),
                            "description" to description.trim(),
                            "sector" to sector.trim(),
                            "contact" to contact.trim(),
                            "active" to active,
                            "updatedAt" to FieldValue.serverTimestamp(),
                        )
                        if (selected == null) {
                            ref.set(data + ("createdAt" to FieldValue.serverTimestamp())).await()
                        } else {
                            ref.update(data).await()
                        }
                        message = "Asociación guardada."
                        load()
                        selectAssociation(
                            AssociationEntry(ref.id, name.trim(), logoUrl.trim(), description.trim(), sector.trim(), contact.trim(), active),
                        )
                    } catch (error: Exception) { message = error.localizedMessage ?: "No se pudo guardar la asociación." }
                    busy = false
                }
            }) { Text("Guardar asociación") }

            selected?.let { entry ->
                HorizontalDivider()
                Text("Integrantes", style = MaterialTheme.typography.titleLarge)
                if (accounts.isNotEmpty()) {
                    var accountMenu by remember { mutableStateOf(false) }
                    val account = accounts.firstOrNull { it.uid == selectedUid }
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { accountMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(account?.let { it.name.ifBlank { it.email } } ?: "Seleccionar emprendedora")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = accountMenu,
                            onDismissRequest = { accountMenu = false },
                        ) {
                            accounts.filter { candidate -> members.none { it.first == candidate.uid } }.forEach { candidate ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(candidate.name.ifBlank { candidate.email }) },
                                    onClick = {
                                        selectedUid = candidate.uid
                                        accountMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Button(enabled = !busy && selectedUid.isNotBlank(), onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val chosen = accounts.first { it.uid == selectedUid }
                                db.collection("associations").document(entry.id).collection("members")
                                    .document(chosen.uid).set(
                                        mapOf(
                                            "entrepreneurUid" to chosen.uid,
                                            "entrepreneurName" to chosen.name.ifBlank { chosen.email },
                                            "businessType" to chosen.businessType,
                                            "addedAt" to FieldValue.serverTimestamp(),
                                        ),
                                    ).await()
                                members = members + (chosen.uid to chosen.name.ifBlank { chosen.email })
                                selectedUid = ""
                                message = "Integrante agregada."
                            } catch (error: Exception) { message = error.localizedMessage ?: "No se pudo agregar." }
                            busy = false
                        }
                    }) { Text("Agregar integrante") }
                }
                if (members.isEmpty()) Text("Esta asociación aún no tiene integrantes.")
                members.forEach { (uid, memberName) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(memberName.ifBlank { uid }, modifier = Modifier.weight(1f))
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    db.collection("associations").document(entry.id).collection("members").document(uid).delete().await()
                                    members = members.filterNot { it.first == uid }
                                    message = "Integrante quitada."
                                } catch (error: Exception) { message = error.localizedMessage ?: "No se pudo quitar." }
                                busy = false
                            }
                        }) { Text("Quitar") }
                    }
                }
                if (entry.active) {
                    TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                db.collection("associations").document(entry.id).update(
                                    mapOf("active" to false, "updatedAt" to FieldValue.serverTimestamp()),
                                ).await()
                                message = "Asociación desactivada."
                                load()
                            } catch (error: Exception) { message = error.localizedMessage ?: "No se pudo desactivar." }
                            busy = false
                        }
                    }) { Text("Desactivar asociación") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}
