import { collection, onSnapshot, orderBy, query } from 'firebase/firestore'
import { db } from '../firebase/firebase'

/**
 * Escucha la colección "products" en tiempo real, del más nuevo al más antiguo.
 * Cada vez que la app móvil publica un producto, `onData` recibe la lista actualizada.
 *
 * Devuelve la función para dejar de escuchar (útil como cleanup de useEffect).
 */
export function subscribeToProducts(onData, onError) {
  const productsQuery = query(collection(db, 'products'), orderBy('createdAt', 'desc'))

  return onSnapshot(
    productsQuery,
    (snapshot) => {
      onData(snapshot.docs.map((doc) => ({ id: doc.id, ...doc.data() })))
    },
    onError,
  )
}
