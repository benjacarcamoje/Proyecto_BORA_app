package com.esp.bora.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

data class StoreProfile(
    val id: String = "",
    val ownerUid: String = "",
    val name: String = "",
    val logoUrl: String = "",
    val logoPath: String = "",
    val description: String = "",
    val businessType: String = "",
    val sector: String = "",
    val contactMode: String = "whatsapp",
    val whatsappPublic: String = "",
    val showPrices: Boolean = true,
    val pickupAddress: String = "",
    val pickupLatitude: Double = 0.0,
    val pickupLongitude: Double = 0.0,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val active: Boolean = true,
)

/** Maps the Spanish Firestore store document to the public Android model. */
fun DocumentSnapshot.toBoraStoreProfile(): StoreProfile? {
    val fields = data ?: return null
    fun string(vararg keys: String): String =
        keys.firstNotNullOfOrNull { fields[it] as? String }.orEmpty()
    fun number(vararg keys: String): Double =
        keys.firstNotNullOfOrNull { (fields[it] as? Number)?.toDouble() } ?: 0.0
    fun bool(default: Boolean, vararg keys: String): Boolean =
        keys.firstNotNullOfOrNull { fields[it] as? Boolean } ?: default

    val whatsapp = string("whatsapp", "whatsappPublic")
    val email = string("emailContacto", "contactEmail")
    val contactMode = when {
        whatsapp.isNotBlank() && email.isNotBlank() -> "both"
        whatsapp.isNotBlank() -> "whatsapp"
        email.isNotBlank() -> "form"
        else -> "whatsapp"
    }
    return StoreProfile(
        id = id,
        ownerUid = string("ID_Dueno", "ownerUid"),
        name = string("nombre", "name"),
        logoUrl = string("imagen", "logoUrl"),
        logoPath = string("logoPath"),
        description = string("descripcion", "description"),
        businessType = string("tipoEmprendimiento", "businessType", "categoria"),
        sector = string("sector"),
        contactMode = contactMode,
        whatsappPublic = whatsapp,
        showPrices = bool(true, "mostrarPrecios", "showPrices"),
        pickupAddress = string("direccionRetiro", "direccion", "pickupAddress"),
        pickupLatitude = number("pickupLatitude", "latitud"),
        pickupLongitude = number("pickupLongitude", "longitud"),
        createdAt = fields["CreadoEn"] as? Timestamp ?: fields["createdAt"] as? Timestamp,
        updatedAt = fields["ActualizadoEn"] as? Timestamp ?: fields["updatedAt"] as? Timestamp,
        active = bool(true, "activo", "active"),
    )
}

data class StorePrivateSettings(
    val ownerUid: String = "",
    val contactPhone: String = "",
    val contactEmail: String = "",
    val whatsapp: String = "",
    val contactNotificationsEnabled: Boolean = false,
    val contactNotificationStartMinutes: Int = 540,
    val contactNotificationEndMinutes: Int = 1080,
    val ratingsEnabled: Boolean = false,
    val salesHistoryEnabled: Boolean = true,
    val defaultProductCategory: String = "Otro",
    val defaultProductType: String = "General",
    val defaultProductModality: String = "Regular",
    val defaultProductManagesStock: Boolean = false,
    val defaultProductStock: Int = 0,
    val defaultProductExhaustedBehavior: String = "Mostrar agotado",
    val defaultProductAvailable: Boolean = true,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
)

data class StoreContact(
    val id: String = "",
    val senderName: String = "",
    val senderEmail: String = "",
    val senderPhone: String = "",
    val message: String = "",
    val createdAt: Timestamp? = null,
)
