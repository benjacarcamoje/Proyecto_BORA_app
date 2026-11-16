package com.esp.bora.data

import com.google.firebase.Timestamp

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
)

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
