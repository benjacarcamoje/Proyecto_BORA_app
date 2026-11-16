# Activar el primer administrador de BORA

Este paso se realiza una sola vez desde Firebase Console antes de usar el panel Android.

1. Abre Authentication en el proyecto Firebase correcto y crea un usuario administrador con correo y contraseña.
2. Copia el UID de ese usuario.
3. Abre Firestore y crea el documento `users/{UID}` (el ID debe ser exactamente ese UID) con estos campos:
   - `email`: correo de la cuenta
   - `displayName`: nombre
   - `role`: `admin`
   - `active`: `true`
   - `createdAt`: timestamp
4. Inicia sesión en BORA con esa cuenta. La app consulta el perfil y muestra el panel solo si el rol es `admin`.
5. Después, crea y desactiva cuentas desde las funciones administrativas.

No agregues una opción de “convertirse en administrador” a la app. Las funciones callable comprueban el perfil administrador en cada operación. El SDK Admin de Functions tiene permisos privilegiados y evita las reglas de Firestore, por lo que esa comprobación del servidor es obligatoria.

Antes de producción, configura el proyecto local con Firebase CLI, prueba las reglas y funciones en Emulator Suite y despliega solo al proyecto confirmado. Este documento no despliega nada.
