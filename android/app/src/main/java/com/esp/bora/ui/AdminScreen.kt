package com.esp.bora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private data class AdminAccountRow(
    val uid: String,
    val email: String,
    val displayName: String,
    val role: String,
    val active: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(onSignOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    val functions = remember { FirebaseFunctions.getInstance() }
    var accounts by remember { mutableStateOf<List<AdminAccountRow>>(emptyList()) }
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("emprendedora") }
    var access by remember { mutableStateOf("allow") }
    var categories by remember { mutableStateOf("Artesanía, Alimentos, Textil, Joyería, Hogar y decoración, Cosmética, Otro") }
    var productTypes by remember { mutableStateOf("General") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var activeSection by remember { mutableStateOf("home") }

    if (activeSection == "programs") {
        AdminProgramsScreen(onBack = { activeSection = "home" })
        return
    }

    fun parseAccounts(data: Any?): List<AdminAccountRow> {
        val result = data as? Map<*, *> ?: return emptyList()
        return (result["accounts"] as? List<*>).orEmpty().mapNotNull { row ->
            val item = row as? Map<*, *> ?: return@mapNotNull null
            val uid = item["uid"] as? String ?: return@mapNotNull null
            AdminAccountRow(
                uid = uid,
                email = item["email"] as? String ?: "",
                displayName = item["displayName"] as? String ?: "",
                role = item["role"] as? String ?: "",
                active = item["active"] as? Boolean ?: false,
            )
        }
    }

    fun load() {
        scope.launch {
            busy = true
            try {
                accounts = parseAccounts(functions.getHttpsCallable("adminListAccounts").call().await().data)
                val config = functions.getHttpsCallable("adminGetSettings").call().await().data as? Map<*, *>
                access = config?.get("anonymousNavigation") as? String ?: "allow"
                categories = (config?.get("categories") as? List<*>)?.filterIsInstance<String>()?.joinToString(", ") ?: categories
                productTypes = (config?.get("productTypes") as? List<*>)?.filterIsInstance<String>()?.joinToString(", ") ?: productTypes
                message = "Datos actualizados."
            } catch (e: Exception) { message = e.localizedMessage ?: "No se pudieron cargar los datos." }
            busy = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Administración BORA") }, actions = {
        TextButton(onClick = onSignOut) { Text("Salir") }
    }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { activeSection = "programs" }, modifier = Modifier.fillMaxWidth()) {
                Text("Ferias y asociaciones")
            }
            Button(onClick = ::load, enabled = !busy) { Text("Cargar cuentas y configuración") }
            Text("Cuentas", style = MaterialTheme.typography.titleLarge)
            accounts.forEach { account ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(account.displayName.ifBlank { account.email }, style = MaterialTheme.typography.titleMedium)
                            Text(account.email)
                            Text(if (account.role == "admin") "Administrador" else "Emprendedora")
                        }
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    functions.getHttpsCallable("adminSetAccountActive").call(
                                        mapOf("uid" to account.uid, "active" to !account.active),
                                    ).await()
                                    accounts = parseAccounts(functions.getHttpsCallable("adminListAccounts").call().await().data)
                                    message = if (account.active) "Cuenta desactivada." else "Cuenta activada."
                                } catch (e: Exception) { message = e.localizedMessage ?: "No se pudo cambiar el estado." }
                                busy = false
                            }
                        }) { Text(if (account.active) "Desactivar" else "Activar") }
                    }
                }
            }

            HorizontalDivider()
            Text("Crear cuenta", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it }, label = { Text("Correo") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password, { password = it },
                label = { Text("Contraseña inicial (mínimo 10)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(role == "emprendedora", { role = "emprendedora" }, label = { Text("Emprendedora") })
                FilterChip(role == "admin", { role = "admin" }, label = { Text("Administrador") })
            }
            Button(enabled = !busy && name.isNotBlank() && email.isNotBlank() && password.length >= 10,
                onClick = { scope.launch {
                    busy = true
                    try {
                        functions.getHttpsCallable("adminCreateAccount").call(mapOf(
                            "email" to email, "displayName" to name, "password" to password, "role" to role,
                        )).await()
                        message = "Cuenta creada."
                        email = ""; name = ""; password = ""
                        accounts = parseAccounts(functions.getHttpsCallable("adminListAccounts").call().await().data)
                    } catch (e: Exception) { message = e.localizedMessage ?: "No se pudo crear la cuenta." }
                    busy = false
                } }) { Text("Crear cuenta") }

            HorizontalDivider()
            Text("Configuración central", style = MaterialTheme.typography.titleLarge)
            Text("Navegación anónima")
            listOf("allow" to "Permitir", "prompt" to "Invitar", "require_login" to "Exigir ingreso").forEach { (value, label) ->
                FilterChip(access == value, { access = value }, label = { Text(label) })
            }
            OutlinedTextField(categories, { categories = it }, label = { Text("Categorías separadas por coma") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(productTypes, { productTypes = it }, label = { Text("Tipos de producto separados por coma") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy, onClick = { scope.launch {
                busy = true
                try {
                    functions.getHttpsCallable("adminSaveSettings").call(mapOf(
                        "anonymousNavigation" to access,
                        "productTypes" to productTypes.split(",").map(String::trim).filter(String::isNotBlank),
                        "categories" to categories.split(",").map(String::trim).filter(String::isNotBlank),
                    )).await()
                    message = "Configuración guardada."
                } catch (e: Exception) { message = e.localizedMessage ?: "No se pudo guardar." }
                busy = false
            } }) { Text("Guardar configuración") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(message)
        }
    }
}
