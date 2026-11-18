package com.esp.bora.data

import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

data class PublicAssociation(
    val id: String = "",
    val name: String = "",
    val logoUrl: String = "",
    val description: String = "",
    val sector: String = "",
    val contact: String = "",
)

data class AssociationMember(
    val uid: String = "",
    val name: String = "",
    val businessType: String = "",
)

class PublicDirectoryRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    fun observeAssociations(): Flow<List<PublicAssociation>> = callbackFlow {
        val registration = db.collection("asociaciones")
            .whereEqualTo("active", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    close(error)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents.orEmpty().map { document ->
                    PublicAssociation(
                        id = document.id,
                        name = document.getString("name").orEmpty(),
                        logoUrl = document.getString("logoUrl").orEmpty(),
                        description = document.getString("description").orEmpty(),
                        sector = document.getString("sector").orEmpty(),
                        contact = document.getString("contact").orEmpty(),
                    )
                })
            }
        awaitClose { registration.remove() }
    }

    suspend fun getMembers(associationId: String): List<AssociationMember> =
        db.collection("asociaciones").document(associationId).collection("members")
            .get().await().documents.map { document ->
                AssociationMember(
                    uid = document.id,
                    name = document.getString("entrepreneurName").orEmpty(),
                    businessType = document.getString("businessType").orEmpty(),
                )
            }
}
