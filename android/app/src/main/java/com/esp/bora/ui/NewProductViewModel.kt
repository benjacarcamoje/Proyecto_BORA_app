package com.esp.bora.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.esp.bora.data.ImageUtils
import com.esp.bora.data.Product
import com.esp.bora.data.ProductRepository
import com.esp.bora.data.ProductVariant
import com.esp.bora.data.ProductVariantDraft
import com.esp.bora.data.StoreRepository
import com.google.firebase.auth.FirebaseAuth
import com.esp.bora.data.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ProductFieldDraft(
    val id: String = java.util.UUID.randomUUID().toString(),
    val label: String = "",
    val value: String = "",
)

data class NewProductUiState(
    val editingId: String? = null,
    val name: String = "",
    val description: String = "",
    val price: String = "",
    val category: String = "Otro",
    val productType: String = "General",
    val modality: String = "Regular",
    val managesStock: Boolean = false,
    val stock: String = "0",
    val exhaustedBehavior: String = "Mostrar agotado",
    val available: Boolean = true,
    val photoUri: Uri? = null,
    val existingImageUrl: String = "",
    val existingImagePath: String = "",
    val variants: List<ProductVariantDraft> = emptyList(),
    val typeFields: List<ProductFieldDraft> = emptyList(),
    val draftModified: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
)

class NewProductViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ProductRepository()
    private val storeRepository = StoreRepository()
    private val draftPreferences = app.getSharedPreferences("product_draft", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(restoreDraft())
    val state: StateFlow<NewProductUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.collect(::persistDraft) }
    }

    fun load(product: Product?) {
        if (product == null) {
            if (_state.value.draftModified) return
            _state.value = NewProductUiState()
            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
            viewModelScope.launch {
                runCatching { storeRepository.getPrivateSettings(uid) }
                    .onSuccess { defaults ->
                        if (_state.value.editingId == null && !_state.value.draftModified) {
                            _state.update {
                                it.copy(
                                    category = defaults.defaultProductCategory,
                                    productType = defaults.defaultProductType,
                                    modality = defaults.defaultProductModality,
                                    managesStock = defaults.defaultProductManagesStock,
                                    stock = defaults.defaultProductStock.toString(),
                                    exhaustedBehavior = defaults.defaultProductExhaustedBehavior,
                                    available = defaults.defaultProductAvailable,
                                )
                            }
                        }
                    }
            }
            return
        }
        if (_state.value.editingId == product.id &&
            (_state.value.name.isNotBlank() || _state.value.photoUri != null || _state.value.variants.isNotEmpty() || _state.value.done)
        ) return

        _state.value = NewProductUiState(
            editingId = product.id,
            name = product.name,
            description = product.description,
            price = product.price.toString(),
            category = product.category,
            productType = product.productType,
            modality = product.modality,
            managesStock = product.managesStock,
            stock = product.stock.toString(),
            exhaustedBehavior = product.exhaustedBehavior,
            available = product.available,
            existingImageUrl = product.imageUrl,
            existingImagePath = product.imagePath,
            typeFields = product.typeSpecificFields.map { (label, value) ->
                ProductFieldDraft(label = label, value = value)
            },
            variants = product.variants.map {
                ProductVariantDraft(
                    id = it.id,
                    name = it.name,
                    price = it.price.toString(),
                    stock = it.stock.toString(),
                    existingImageUrl = it.imageUrl,
                    existingImagePath = it.imagePath,
                )
            },
        )
    }

    fun onName(value: String) = _state.update { it.copy(name = value.take(80), draftModified = true, error = null) }
    fun onDescription(value: String) = _state.update { it.copy(description = value.take(500), draftModified = true, error = null) }
    fun onPrice(value: String) = _state.update { it.copy(price = value.filter(Char::isDigit).take(9), draftModified = true, error = null) }
    fun onCategory(value: String) = _state.update { it.copy(category = value, draftModified = true, error = null) }
    fun onProductType(value: String) = _state.update { it.copy(productType = value.take(60), draftModified = true, error = null) }
    fun onModality(value: String) = _state.update { it.copy(modality = value, draftModified = true, error = null) }
    fun onManagesStock(value: Boolean) = _state.update {
        it.copy(managesStock = value || it.variants.isNotEmpty(), draftModified = true, error = null)
    }
    fun onStock(value: String) = _state.update { it.copy(stock = value.filter(Char::isDigit).take(7), draftModified = true, error = null) }
    fun onExhaustedBehavior(value: String) = _state.update { it.copy(exhaustedBehavior = value, draftModified = true, error = null) }
    fun onAvailable(value: Boolean) = _state.update { it.copy(available = value, draftModified = true, error = null) }
    fun onPhotoTaken(uri: Uri?) { if (uri != null) _state.update { it.copy(photoUri = uri, draftModified = true, error = null) } }
    fun onVariantPhotoTaken(id: String, uri: Uri?) {
        if (uri != null) updateVariant(id) { it.copy(photoUri = uri) }
    }

    fun addTypeField() = _state.update {
        if (it.typeFields.size >= 20) it.copy(error = "Puedes agregar hasta 20 datos específicos.")
        else it.copy(typeFields = it.typeFields + ProductFieldDraft(), draftModified = true, error = null)
    }
    fun updateTypeFieldLabel(id: String, value: String) = updateTypeField(id) { it.copy(label = value.take(40)) }
    fun updateTypeFieldValue(id: String, value: String) = updateTypeField(id) { it.copy(value = value.take(160)) }
    fun removeTypeField(id: String) = _state.update {
        it.copy(typeFields = it.typeFields.filterNot { field -> field.id == id }, draftModified = true, error = null)
    }
    private fun updateTypeField(id: String, transform: (ProductFieldDraft) -> ProductFieldDraft) =
        _state.update { state ->
            state.copy(typeFields = state.typeFields.map { if (it.id == id) transform(it) else it }, draftModified = true, error = null)
        }

    fun addVariant() = _state.update {
        if (it.variants.size >= 10) it.copy(error = "Puedes agregar hasta 10 variantes.")
        else it.copy(managesStock = true, variants = it.variants + ProductVariantDraft(), draftModified = true, error = null)
    }
    fun removeVariant(id: String) = _state.update { state ->
        val remaining = state.variants.filterNot { it.id == id }
        state.copy(variants = remaining, managesStock = state.managesStock || remaining.isNotEmpty(), draftModified = true)
    }
    fun updateVariantName(id: String, value: String) = updateVariant(id) { it.copy(name = value.take(60)) }
    fun updateVariantPrice(id: String, value: String) =
        updateVariant(id) { it.copy(price = value.filter(Char::isDigit).take(9)) }
    fun updateVariantStock(id: String, value: String) =
        updateVariant(id) { it.copy(stock = value.filter(Char::isDigit).take(7)) }

    private fun updateVariant(id: String, transform: (ProductVariantDraft) -> ProductVariantDraft) =
        _state.update { state ->
            state.copy(variants = state.variants.map { if (it.id == id) transform(it) else it }, draftModified = true, error = null)
        }

    fun showError(message: String) = _state.update { it.copy(error = message) }

    fun reset() {
        draftPreferences.edit().remove("draft").apply()
        _state.value = NewProductUiState()
        File(getApplication<Application>().filesDir, "photos").deleteRecursively()
    }

    private fun persistDraft(state: NewProductUiState) {
        if (state.editingId != null || state.done || !state.draftModified) {
            draftPreferences.edit().remove("draft").apply()
            return
        }
        val typeFields = JSONArray().apply {
            state.typeFields.forEach { field ->
                put(JSONObject().apply {
                    put("id", field.id)
                    put("label", field.label)
                    put("value", field.value)
                })
            }
        }
        val variants = JSONArray().apply {
            state.variants.forEach { variant ->
                put(JSONObject().apply {
                    put("id", variant.id)
                    put("name", variant.name)
                    put("price", variant.price)
                    put("stock", variant.stock)
                    put("photoUri", variant.photoUri?.toString().orEmpty())
                    put("existingImageUrl", variant.existingImageUrl)
                    put("existingImagePath", variant.existingImagePath)
                })
            }
        }
        val json = JSONObject().apply {
            put("name", state.name)
            put("description", state.description)
            put("price", state.price)
            put("category", state.category)
            put("productType", state.productType)
            put("modality", state.modality)
            put("managesStock", state.managesStock)
            put("stock", state.stock)
            put("exhaustedBehavior", state.exhaustedBehavior)
            put("available", state.available)
            put("draftModified", state.draftModified)
            put("photoUri", state.photoUri?.toString().orEmpty())
            put("typeFields", typeFields)
            put("variants", variants)
        }
        draftPreferences.edit().putString("draft", json.toString()).apply()
    }

    private fun restoreDraft(): NewProductUiState = runCatching {
        val json = draftPreferences.getString("draft", null)?.let(::JSONObject) ?: return NewProductUiState()
        val variants = json.optJSONArray("variants") ?: JSONArray()
        val typeFields = json.optJSONArray("typeFields") ?: JSONArray()
        NewProductUiState(
            name = json.optString("name"),
            description = json.optString("description"),
            price = json.optString("price"),
            category = json.optString("category", "Otro"),
            productType = json.optString("productType", "General"),
            modality = json.optString("modality", "Regular"),
            managesStock = json.optBoolean("managesStock"),
            stock = json.optString("stock", "0"),
            exhaustedBehavior = json.optString("exhaustedBehavior", "Mostrar agotado"),
            available = json.optBoolean("available", true),
            draftModified = json.optBoolean(
                "draftModified",
                json.optString("name").isNotBlank() || json.optString("description").isNotBlank() ||
                    json.optString("price").isNotBlank() || json.optString("photoUri").isNotBlank() ||
                    variants.length() > 0 || json.optString("category", "Otro") != "Otro" ||
                    json.optString("productType", "General") != "General" ||
                    json.optString("modality", "Regular") != "Regular" || json.optBoolean("managesStock") ||
                    json.optString("stock", "0") != "0" ||
                    json.optString("exhaustedBehavior", "Mostrar agotado") != "Mostrar agotado" ||
                    !json.optBoolean("available", true),
            ),
            photoUri = json.optString("photoUri").takeIf(String::isNotBlank)?.let(Uri::parse),
            typeFields = (0 until typeFields.length()).mapNotNull { index ->
                typeFields.optJSONObject(index)?.let { item ->
                    ProductFieldDraft(
                        id = item.optString("id"),
                        label = item.optString("label"),
                        value = item.optString("value"),
                    )
                }
            },
            variants = (0 until variants.length()).mapNotNull { index ->
                variants.optJSONObject(index)?.let { item ->
                    ProductVariantDraft(
                        id = item.optString("id"),
                        name = item.optString("name"),
                        price = item.optString("price"),
                        stock = item.optString("stock", "0"),
                        photoUri = item.optString("photoUri").takeIf(String::isNotBlank)?.let(Uri::parse),
                        existingImageUrl = item.optString("existingImageUrl"),
                        existingImagePath = item.optString("existingImagePath"),
                    )
                }
            },
        )
    }.getOrDefault(NewProductUiState())

    fun save() {
        val current = _state.value
        if (current.saving) return
        val price = current.price.toLongOrNull()
        val stock = current.stock.toIntOrNull()
        if (current.name.isBlank()) return showError("Ingresa el nombre del producto.")
        if (current.productType.isBlank()) return showError("Ingresa el tipo de producto.")
        if (price == null || price <= 0L) return showError("Ingresa un precio válido.")
        if (current.photoUri == null && current.existingImageUrl.isBlank()) {
            return showError("Agrega una foto del producto.")
        }
        if (current.managesStock && current.variants.isEmpty() && stock == null) {
            return showError("Ingresa una cantidad de stock válida.")
        }
        val enteredTypeFields = current.typeFields.filter { it.label.isNotBlank() || it.value.isNotBlank() }
        if (enteredTypeFields.any { it.label.isBlank() }) {
            return showError("Escribe el nombre de cada dato específico.")
        }
        if (enteredTypeFields.map { it.label.trim().lowercase() }.distinct().size != enteredTypeFields.size) {
            return showError("Los nombres de los datos específicos no se pueden repetir.")
        }
        val typeSpecificFields = enteredTypeFields.associate { it.label.trim() to it.value.trim() }
        val variants = current.variants.map { draft ->
            val variantPrice = draft.price.toLongOrNull()
            val variantStock = draft.stock.toIntOrNull()
            if (draft.name.isBlank() || variantPrice == null || variantPrice <= 0L || variantStock == null) {
                return showError("Completa el nombre, precio y stock de cada variante.")
            }
            if (draft.photoUri == null && draft.existingImageUrl.isBlank()) {
                return showError("Agrega una foto para cada variante.")
            }
            ProductVariant(
                id = draft.id,
                name = draft.name.trim(),
                price = variantPrice,
                stock = variantStock,
                imageUrl = draft.existingImageUrl,
                imagePath = draft.existingImagePath,
            )
        }

        viewModelScope.launch {
            _state.update { it.copy(saving = true, error = null) }
            try {
                val app = getApplication<Application>()
                val mainBytes = current.photoUri?.let { uri ->
                    withContext(Dispatchers.IO) { ImageUtils.compressToJpeg(app, uri) }
                }
                val variantBytes = current.variants.mapNotNull { variant ->
                    variant.photoUri?.let { uri ->
                        variant.id to withContext(Dispatchers.IO) { ImageUtils.compressToJpeg(app, uri) }
                    }
                }.toMap()
                val saved = Product(
                    id = current.editingId.orEmpty(),
                    name = current.name.trim(),
                    description = current.description.trim(),
                    price = price,
                    imageUrl = current.existingImageUrl,
                    imagePath = current.existingImagePath,
                    category = current.category,
                    productType = current.productType.trim(),
                    modality = current.modality,
                    managesStock = current.managesStock || variants.isNotEmpty(),
                    stock = stock ?: 0,
                    exhaustedBehavior = current.exhaustedBehavior,
                    available = current.available,
                    variants = variants,
                    typeSpecificFields = typeSpecificFields,
                )
                repo.saveProduct(saved, mainBytes, variantBytes)
                _state.update { it.copy(saving = false, done = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.toUserMessage()) }
            }
        }
    }
}
