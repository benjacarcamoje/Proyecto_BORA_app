import { useEffect, useState } from 'react'
import {
  collection,
  doc,
  getDoc,
  getDocs,
  query,
  where,
} from 'firebase/firestore'
import logo from '../../assets/logo-bora.png'
import { db } from '../../firebase/firebase'
import { subscribeToProducts } from '../../repositories/productRepository'
import './Catalog.css'

const clp = new Intl.NumberFormat('es-CL', {
  style: 'currency',
  currency: 'CLP',
  maximumFractionDigits: 0,
})

function isPublicProduct(product) {
  if (product.available === false || product.sold === true) return false
  if (product.managesStock && product.stock <= 0 && product.exhaustedBehavior === 'Ocultar') return false
  return true
}

function Catalog() {
  const [products, setProducts] = useState([])
  const [status, setStatus] = useState('loading')
  const [publicPage, setPublicPage] = useState(null)

  useEffect(() => {
    const unsubscribe = subscribeToProducts(
      (items) => {
        setProducts(items.filter(isPublicProduct))
        setStatus('ready')
      },
      (error) => {
        console.error('No se pudieron leer los productos:', error)
        setStatus('error')
      },
    )
    return unsubscribe
  }, [])

  useEffect(() => {
    let active = true
    async function loadPage() {
      const params = new URLSearchParams(window.location.search)
      const route = ['store', 'product', 'association', 'fair']
        .find((kind) => params.has(kind))
      const id = route ? params.get(route) : null
      if (!route || !id) {
        setPublicPage(null)
        return
      }
      setPublicPage({ loading: true, route })
      try {
        const snapshot = await getDoc(doc(db, route === 'product' ? 'products' : route === 'store' ? 'stores' : route === 'association' ? 'associations' : 'fairs', id))
        if (!snapshot.exists()) throw new Error('No encontramos esa página pública.')
        const data = { id: snapshot.id, ...snapshot.data() }
        let result = { loading: false, route, data }

        if (route === 'store') {
          const productDocs = await getDocs(query(collection(db, 'products'), where('storeId', '==', id)))
          result.products = productDocs.docs.map((item) => ({ id: item.id, ...item.data() })).filter(isPublicProduct)
        } else if (route === 'product') {
          const storeDoc = await getDoc(doc(db, 'stores', data.storeId))
          result.store = storeDoc.exists() ? { id: storeDoc.id, ...storeDoc.data() } : null
        } else if (route === 'association') {
          const memberDocs = await getDocs(collection(db, 'associations', id, 'members'))
          result.members = await Promise.all(memberDocs.docs.map(async (member) => {
            const storeSnapshot = await getDocs(query(collection(db, 'stores'), where('ownerUid', '==', member.id)))
            const store = storeSnapshot.docs[0]
            return {
              id: member.id,
              name: member.data().entrepreneurName || 'Emprendedora',
              store: store ? { id: store.id, ...store.data() } : null,
            }
          }))
        } else if (route === 'fair') {
          const memberDocs = await getDocs(query(
            collection(db, 'fairs', id, 'participants'),
            where('status', '==', 'confirmada'),
          ))
          result.members = await Promise.all(memberDocs.docs.map(async (member) => {
            const storeSnapshot = await getDocs(query(collection(db, 'stores'), where('ownerUid', '==', member.id)))
            const store = storeSnapshot.docs[0]
            const storeId = store?.id || member.id
            const productDocs = await getDocs(query(collection(db, 'products'), where('storeId', '==', storeId)))
            return {
              id: member.id,
              name: member.data().entrepreneurName || 'Emprendedora',
              store: store ? { id: store.id, ...store.data() } : null,
              products: productDocs.docs.map((item) => ({ id: item.id, ...item.data() })).filter(isPublicProduct).slice(0, 4),
            }
          }))
        }
        if (active) setPublicPage(result)
      } catch (error) {
        if (active) setPublicPage({ loading: false, route, error: error.message })
      }
    }
    loadPage()
    return () => { active = false }
  }, [])

  return (
    <div className="catalog">
      <header className="catalog__header">
        <a href="/" aria-label="Ir al catálogo BORA"><img className="catalog__logo" src={logo} alt="BORA" /></a>
        <p className="catalog__tagline">Emprendimientos que conectan.</p>
      </header>
      <main className="catalog__main">
        {publicPage ? (
          <PublicDetail page={publicPage} />
        ) : (
          <>
            <h1 className="catalog__title">Productos</h1>
            {status === 'loading' && <p className="catalog__state">Cargando productos…</p>}
            {status === 'error' && (
              <p className="catalog__state catalog__state--error" role="alert">
                No se pudieron cargar los productos. Revisa la conexión y las reglas de Firestore.
              </p>
            )}
            {status === 'ready' && products.length === 0 && (
              <p className="catalog__state">Todavía no hay productos publicados.</p>
            )}
            {status === 'ready' && products.length > 0 && (
              <ul className="catalog__grid">
                {products.map((product) => (
                  <li key={product.id} className="product">
                    <a href={`/?product=${encodeURIComponent(product.id)}`}>
                      <img className="product__image" src={product.imageUrl} alt={product.name} loading="lazy" />
                    </a>
                    <div className="product__body">
                      <h2 className="product__name">{product.name}</h2>
                      <p className="product__price">{clp.format(product.price || 0)}</p>
                      {product.description && <p className="product__description">{product.description}</p>}
                      {product.storeId && <a href={`/?store=${encodeURIComponent(product.storeId)}`}>Ver tienda</a>}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </>
        )}
      </main>
    </div>
  )
}

function PublicDetail({ page }) {
  if (page.loading) return <p className="catalog__state">Cargando ficha…</p>
  if (page.error) return <p className="catalog__state catalog__state--error">{page.error}</p>
  const { route, data } = page
  const title = data.name || data.title || 'BORA'
  const mapUrl = route === 'fair' && Number.isFinite(Number(data.latitude)) && Number.isFinite(Number(data.longitude))
    ? `https://www.openstreetmap.org/export/embed.html?bbox=${Number(data.longitude) - 0.01}%2C${Number(data.latitude) - 0.01}%2C${Number(data.longitude) + 0.01}%2C${Number(data.latitude) + 0.01}&layer=mapnik&marker=${data.latitude}%2C${data.longitude}`
    : null
  return (
    <section className="public-detail">
      <a href="/">← Volver al catálogo</a>
      {data.imageUrl && <img className="product__image" src={data.imageUrl} alt={title} />}
      {data.logoUrl && <img className="product__image" src={data.logoUrl} alt={title} />}
      <h1 className="catalog__title">{title}</h1>
      {route === 'product' && (
        <>
          <p>{data.showPrices === false || page.store?.showPrices === false ? 'Consultar precio' : clp.format(data.price || 0)}</p>
          <p>{data.description}</p>
          {page.store && <a href={`/?store=${encodeURIComponent(page.store.id)}`}>Visitar tienda</a>}
        </>
      )}
      {route === 'store' && (
        <>
          <p>{data.description}</p>
          <p>{data.businessType} · {data.sector}</p>
          {data.pickupAddress && <p>Punto de venta o retiro: {data.pickupAddress}</p>}
          <ul className="catalog__grid">
            {(page.products || []).map((product) => (
              <li key={product.id} className="product">
                <a href={`/?product=${encodeURIComponent(product.id)}`}><img className="product__image" src={product.imageUrl} alt={product.name} /></a>
                <div className="product__body">
                  <h2 className="product__name">{product.name}</h2>
                  <p>{data.showPrices === false ? 'Consultar precio' : clp.format(product.price || 0)}</p>
                </div>
              </li>
            ))}
          </ul>
        </>
      )}
      {route === 'association' && (
        <>
          <p>{data.description}</p>
          <p>{data.sector}</p>
          <h2>Integrantes</h2>
          {(page.members || []).map((member) => (
            <p key={member.id}>{member.name} {member.store && <>· <a href={`/?store=${encodeURIComponent(member.store.id)}`}>Ver tienda</a></>}</p>
          ))}
        </>
      )}
      {route === 'fair' && (
        <>
          <p>{data.description}</p>
          <p>{data.address} · {data.startsAt?.toDate?.().toLocaleString('es-CL')}</p>
          {mapUrl && <iframe title={`Mapa de ${title}`} src={mapUrl} width="100%" height="320" loading="lazy" style={{ border: 0 }} />}
          <a href={`https://www.openstreetmap.org/?mlat=${data.latitude}&mlon=${data.longitude}#map=16/${data.latitude}/${data.longitude}`}>Ver mapa y cómo llegar</a>
          <h2>Participantes confirmados</h2>
          {(page.members || []).map((member) => (
            <section key={member.id}>
              <h3>{member.name}</h3>
              {member.store && <a href={`/?store=${encodeURIComponent(member.store.id)}`}>Ver tienda</a>}
              <ul>{member.products.map((product) => <li key={product.id}><a href={`/?product=${encodeURIComponent(product.id)}`}>{product.name}</a></li>)}</ul>
            </section>
          ))}
        </>
      )}
    </section>
  )
}

export default Catalog
