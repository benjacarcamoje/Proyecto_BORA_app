const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");
const { onDocumentCreated, onDocumentUpdated, onDocumentWritten } = require("firebase-functions/v2/firestore");

initializeApp();

exports.notifyFollowersWhenStoreConfirmsFair = onDocumentUpdated(
  "fairs/{fairId}/participants/{entrepreneurUid}",
  async (event) => {
    const before = event.data?.before.data();
    const after = event.data?.after.data();
    if (!before || !after || before.status === "confirmada" || after.status !== "confirmada") return;

    const db = getFirestore();
    const fairSnapshot = await db.doc(`fairs/${event.params.fairId}`).get();
    if (!fairSnapshot.exists || fairSnapshot.get("published") !== true || fairSnapshot.get("cancelled") === true) return;

    const profileSnapshot = await db.doc(`users/${event.params.entrepreneurUid}`).get();
    const storeId = profileSnapshot.get("storeId") || event.params.entrepreneurUid;
    const storeName = profileSnapshot.get("storeName") || after.entrepreneurName || "Una tienda que sigues";
    const fairName = fairSnapshot.get("name") || "una feria";

    const followQueries = [
      db.collectionGroup("follows")
        .where("targetType", "==", "store")
        .where("targetId", "==", storeId)
        .where("notificationsEnabled", "==", true)
        .get(),
    ];
    if (after.associationId) {
      followQueries.push(
        db.collectionGroup("follows")
          .where("targetType", "==", "association")
          .where("targetId", "==", after.associationId)
          .where("notificationsEnabled", "==", true)
          .get(),
      );
    }
    const followSnapshots = await Promise.all(followQueries);
    const followDocs = followSnapshots.flatMap((snapshot) => snapshot.docs);

    const deviceSnapshots = await Promise.all(
      [...new Set(followDocs.map((follow) => follow.ref.parent.parent).filter(Boolean).map((ref) => ref.id))]
        .map((uid) => db.doc(`users/${uid}`).collection("devices").get()),
    );
    const tokenOwners = new Map();
    for (const devices of deviceSnapshots) {
      for (const device of devices.docs) {
        const token = device.get("token");
        if (typeof token === "string" && token.length > 0) tokenOwners.set(token, device.ref);
      }
    }
    const entries = [...tokenOwners.entries()];
    for (let offset = 0; offset < entries.length; offset += 500) {
      const batch = entries.slice(offset, offset + 500);
      const response = await getMessaging().sendEachForMulticast({
        tokens: batch.map(([token]) => token),
        notification: {
          title: `${storeName} participará en una feria`,
          body: `${fairName} · ${fairSnapshot.get("address") || "Puerto Montt"}`,
        },
        data: { fairId: event.params.fairId, kind: "store_fair_confirmed" },
      });
      const staleTokens = [];
      response.responses.forEach((result, index) => {
        if (!result.success && [
          "messaging/invalid-registration-token",
          "messaging/registration-token-not-registered",
        ].includes(result.error?.code)) {
          staleTokens.push(batch[index][1].delete());
        }
      });
      await Promise.all(staleTokens);
    }
  },
);

const { getAuth } = require("firebase-admin/auth");
const { onCall, HttpsError } = require("firebase-functions/v2/https");

async function requireAdmin(request) {
  const uid = request.auth?.uid;
  if (!uid) throw new HttpsError("unauthenticated", "Inicia sesión como administrador.");
  const snapshot = await getFirestore().doc(`users/${uid}`).get();
  if (snapshot.get("role") !== "admin" || snapshot.get("active") === false) {
    throw new HttpsError("permission-denied", "Esta acción requiere una cuenta administradora activa.");
  }
  return uid;
}

function cleanText(value, maxLength, field) {
  if (typeof value !== "string" || value.trim().length === 0 || value.trim().length > maxLength) {
    throw new HttpsError("invalid-argument", `${field} no es válido.`);
  }
  return value.trim();
}

exports.adminListAccounts = onCall(async (request) => {
  await requireAdmin(request);
  const snapshot = await getFirestore().collection("users")
    .where("role", "in", ["admin", "emprendedora"]).limit(100).get();
  const accounts = await Promise.all(snapshot.docs.map(async (doc) => {
    const user = await getAuth().getUser(doc.id).catch(() => null);
    return {
      uid: doc.id,
      email: doc.get("email") || user?.email || "",
      displayName: doc.get("displayName") || user?.displayName || "",
      role: doc.get("role"),
      active: doc.get("active") !== false && user?.disabled !== true,
    };
  }));
  return { accounts };
});

