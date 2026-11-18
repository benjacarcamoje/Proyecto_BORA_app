package com.esp.bora.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class StoreRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    suspend fun resolveStoreId(uid: String): String =
        runCatching { db.collection("usuarios").document(uid).get().await().getString("storeId") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: uid

    fun observeProfile(storeId: String): Flow<StoreProfile?> = callbackFlow {
        val registration = db.collection(STORES).document(storeId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val profile = snapshot?.takeIf { it.exists() }
                    ?.toBoraStoreProfile()
                trySend(profile)
            }
        awaitClose { registration.remove() }
    }

    fun observePublicProfiles(): Flow<List<StoreProfile>> = callbackFlow {
        val registration = db.collection(STORES).addSnapshotListener { snapshot, error ->
            if (error != null) {
                close(error)
                return@addSnapshotListener
            }
            val profiles = snapshot?.documents.orEmpty().mapNotNull { document ->
                document.toBoraStoreProfile()
            }
            trySend(profiles)
        }
        awaitClose { registration.remove() }
    }

    suspend fun getPrivateSettings(uid: String): StorePrivateSettings {
        val snapshot = db.collection(STORE_SETTINGS).document(uid).get().await()
        return snapshot.takeIf { it.exists() }
            ?.toObject(StorePrivateSettings::class.java)
            ?.copy(ownerUid = uid)
            ?: StorePrivateSettings(ownerUid = uid)
    }

    fun observePrivateSettings(uid: String): Flow<StorePrivateSettings> = callbackFlow {
        val registration = db.collection(STORE_SETTINGS).document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val settings = snapshot?.takeIf { it.exists() }
                    ?.toObject(StorePrivateSettings::class.java)
                    ?.copy(ownerUid = uid)
                    ?: StorePrivateSettings(ownerUid = uid)
                trySend(settings)
            }
        awaitClose { registration.remove() }
    }

    fun observeContacts(uid: String): Flow<List<StoreContact>> = callbackFlow {
        val registration = db.collection(STORE_CONTACTS)
            .whereEqualTo("ownerUid", uid)
            .limit(CONTACT_LIMIT)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val contacts = snapshot?.documents.orEmpty()
                    .map { document ->
                        StoreContact(
                            id = document.id,
                            senderName = document.getString("senderName").orEmpty(),
                            senderEmail = document.getString("senderEmail").orEmpty(),
                            senderPhone = document.getString("senderPhone").orEmpty(),
                            message = document.getString("message").orEmpty(),
                            createdAt = document.getTimestamp("createdAt"),
                        )
                    }
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
                trySend(contacts)
            }
        awaitClose { registration.remove() }
    }

    suspend fun save(
        uid: String,
        storeId: String,
        profile: StoreProfile,
        settings: StorePrivateSettings,
        logoBytes: ByteArray?,
    ) {
        require(auth.currentUser?.uid == uid) { "Tu sesión expiró. Vuelve a ingresar." }
        require(profile.name.isNotBlank()) { "Ingresa el nombre de la tienda." }
        require(profile.contactMode in CONTACT_MODES) { "Selecciona una forma de contacto válida." }
        require(settings.defaultProductCategory in ProductOptions.categories) { "Selecciona una categoría predeterminada válida." }
        require(settings.defaultProductType.isNotBlank() && settings.defaultProductType.length <= 60) {
            "Escribe un tipo predeterminado de producto."
        }
        require(settings.defaultProductModality in ProductOptions.modalities) { "Selecciona una modalidad predeterminada válida." }
        require(settings.defaultProductExhaustedBehavior in ProductOptions.exhaustedBehaviors) {
            "Selecciona qué ocurrirá cuando se agote el stock."
        }
        require(settings.defaultProductStock in 0..1_000_000) { "El stock predeterminado no es válido." }

        val profileRef = db.collection(STORES).document(storeId)
        val logoUrl = if (logoBytes != null) {
            val path = "logos/$uid/${UUID.randomUUID()}.jpg"
            SupabaseImageStorage.uploadJpeg(path, logoBytes)
        } else {
            profile.logoUrl
        }
        val publishWhatsapp = profile.contactMode in setOf("whatsapp", "both")
        val publishEmail = profile.contactMode in setOf("form", "both")
        val publicData = mapOf(
            "nombre" to profile.name.trim(),
            "descripcion" to profile.description.trim(),
            "categoria" to profile.businessType.trim(),
            "sector" to profile.sector.trim(),
            "telefono" to settings.contactPhone.trim(),
            "whatsapp" to if (publishWhatsapp) settings.whatsapp.trim() else "",
            "emailContacto" to if (publishEmail) settings.contactEmail.trim() else "",
            "imagen" to logoUrl,
        )
        profileRef.update(publicData).await()
    }

    private companion object {
        const val STORES = "tiendas"
        const val STORE_SETTINGS = "storeSettings"
        const val STORE_CONTACTS = "storeContacts"
        const val CONTACT_LIMIT = 100L
        val CONTACT_MODES = setOf("whatsapp", "form", "both")
    }
}
