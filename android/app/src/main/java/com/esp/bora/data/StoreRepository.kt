package com.esp.bora.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class StoreRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) {
    suspend fun resolveStoreId(uid: String): String =
        runCatching { db.collection("users").document(uid).get().await().getString("storeId") }
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
                    ?.toObject(StoreProfile::class.java)
                    ?.copy(id = snapshot.id)
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
                document.toObject(StoreProfile::class.java)?.copy(id = document.id)
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
        val settingsRef = db.collection(STORE_SETTINGS).document(uid)
        val oldProfile = profileRef.get().await()
        val oldSettings = settingsRef.get().await()
        var uploadedLogoPath: String? = null

        try {
            val logoPath = if (logoBytes != null) {
                "stores/$uid/logo/${System.currentTimeMillis()}.jpg".also { path ->
                    storage.reference.child(path).putBytes(
                        logoBytes,
                        StorageMetadata.Builder().setContentType("image/jpeg").build(),
                    ).await()
                    uploadedLogoPath = path
                }
            } else {
                profile.logoPath
            }
            val logoUrl = if (logoBytes != null) {
                storage.reference.child(logoPath).downloadUrl.await().toString()
            } else {
                profile.logoUrl
            }
            val publishWhatsapp = profile.contactMode in setOf("whatsapp", "both")
            val profileData = mapOf(
                "ownerUid" to uid,
                "name" to profile.name.trim(),
                "logoUrl" to logoUrl,
                "logoPath" to logoPath,
                "description" to profile.description.trim(),
                "businessType" to profile.businessType.trim(),
                "sector" to profile.sector.trim(),
                "contactMode" to profile.contactMode,
                "whatsappPublic" to if (publishWhatsapp) settings.whatsapp.trim() else "",
                "showPrices" to profile.showPrices,
                "pickupAddress" to profile.pickupAddress.trim(),
                "pickupLatitude" to profile.pickupLatitude,
                "pickupLongitude" to profile.pickupLongitude,
                "createdAt" to (oldProfile.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            val privateData = mapOf(
                "ownerUid" to uid,
                "contactPhone" to settings.contactPhone.trim(),
                "contactEmail" to settings.contactEmail.trim(),
                "whatsapp" to settings.whatsapp.trim(),
                "contactNotificationsEnabled" to settings.contactNotificationsEnabled,
                "contactNotificationStartMinutes" to settings.contactNotificationStartMinutes,
                "contactNotificationEndMinutes" to settings.contactNotificationEndMinutes,
                "ratingsEnabled" to settings.ratingsEnabled,
                "salesHistoryEnabled" to settings.salesHistoryEnabled,
                "defaultProductCategory" to settings.defaultProductCategory,
                "defaultProductType" to settings.defaultProductType,
                "defaultProductModality" to settings.defaultProductModality,
                "defaultProductManagesStock" to settings.defaultProductManagesStock,
                "defaultProductStock" to settings.defaultProductStock,
                "defaultProductExhaustedBehavior" to settings.defaultProductExhaustedBehavior,
                "defaultProductAvailable" to settings.defaultProductAvailable,
                "createdAt" to (oldSettings.getTimestamp("createdAt") ?: FieldValue.serverTimestamp()),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            db.batch()
                .set(profileRef, profileData)
                .set(settingsRef, privateData)
                .commit()
                .await()

            val oldLogoPath = oldProfile.getString("logoPath").orEmpty()
            if (oldLogoPath.isNotBlank() && oldLogoPath != logoPath) {
                runCatching { storage.reference.child(oldLogoPath).delete().await() }
            }
        } catch (error: Exception) {
            uploadedLogoPath?.let { path ->
                runCatching { storage.reference.child(path).delete().await() }
            }
            throw error
        }
    }

    private companion object {
        const val STORES = "stores"
        const val STORE_SETTINGS = "storeSettings"
        const val STORE_CONTACTS = "storeContacts"
        const val CONTACT_LIMIT = 100L
        val CONTACT_MODES = setOf("whatsapp", "form", "both")
    }
}