exports.adminCreateAccount = onCall(async (request) => {
  const actorUid = await requireAdmin(request);
  const email = cleanText(request.data?.email, 254, "El correo").toLowerCase();
  const password = request.data?.password;
  const displayName = cleanText(request.data?.displayName, 100, "El nombre");
  const role = request.data?.role;
  if (typeof password !== "string" || password.length < 10 || password.length > 128) {
    throw new HttpsError("invalid-argument", "La contraseña debe tener entre 10 y 128 caracteres.");
  }
  if (!["emprendedora", "admin"].includes(role)) {
    throw new HttpsError("invalid-argument", "El perfil solicitado no es válido.");
  }
  const auth = getAuth();
  let user;
  try {
    user = await auth.createUser({ email, password, displayName, disabled: false });
    await getFirestore().doc(`users/${user.uid}`).set({
      email, displayName, role, active: true, createdAt: new Date(),
    });
    await getFirestore().collection("adminAudit").add({
      actorUid, action: "account_created", targetType: role, targetId: user.uid,
      createdAt: new Date(),
    });
    return { uid: user.uid };
  } catch (error) {
    if (user) await auth.deleteUser(user.uid).catch(() => {});
    if (error instanceof HttpsError) throw error;
    if (error.code === "auth/email-already-exists") {
      throw new HttpsError("already-exists", "Ya existe una cuenta con ese correo.");
    }
    throw new HttpsError("internal", "No se pudo crear la cuenta.");
  }
});

exports.adminSetAccountActive = onCall(async (request) => {
  const actorUid = await requireAdmin(request);
  const uid = cleanText(request.data?.uid, 128, "El identificador");
  const active = request.data?.active;
  if (uid === actorUid) throw new HttpsError("failed-precondition", "No puedes desactivar tu propia cuenta.");
  if (typeof active !== "boolean") throw new HttpsError("invalid-argument", "El estado no es válido.");
  const db = getFirestore();
  const ref = db.doc(`users/${uid}`);
  const snap = await ref.get();
  if (!snap.exists || !["admin", "emprendedora"].includes(snap.get("role"))) {
    throw new HttpsError("not-found", "No se encontró esa cuenta.");
  }
  if (!active && snap.get("role") === "admin") {
    const admins = await db.collection("users").where("role", "==", "admin")
      .where("active", "==", true).get();
    if (admins.docs.filter((doc) => doc.id !== uid).length === 0) {
      throw new HttpsError("failed-precondition", "Debe quedar al menos un administrador activo.");
    }
  }
  await getAuth().updateUser(uid, { disabled: !active });
  await ref.update({ active, updatedAt: new Date() });
  await db.collection("adminAudit").add({
    actorUid, action: active ? "account_enabled" : "account_disabled",
    targetType: snap.get("role"), targetId: uid, createdAt: new Date(),
  });
  return { success: true };
});

async function sendPushToUsers(userIds, message) {
  const db = getFirestore();
  const deviceSnapshots = await Promise.all(
    [...new Set(userIds)].map((uid) => db.doc(`users/${uid}`).collection("devices").get()),
  );
  const tokenOwners = new Map();
  for (const devices of deviceSnapshots) {
    for (const device of devices.docs) {
      const token = device.get("token");
      if (typeof token === "string" && token.length > 0) tokenOwners.set(token, device.ref);
    }
  }
  const entries = [...tokenOwners.entries()];
  for (let offset = 0; offset < entries.length; offset += 500) {
    const batch = entries.slice(offset, offset + 500);
    const response = await getMessaging().sendEachForMulticast({
      tokens: batch.map(([token]) => token),
      notification: { title: message.title, body: message.body },
      data: message.data,
    });
    const stale = [];
    response.responses.forEach((result, index) => {
      if (!result.success && [
        "messaging/invalid-registration-token",
        "messaging/registration-token-not-registered",
      ].includes(result.error?.code)) {
        stale.push(batch[index][1].delete());
      }
    });
    await Promise.all(stale);
  }
}

exports.submitStoreContact = onCall(async (request) => {
  const data = request.data || {};
  const boundedText = (value, limit) =>
    typeof value === "string" && value.trim().length > 0 && value.length <= limit;
  if (!boundedText(data.storeId, 128) ||
      !boundedText(data.senderName, 100) ||
      typeof data.senderEmail !== "string" || data.senderEmail.length > 254 ||
      typeof data.senderPhone !== "string" || data.senderPhone.length > 32 ||
      !boundedText(data.message, 2000)) {
    throw new HttpsError("invalid-argument", "Revisa los datos y el mensaje.");
  }
  const db = getFirestore();
  const store = await db.collection("stores").doc(data.storeId).get();
  if (!store.exists || !store.get("ownerUid")) {
    throw new HttpsError("not-found", "No encontramos la tienda.");
  }
  if (!["form", "both"].includes(store.get("contactMode"))) {
    throw new HttpsError("failed-precondition", "Esta tienda no recibe mensajes por formulario.");
  }
  await db.collection("storeContacts").add({
    ownerUid: store.get("ownerUid"),
    storeId: data.storeId,
    senderName: data.senderName.trim(),
    senderEmail: data.senderEmail.trim(),
    senderPhone: data.senderPhone.trim(),
    message: data.message.trim(),
    createdAt: new Date(),
  });
  return { success: true };
});

