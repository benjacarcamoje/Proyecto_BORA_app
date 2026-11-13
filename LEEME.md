# BORA — app Android + catálogo web

La emprendedora toma la foto de un producto con el celular, completa nombre y precio, y el producto
aparece en la tienda web sin recargar la página (ambos usan el mismo proyecto Firebase `bora-database`).

## Dónde va cada cosa en el repositorio

| Carpeta de este paquete | Qué hacer |
|---|---|
| `android/` | Copiarla tal cual a la raíz del repo (carpeta nueva). Ya incluye `app/google-services.json` (copia del que está en `backend/Android/`). |
| `web/` | Copiar encima de la carpeta `web/` del repo (reemplaza `package.json`, `index.html`, `src/index.css`, `src/App.jsx` y agrega `src/firebase`, `src/repositories`, `src/pages/public` y `src/assets/logo-bora.png`). Luego, dentro de `web/`, ejecutar `npm install` (no `npm ci`: se agregó la dependencia `firebase`, así que `package-lock.json` se actualiza y conviene subirlo al repo). |
| `backend/` | Copiar a `backend/` del repo: `firestore.rules`, `storage.rules` y `modelo_datos.md`. |

Archivos de la plantilla de Vite que quedan sin uso y se pueden borrar: `web/src/App.css`,
`web/src/assets/hero.png`, `react.svg` y `vite.svg`.

## Antes de probar: configurar Firebase (consola de `bora-database`)

1. **Authentication** → Método de acceso → activar **Correo electrónico/contraseña**.
2. **Firestore Database** → crear la base de datos si aún no existe → pestaña **Reglas** → pegar el contenido de `backend/firestore.rules` → Publicar.
3. **Storage** → activarlo (Firebase puede pedir el plan Blaze, que es de pago pero con cuota gratuita) → pestaña **Reglas** → pegar `backend/storage.rules` → Publicar.
4. Crear una cuenta de prueba: Authentication → Users → **Add user** (correo y contraseña). Copiar su **UID**.
5. En Firestore crear la colección `users` y un documento cuyo **ID sea ese UID**, con los campos
   `role` (string) = `emprendedora` y `storeName` (string) = el nombre que quieran.
   Sin este documento las reglas no dejan publicar productos.

## Probar

**App:** abrir la carpeta `android/` con Android Studio, esperar la sincronización de Gradle (necesita internet),
y ejecutarla en un teléfono. Ingresar con la cuenta de prueba → **Nuevo producto** → tocar el recuadro para tomar la foto →
nombre y precio → **Publicar producto**.
Si cierras la app y la vuelves a abrir, pide la huella (si el teléfono tiene una registrada) o permite entrar con contraseña.

**Web:** `cd web && npm install && npm run dev`. El producto publicado desde el celular aparece en la lista automáticamente.

## Qué incluye esta primera versión

- Inicio de sesión con correo y contraseña; huella al reabrir la app con sesión guardada (RF-AUT-01).
- Lista de los productos de la emprendedora y creación de productos con foto de la cámara (RF-PRO-01).
- Vitrina web con todos los productos publicados, en tiempo real.

**No incluye todavía:** editar o borrar productos, registro de ventas, ferias, compartir tienda, colección de tiendas
y panel de administración.

## Notas importantes

- **No pude compilar la app en el entorno donde la generé** (no tiene Android SDK), así que la primera compilación puede
  pedir pequeños ajustes. Si Android Studio sugiere actualizar versiones de Gradle o del plugin de Android, se puede aceptar.
- Tampoco pude ejecutar `npm install` ni `npm run build` en la parte web por falta de acceso al registro de paquetes.
- El proyecto no trae `gradle-wrapper.jar`; Android Studio funciona igual con `gradle-wrapper.properties`.
  Si quieren usar `./gradlew` por terminal: `gradle wrapper --gradle-version 8.9` dentro de `android/`.
- Las reglas de seguridad son una **propuesta**: conviene que quien administra el backend las revise antes de publicarlas.
- Las cuentas de emprendedoras las crea el administrador (por eso la app no tiene pantalla de registro).
