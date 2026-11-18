package com.esp.bora.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

enum class AccountMode { ENTREPRENEUR, BUYER, ADMIN }

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    val currentUser: FirebaseUser? get() = auth.currentUser

    val currentMode: AccountMode?
        get() = auth.currentUser?.let { user ->
            if (user.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }) AccountMode.BUYER
            else if (user.email != null) AccountMode.ENTREPRENEUR else null
        }

    suspend fun resolveCurrentMode(): AccountMode? {
        val user = auth.currentUser ?: return null
        if (user.providerData.any { it.providerId == GoogleAuthProvider.PROVIDER_ID }) return AccountMode.BUYER
        val role = db.collection("usuarios").document(user.uid).get().await().getString("role")
        return if (role == "admin") AccountMode.ADMIN else if (user.email != null) AccountMode.ENTREPRENEUR else null
    }

    suspend fun loadAnonymousNavigation(): String {
        val configured = db.collection("settings").document("app")
            .get().await().getString("anonymousNavigation")
        return configured?.takeIf { it in setOf("allow", "prompt", "require_login") } ?: "allow"
    }

    suspend fun signInEntrepreneur(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun sendPasswordResetEmail(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    suspend fun signInBuyerWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential).await()
    }

    fun signOut() = auth.signOut()
}