exports.notifyEntrepreneurAboutFairInvitation = onDocumentWritten(
  "fairs/{fairId}/participants/{entrepreneurUid}",
  async (event) => {
    const before = event.data?.before.data();
    const participant = event.data?.after.data();
    if (!participant || participant.status !== "invitada" || before?.status === "invitada") return;
    const db = getFirestore();
    const [fair, account] = await Promise.all([
      db.doc(`fairs/${event.params.fairId}`).get(),
      db.doc(`users/${event.params.entrepreneurUid}`).get(),
    ]);
    if (!fair.exists || fair.get("cancelled") === true || !account.exists || account.get("active") === false) return;
    await sendPushToUsers([event.params.entrepreneurUid], {
      title: "Tienes una invitación a una feria",
      body: `${fair.get("name") || "Una feria"} · ${fair.get("address") || "Puerto Montt"}`,
      data: { fairId: event.params.fairId, kind: "fair_invitation" },
    });
  },
);

exports.adminSendFairReminder = onCall(async (request) => {
  const actorUid = await requireAdmin(request);
  const fairId = cleanText(request.data?.fairId, 128, "El identificador de la feria");
  const db = getFirestore();
  const fairRef = db.doc(`fairs/${fairId}`);
  const fair = await fairRef.get();
  if (!fair.exists || fair.get("published") !== true || fair.get("cancelled") === true) {
    throw new HttpsError("failed-precondition", "La feria debe estar publicada y vigente.");
  }
  const [pending, confirmed] = await Promise.all([
    fairRef.collection("participants").where("status", "==", "invitada").get(),
    fairRef.collection("participants").where("status", "==", "confirmada").get(),
  ]);
  const userIds = [...pending.docs, ...confirmed.docs].map((doc) => doc.id);
  await sendPushToUsers(userIds, {
    title: `Recordatorio: ${fair.get("name")}`,
    body: "Revisa tu invitación y la información de la feria.",
    data: { fairId, kind: "fair_reminder" },
  });
  await db.collection("adminAudit").add({
    actorUid,
    action: "fair_reminder_sent",
    targetType: "fair",
    targetId: fairId,
    createdAt: new Date(),
  });
  return { success: true, recipientCount: userIds.length };
});

exports.adminGetSettings = onCall(async (request) => {
  await requireAdmin(request);
  const snapshot = await getFirestore().doc("settings/app").get();
  return snapshot.exists ? snapshot.data() : {
    anonymousNavigation: "allow", productTypes: ["General"], categories: [
      "Artesanía", "Alimentos", "Textil", "Joyería",
      "Hogar y decoración", "Cosmética", "Otro",
    ],
  };
});

exports.adminSaveSettings = onCall(async (request) => {
  const actorUid = await requireAdmin(request);
  const data = request.data || {};
  const modes = ["allow", "prompt", "require_login"];
  if (!modes.includes(data.anonymousNavigation)) {
    throw new HttpsError("invalid-argument", "La política de navegación no es válida.");
  }
  const validList = (list) => Array.isArray(list) && list.length > 0 && list.length <= 30 &&
    list.every((item) => typeof item === "string" && item.trim().length > 0 && item.length <= 60);
  if (!validList(data.productTypes) || !validList(data.categories)) {
    throw new HttpsError("invalid-argument", "Revisa los tipos y categorías.");
  }
  const settings = {
    anonymousNavigation: data.anonymousNavigation,
    productTypes: [...new Set(data.productTypes.map((x) => x.trim()))],
    categories: [...new Set(data.categories.map((x) => x.trim()))],
    updatedAt: new Date(),
    updatedBy: actorUid,
  };
  const db = getFirestore();
  await db.doc("settings/app").set(settings, { merge: true });
  await db.collection("adminAudit").add({
    actorUid, action: "settings_updated", targetType: "settings",
    targetId: "app", createdAt: new Date(),
  });
  return { success: true };
});

async function sendPushToUsers(userIds, message) {
  const db = getFirestore();
  const deviceSnapshots = await Promise.all(
    [...new Set(userIds)].map((uid) => db.doc(`users/${uid}`).collection("devices").get()),
  );
  const tokenOwners = new Map();
  for (const devices of deviceSnapshots) {
    for (const device of devices.docs) {
      const token = device.get("token");
      if (typeof token === "string" && token.length > 0) tokenOwners.set(token, device.ref);
    }
  }
  const entries = [...tokenOwners.entries()];
  for (let offset = 0; offset < entries.length; offset += 500) {
    const batch = entries.slice(offset, offset + 500);
    const response = await getMessaging().sendEachForMulticast({
      tokens: batch.map(([token]) => token),
      notification: { title: message.title, body: message.body },
      data: message.data,
    });
    const stale = [];
    response.responses.forEach((result, index) => {
      if (!result.success && [
        "messaging/invalid-registration-token",
        "messaging/registration-token-not-registered",
      ].includes(result.error?.code)) {
        stale.push(batch[index][1].delete());
      }
    });
    await Promise.all(stale);
  }
}
