package com.esp.bora.data

import android.net.Uri
import com.esp.bora.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Uploads public images to the BORA Supabase bucket using the anon API key.
 * Firebase remains the app's authentication and Firestore provider.
 */
object SupabaseImageStorage {
    private const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    private const val JPEG_MIME_TYPE = "image/jpeg"

    suspend fun uploadJpeg(path: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty()) { "La imagen está vacía." }
        require(bytes.size <= MAX_IMAGE_BYTES) { "La imagen debe pesar 5 MB o menos." }
        require(bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            "La imagen debe estar en formato JPEG."
        }

        val apiKey = BuildConfig.SUPABASE_ANON_KEY
        require(apiKey.isNotBlank()) {
            "Falta configurar supabase.anonKey en local.properties."
        }

        val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
        val encodedPath = path.split('/').joinToString("/") { Uri.encode(it) }
        val objectUrl = "$baseUrl/storage/v1/object/${BuildConfig.SUPABASE_BUCKET}/$encodedPath"
        val connection = (URL(objectUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setFixedLengthStreamingMode(bytes.size)
            setRequestProperty("apikey", apiKey)
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", JPEG_MIME_TYPE)
            setRequestProperty("x-upsert", "false")
        }

        try {
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val message = when (status) {
                    401, 403 -> "Supabase rechazó la subida. Revisa la política INSERT del bucket img-bora."
                    413 -> "Supabase rechazó la imagen porque supera el límite permitido."
                    else -> "No se pudo subir la imagen a Supabase (HTTP $status)."
                }
                error(message)
            }
            "$baseUrl/storage/v1/object/public/${BuildConfig.SUPABASE_BUCKET}/$encodedPath"
        } finally {
            connection.disconnect()
        }
    }
}
