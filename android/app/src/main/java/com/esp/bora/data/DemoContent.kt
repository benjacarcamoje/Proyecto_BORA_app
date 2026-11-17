package com.esp.bora.data

import com.google.firebase.Timestamp
import java.util.Date

/**
 * Datos locales de muestra para que el modo invitado sea explorable sin conexión.
 * Nunca se escriben en Firebase ni representan publicaciones reales.
 */
object DemoContent {
    val products = listOf(
        Product(
            id = "demo-murta",
            storeId = "demo-tienda-murta",
            name = "Mermelada artesanal de murta",
            description = "Preparada en Puerto Montt con murta de la zona. Frasco de 250 g.",
            price = 4_500L,
            category = "Alimentos",
            productType = "Conservas",
            modality = "Regular",
            managesStock = true,
            stock = 12,
            variants = listOf(
                ProductVariant(id = "frasco-250", name = "250 g", price = 4_500L, stock = 12),
                ProductVariant(id = "frasco-500", name = "500 g", price = 7_500L, stock = 6),
            ),
        ),
        Product(
            id = "demo-bufanda",
            storeId = "demo-tienda-textil",
            name = "Bufanda tejida a mano",
            description = "Lana suave, tejida en telar. Cada pieza tiene un diseño propio.",
            price = 12_000L,
            category = "Textil",
            productType = "Accesorios",
            modality = "Pieza única",
            managesStock = false,
        ),
        Product(
            id = "demo-tazon",
            storeId = "demo-tienda-ceramica",
            name = "Tazón de cerámica esmaltada",
            description = "Pieza hecha a mano; pequeñas variaciones de color forman parte de su acabado.",
            price = 9_000L,
            category = "Hogar y decoración",
            productType = "Cerámica",
            modality = "A pedido",
            managesStock = false,
        ),
    )

    fun upcomingFairs(): List<Fair> {
        val now = System.currentTimeMillis()
        fun atDayOffset(days: Long): Timestamp =
            Timestamp(Date(now + days * MILLIS_PER_DAY))

        return listOf(
            Fair(
                id = "demo-feria-costanera",
                name = "Feria de Emprendimientos de la Costanera",
                description = "Ejemplo de feria con productos de artesanía, alimentos y diseño local.",
                address = "Costanera de Puerto Montt, dirección demostrativa",
                latitude = -41.4777,
                longitude = -72.9451,
                startsAt = atDayOffset(2),
                endsAt = atDayOffset(3),
                organizer = "Organización de ejemplo BORA",
                published = true,
            ),
            Fair(
                id = "demo-feria-plaza",
                name = "Encuentro de Oficios de Puerto Montt",
                description = "Feria de muestra para explorar cómo se ven las actividades y direcciones.",
                address = "Plaza de Armas, San Martín 152, Puerto Montt (dirección demostrativa)",
                latitude = -41.4718,
                longitude = -72.9368,
                startsAt = atDayOffset(5),
                endsAt = atDayOffset(6),
                organizer = "Organización de ejemplo BORA",
                published = true,
            ),
        )
    }

    private const val MILLIS_PER_DAY = 86_400_000L
}
