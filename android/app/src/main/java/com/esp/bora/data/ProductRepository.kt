package com.esp.bora.data

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class ProductRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private companion object {
        const val PRODUCTS = "productos"
        const val USERS = "usuarios"
        const val UPLOAD_TIMEOUT_MS = 120_000L
        const val MAX_BATCH_WRITES = 450
        const val MAX_TYPE_FIELDS = 20
        const val MAX_FIELD_LABEL = 40
        const val MAX_FIELD_VALUE = 160
    }

    fun observeMyProducts(uid: String): Flow<List<Product>> = observeProducts(
        db.collection(PRODUCTS).whereEqualTo("ID_Tienda", uid),
    )

    /** El catálogo público se filtra de nuevo en el cliente por disponibilidad y stock. */
    fun observePublicProducts(): Flow<List<Product>> = observeProducts(db.collection(PRODUCTS))

    private fun observeProducts(query: com.google.firebase.firestore.Query): Flow<List<Product>> =
        callbackFlow {
            val registration = query.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                val products = snapshot?.documents
                    ?.mapNotNull { doc -> doc.toBoraProduct() }
                    ?.sortedWith(
                        compareByDescending<Product> { it.createdAt?.seconds ?: Long.MAX_VALUE }
                            .thenByDescending { it.createdAt?.nanoseconds ?: 0 },
                    )
                    ?: emptyList()
                trySend(products)
            }
            awaitClose { registration.remove() }
        }

    suspend fun saveProduct(
        product: Product,
        mainImageBytes: ByteArray?,
        variantImageBytes: Map<String, ByteArray>,
    ): String {
        val user = auth.currentUser ?: error("Tu sesión expiró. Vuelve a ingresar.")
        val storeId = resolveStoreId(user.uid)
        val document = if (product.id.isBlank()) db.collection(PRODUCTS).document()
        else db.collection(PRODUCTS).document(product.id)

        val previous = if (product.id.isBlank()) null else document.get().await()
            .toBoraProduct()
        if (product.id.isNotBlank() && previous?.ownerUid != user.uid) {
            error("Solo puedes editar tus propios productos.")
        }
        require(product.variants.size <= ProductOptions.MAX_VARIANTS) {
            "Un producto puede tener hasta ${ProductOptions.MAX_VARIANTS} variantes."
        }
        require(product.typeSpecificFields.size <= MAX_TYPE_FIELDS) {
            "Un producto puede tener hasta $MAX_TYPE_FIELDS datos específicos."
        }
        require(product.typeSpecificFields.all { (label, value) ->
            label.isNotBlank() && label.length <= MAX_FIELD_LABEL && value.length <= MAX_FIELD_VALUE
        }) { "Revisa los nombres y valores de los datos específicos." }
        val imageUrl = if (mainImageBytes != null) {
            val path = "productos/${user.uid}/${document.id}/${UUID.randomUUID()}.jpg"
            SupabaseImageStorage.uploadJpeg(path, mainImageBytes)
        } else {
            previous?.imageUrl.orEmpty()
        }

        val variants = product.variants.map { variant ->
            val bytes = variantImageBytes[variant.id]
            if (bytes == null) variant
            else {
                val path = "productos/${user.uid}/${document.id}/variantes/${variant.id}/${UUID.randomUUID()}.jpg"
                variant.copy(
                    imagePath = path,
                    imageUrl = SupabaseImageStorage.uploadJpeg(path, bytes),
                )
            }
        }
        val originalStock = if (variants.isEmpty()) {
            product.stock
        } else {
            product.stockOriginal
        }
        val saved = product.copy(
            id = document.id,
            ownerUid = user.uid,
            storeId = storeId,
            imageUrl = imageUrl,
            imagePath = "",
            stockOriginal = originalStock,
            stock = originalStock + variants.filter { it.active }.sumOf { it.stock },
            revision = (previous?.revision ?: 0) + 1,
            lastSaleId = previous?.lastSaleId.orEmpty(),
            sold = previous?.sold ?: product.sold,
            variants = variants,
            createdAt = previous?.createdAt ?: product.createdAt ?: Timestamp.now(),
        )
        val data = saved.toFirestoreMap().toMutableMap().apply {
            put("ActualizadoEn", FieldValue.serverTimestamp())
        }
        withTimeoutOrNull(UPLOAD_TIMEOUT_MS) { document.set(data).await() }
            ?: error("Tiempo de espera agotado. Revisa tu conexión e inténtalo de nuevo.")
        return document.id
    }

    suspend fun setAvailability(productIds: List<String>, available: Boolean) {
        require(auth.currentUser != null) { "Tu sesión expiró. Vuelve a ingresar." }
        val ids = productIds.filter(String::isNotBlank).distinct()
        ids.chunked(MAX_BATCH_WRITES).forEach { chunk ->
            val batch = db.batch()
            chunk.forEach { id ->
                batch.update(
                    db.collection(PRODUCTS).document(id),
                    mapOf("available" to available, "updatedAt" to FieldValue.serverTimestamp()),
                )
            }
            batch.commit().await()
        }
    }

    suspend fun deleteProduct(product: Product) {
        val uid = auth.currentUser?.uid ?: error("Tu sesión expiró. Vuelve a ingresar.")
        require(product.ownerUid == uid) { "Solo puedes eliminar tus propios productos." }
        db.collection(PRODUCTS).document(product.id).delete().await()
    }

    private suspend fun resolveStoreId(uid: String): String =
        runCatching { db.collection(USERS).document(uid).get().await().getString("storeId") }
            .getOrNull()?.takeIf(String::isNotBlank) ?: uid
}

private fun Product.toFirestoreMap(): Map<String, Any> = mapOf(
    "ID_Producto" to id,
    "ID_Tienda" to storeId,
    "Nombre" to name,
    "Categoria" to category,
    "Precio" to price,
    "Stock" to stock,
    "StockOriginal" to stockOriginal,
    "Disponible" to available,
    "imagen" to imageUrl,
    "Descripcion" to description,
    "OfrecerPedido" to (modality == "A pedido"),
    "PiezaUnica" to (modality == "Pieza única"),
    "AccionAgotado" to when (exhaustedBehavior) {
        "Ocultar" -> "ocultar"
        "A pedido" -> "pedido"
        else -> "mostrar"
    },
    "ManejaStock" to managesStock,
    "Variantes" to variants.map { variant ->
        mapOf(
            "id" to variant.id,
            "name" to variant.name,
            "price" to variant.price,
            "stock" to variant.stock,
            "imageUrl" to variant.imageUrl,
            "imagePath" to variant.imagePath,
            "active" to variant.active,
        )
    },
    "Vendido" to sold,
    "Revision" to revision,
    "UltimaVenta" to lastSaleId,
)
