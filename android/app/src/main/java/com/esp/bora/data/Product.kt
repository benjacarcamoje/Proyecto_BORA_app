package com.esp.bora.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

/** Estados persistidos en Firestore. */
object ProductOptions {
    const val MAX_VARIANTS = 5

    val categories = listOf(
        "Artesanía", "Alimentos", "Textil", "Joyería",
        "Hogar y decoración", "Cosmética", "Otro",
    )
    val modalities = listOf("Regular", "A pedido", "Pieza única")
    val exhaustedBehaviors = listOf("Mostrar agotado", "Ocultar", "A pedido")
}

data class ProductVariant(
    val id: String = "",
    val name: String = "",
    val price: Long = 0L,
    val stock: Int = 0,
    val imageUrl: String = "",
    val imagePath: String = "",
    val active: Boolean = true,
)

data class Product(
    val id: String = "",
    val storeId: String = "",
    val ownerUid: String = "",
    val name: String = "",
    val description: String = "",
    /** Precio en pesos chilenos, sin decimales. */
    val price: Long = 0L,
    val imageUrl: String = "",
    val imagePath: String = "",
    val category: String = "Otro",
    val productType: String = "General",
    val modality: String = "Regular",
    val managesStock: Boolean = false,
    /** Stock agregado que expone el contrato Firestore. */
    val stock: Int = 0,
    /** Inventario base separado de las existencias por variante. */
    val stockOriginal: Int = 0,
    val revision: Int = 1,
    val lastSaleId: String = "",
    val exhaustedBehavior: String = "Mostrar agotado",
    val available: Boolean = true,
    val sold: Boolean = false,
    val variants: List<ProductVariant> = emptyList(),
    val typeSpecificFields: Map<String, String> = emptyMap(),
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
) {
    val totalStock: Int
        get() = if (stock > 0 || stockOriginal > 0 || variants.none { it.active }) {
            stock
        } else {
            stockOriginal + variants.filter { it.active }.sumOf { it.stock }
        }

    /** Regla de visibilidad para catálogos públicos y de compradores. */
    val visibleInPublicCatalog: Boolean
        get() {
            if (!available || sold) return false
            if (!managesStock || totalStock > 0) return true
            return exhaustedBehavior != "Ocultar"
        }

    val isSoldOut: Boolean
        get() = managesStock && totalStock <= 0 && exhaustedBehavior == "Mostrar agotado"

    val isMadeToOrder: Boolean
        get() = modality == "A pedido" ||
            (managesStock && totalStock <= 0 && exhaustedBehavior == "A pedido")
}

/** Datos para una línea de variante dentro del editor. */
data class ProductVariantDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val price: String = "",
    val stock: String = "0",
    val photoUri: android.net.Uri? = null,
    val existingImageUrl: String = "",
    val existingImagePath: String = "",
)

/** Maps the Spanish Firestore contract to the app model without changing stored documents. */
fun DocumentSnapshot.toBoraProduct(): Product? {
    val fields = data ?: return null
    fun string(vararg keys: String): String =
        keys.firstNotNullOfOrNull { fields[it] as? String }.orEmpty()
    fun number(vararg keys: String): Int =
        keys.firstNotNullOfOrNull { (fields[it] as? Number)?.toInt() } ?: 0
    fun long(vararg keys: String): Long =
        keys.firstNotNullOfOrNull { (fields[it] as? Number)?.toLong() } ?: 0L
    fun bool(default: Boolean, vararg keys: String): Boolean =
        keys.firstNotNullOfOrNull { fields[it] as? Boolean } ?: default

    val storeId = string("ID_Tienda", "storeId")
    val variants = ((fields["Variantes"] ?: fields["variants"]) as? List<*>)
        .orEmpty()
        .mapNotNull { raw ->
            val variant = raw as? Map<*, *> ?: return@mapNotNull null
            fun vString(key: String) = variant[key] as? String ?: ""
            fun vLong(key: String) = (variant[key] as? Number)?.toLong() ?: 0L
            fun vInt(key: String) = (variant[key] as? Number)?.toInt() ?: 0
            ProductVariant(
                id = vString("id"),
                name = vString("name"),
                price = vLong("price"),
                stock = vInt("stock"),
                imageUrl = vString("imageUrl"),
                imagePath = vString("imagePath"),
                active = variant["active"] as? Boolean ?: true,
            )
        }
    val uniquePiece = bool(false, "PiezaUnica", "uniquePiece")
    val madeToOrder = bool(false, "OfrecerPedido", "madeToOrder")
    val modality = when {
        uniquePiece -> "Pieza única"
        madeToOrder -> "A pedido"
        else -> string("modality").ifBlank { "Regular" }
    }
    val exhaustedBehavior = when (string("AccionAgotado", "exhaustedBehavior")) {
        "ocultar", "Ocultar" -> "Ocultar"
        "pedido", "A pedido" -> "A pedido"
        "mostrar", "Mostrar agotado" -> "Mostrar agotado"
        else -> "Mostrar agotado"
    }

    @Suppress("UNCHECKED_CAST")
    val typeFields = (fields["typeSpecificFields"] as? Map<String, String>).orEmpty()
    return Product(
        id = string("ID_Producto", "id").ifBlank { id },
        storeId = storeId,
        ownerUid = string("ID_Dueno", "ownerUid").ifBlank { storeId },
        name = string("Nombre", "name"),
        description = string("Descripcion", "description"),
        price = long("Precio", "price"),
        imageUrl = string("imagen", "imageUrl", "logoUrl"),
        imagePath = string("imagePath"),
        category = string("Categoria", "category").ifBlank { "Otro" },
        productType = string("TipoProducto", "productType").ifBlank { "General" },
        modality = modality,
        managesStock = bool(true, "ManejaStock", "managesStock"),
        stock = number("Stock", "stock"),
        stockOriginal = number("StockOriginal", "stockOriginal"),
        revision = number("Revision", "revision").coerceAtLeast(1),
        lastSaleId = string("UltimaVenta", "lastSaleId"),
        exhaustedBehavior = exhaustedBehavior,
        available = bool(true, "Disponible", "available"),
        sold = bool(false, "Vendido", "sold"),
        variants = variants,
        typeSpecificFields = typeFields,
        createdAt = fields["CreadoEn"] as? Timestamp ?: fields["createdAt"] as? Timestamp,
        updatedAt = fields["ActualizadoEn"] as? Timestamp ?: fields["updatedAt"] as? Timestamp,
    )
}
