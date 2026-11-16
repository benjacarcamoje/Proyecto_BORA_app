package com.esp.bora.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest

class FollowRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val messaging: FirebaseMessaging = FirebaseMessaging.getInstance(),
) {
    fun observeFollowedStores(uid: String): Flow<Set<String>> = callbackFlow {
        val registration = db.collection("users").document(uid).collection("follows")
            .whereEqualTo("targetType", "store")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.getString("targetId") }.toSet())
            }
        awaitClose { registration.remove() }
    }

    suspend fun setStoreFollow(uid: String, storeId: String, following: Boolean, notificationsEnabled: Boolean) {
        require(auth.currentUser?.uid == uid) { "Inicia sesión para seguir una tienda." }
        require(storeId.isNotBlank())
        val follow = db.collection("users").document(uid).collection("follows")
            .document("store_$storeId")
        if (!following) {
            follow.delete().await()
            val remainingAlerts = db.collection("users").document(uid).collection("follows")
                .whereEqualTo("notificationsEnabled", true)
                .limit(1)
                .get()
                .await()
            if (remainingAlerts.isEmpty) {
                val devices = db.collection("users").document(uid).collection("devices").get().await()
                devices.documents.chunked(400).forEach { page ->
                    db.batch().apply { page.forEach { delete(it.reference) } }.commit().await()
                }
            }
            return
        }
        val existingFollow = follow.get().await()
        val effectiveNotifications = notificationsEnabled ||
            existingFollow.getBoolean("notificationsEnabled") == true
        follow.set(
            mapOf(
                "targetId" to storeId,
                "targetType" to "store",
                "notificationsEnabled" to effectiveNotifications,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        if (effectiveNotifications) registerCurrentDevice(uid)
    }

    fun observeFollowedAssociations(uid: String): Flow<Set<String>> = callbackFlow {
        val registration = db.collection("users").document(uid).collection("follows")
            .whereEqualTo("targetType", "association")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.getString("targetId") }.toSet())
            }
        awaitClose { registration.remove() }
    }

    fun observeAssociationNotifications(uid: String): Flow<Set<String>> = callbackFlow {
        val registration = db.collection("users").document(uid).collection("follows")
            .whereEqualTo("targetType", "association")
            .whereEqualTo("notificationsEnabled", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents.orEmpty().mapNotNull { it.getString("targetId") }.toSet())
            }
        awaitClose { registration.remove() }
    }

    suspend fun setAssociationFollow(
        uid: String,
        associationId: String,
        following: Boolean,
        notificationsEnabled: Boolean,
    ) {
        require(auth.currentUser?.uid == uid) { "Inicia sesión para seguir una asociación." }
        require(associationId.isNotBlank())
        val follow = db.collection("users").document(uid).collection("follows")
            .document("association_$associationId")
        if (!following) {
            follow.delete().await()
            val remainingAlerts = db.collection("users").document(uid).collection("follows")
                .whereEqualTo("notificationsEnabled", true)
                .limit(1)
                .get()
                .await()
            if (remainingAlerts.isEmpty) {
                val devices = db.collection("users").document(uid).collection("devices").get().await()
                devices.documents.chunked(400).forEach { page ->
                    db.batch().apply { page.forEach { delete(it.reference) } }.commit().await()
                }
            }
            return
        }
        val existingFollow = follow.get().await()
        val effectiveNotifications = notificationsEnabled ||
            existingFollow.getBoolean("notificationsEnabled") == true
        follow.set(
            mapOf(
                "targetId" to associationId,
                "targetType" to "association",
                "notificationsEnabled" to effectiveNotifications,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
        if (effectiveNotifications) registerCurrentDevice(uid)
    }

    suspend fun setAssociationNotifications(uid: String, associationId: String, enabled: Boolean) {
        require(auth.currentUser?.uid == uid)
        val follow = db.collection("users").document(uid).collection("follows")
            .document("association_$associationId")
        require(follow.get().await().exists()) { "Primero sigue esta asociación." }
        follow.update(
            mapOf("notificationsEnabled" to enabled, "updatedAt" to FieldValue.serverTimestamp()),
        ).await()
        if (enabled) registerCurrentDevice(uid)
    }

    suspend fun registerCurrentDevice(uid: String) {
        require(auth.currentUser?.uid == uid)
        registerTokenIfOptedIn(uid, messaging.token.await())
    }

    suspend fun registerTokenIfOptedIn(uid: String, token: String) {
        require(auth.currentUser?.uid == uid)
        val user = db.collection("users").document(uid).get().await()
        val isEntrepreneur = user.getString("role") == "emprendedora" && user.getBoolean("active") != false
        val optedIn = if (isEntrepreneur) true else db.collection("users").document(uid).collection("follows")
            .whereEqualTo("notificationsEnabled", true)
            .limit(1)
            .get()
            .await()
            .isEmpty.not()
        if (!optedIn || token.isBlank()) return
        val tokenId = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
        db.collection("users").document(uid).collection("devices").document(tokenId)
            .set(mapOf("token" to token, "updatedAt" to FieldValue.serverTimestamp()))
            .await()
    }
}
