import { useEffect, useState } from 'react'
import logo from '../../assets/logo-bora.png'
import { subscribeToProducts } from '../../repositories/productRepository'
import './Catalog.css'

const clp = new Intl.NumberFormat('es-CL', {
  style: 'currency',
  currency: 'CLP',
  maximumFractionDigits: 0,
})

function Catalog() {
  const [products, setProducts] = useState([])
  const [status, setStatus] = useState('loading') // 'loading' | 'ready' | 'error'

  useEffect(() => {
    const unsubscribe = subscribeToProducts(
      (items) => {
        setProducts(items)
        setStatus('ready')
      },
      (error) => {
        console.error('No se pudieron leer los productos:', error)
        setStatus('error')
      },
    )
    return unsubscribe
  }, [])

  return (
    <div className="catalog">
      <header className="catalog__header">
        <img className="catalog__logo" src={logo} alt="BORA" />
        <p className="catalog__tagline">Emprendimientos que conectan.</p>
      </header>

      <main className="catalog__main">
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
                <img
                  className="product__image"
                  src={product.imageUrl}
                  alt={product.name}
                  loading="lazy"
                />
                <div className="product__body">
                  <h2 className="product__name">{product.name}</h2>
                  {typeof product.price === 'number' && (
                    <p className="product__price">{clp.format(product.price)}</p>
                  )}
                  {product.description && (
                    <p className="product__description">{product.description}</p>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </main>
    </div>
  )
}

export default Catalog
