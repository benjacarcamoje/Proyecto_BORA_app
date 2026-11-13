import { initializeApp } from 'firebase/app'
import { getFirestore } from 'firebase/firestore'

// Configuración de la app web del proyecto "bora-database"
// (la misma que está en backend/Web/firebaseConfig.txt).
// Estos valores identifican el proyecto, no son una contraseña: lo que protege
// los datos son las reglas de Firestore y Storage (ver backend/firestore.rules).
const firebaseConfig = {
  apiKey: 'AIzaSyA-fvcUZIt1eH77AhNrgPoTEMZ-rVhH_c0',
  authDomain: 'bora-database.firebaseapp.com',
  projectId: 'bora-database',
  storageBucket: 'bora-database.firebasestorage.app',
  messagingSenderId: '17026152565',
  appId: '1:17026152565:web:abfcd68ff2fa112662603c',
  measurementId: 'G-783V1SEY0C',
}

const app = initializeApp(firebaseConfig)

export const db = getFirestore(app)
