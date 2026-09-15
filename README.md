# Induxsoft Enterprise Browser — Android

Navegador para equipos de trabajo (cajas, terminales de punto de venta, tabletas de almacén). Da a las páginas web acceso a impresión ESC/POS, cajón de dinero e instalación local de aplicaciones.

---

## Qué hay en este repositorio

```
src/                  Proyecto de Android Studio
  app/src/main/java/app/induxsoft/ebrowser/
    Constants.kt              Versión del puente, URLs de actualización, límites
    MainActivity.kt           WebView, interceptor, navegación, consentimiento
    apps/                     Registro e instalador de aplicaciones
    cache/                    Precache que conserva el origen
    bridge/                   Puente JS genérico + puente ESC/POS
    launcher/                 Almacén del lanzador y actualización automática
    security/                 Candado de configuración
    settings/                 Configuración persistente
    util/                     Hashing, red, MIME, identificador de instalación
  app/src/main/assets/
    ebrowser.js               API inyectada en toda página
    launcher/                 Lanzador embebido (respaldo sin Internet)

epos/                 Cliente JavaScript del puente de impresión (sin cambios)

docs/
  manual-programador.md             Público — API de e-browser
  manual-usuario.md                 Público — manual del producto
  manual-actualizacion-lanzador.md  INTERNO — no publicar
```

---

## Antes de compilar

Esta versión sube el proyecto de AGP 7.3 a 8.6 y de Java 8 a 17. Ese salto toca todo el proyecto, no solo el código nuevo, así que espera ajustes menores la primera vez que lo abras.

| | Antes | Ahora | Por qué |
|---|---|---|---|
| Gradle | 8.5 | 8.9 | Requerido por AGP 8.6 |
| AGP | 7.3.1 | 8.6.1 | Requerido para compileSdk 35 |
| Kotlin | 1.7.20 | 1.9.24 | Compatibilidad con AGP 8 |
| Java | 8 | 17 | AGP 8 lo exige |
| compileSdk / targetSdk | 32 | 35 | Requisito de Google Play |
| minSdk | 21 | 21 | Sin cambio |

**Android Studio necesita JDK 17.** Si falla con un error de versión de Java, revísalo en *Settings → Build Tools → Gradle → Gradle JDK*.

**Dependencia nueva:** `androidx.webkit:webkit:1.11.0`. Es necesaria para `WebViewAssetLoader` —que da al lanzador un origen estable— y para `addDocumentStartJavaScript`, que inyecta `ebrowser.js` antes de que corra el código de la página.

---

## Qué cambió respecto de la versión anterior

### Funciones nuevas

- **Instalación de aplicaciones.** Una página llama a `ebrowser.apps.install()` y queda en la pantalla de inicio con su ícono. Con `precache` abre sin Internet; sin él queda como acceso directo.
- **Lanzador en HTML.** Reemplaza `browser.html` como pantalla de inicio: rejilla de aplicaciones, barra de dirección y engrane de configuración.
- **Actualización automática del lanzador** por red, con commit en dos fases y reversión automática. Ver `docs/manual-actualizacion-lanzador.md`.
- **Modo operación.** Oculta la barra de dirección, con contraseña opcional.
- **Pantalla propia de error** en vez de la del WebView, servida desde el lanzador.

### Correcciones al código existente

| Problema | Efecto que tenía |
|---|---|
| `shouldOverrideUrlLoading` devolvía `true` y hacía `loadUrl` | Convertía los POST de formulario en GET (se perdía el body), rompía redirecciones 30x y esquemas `tel:`, `mailto:`, `intent:` |
| `openPrinterUSB` leía `usbReady` justo después de `requestPermission()` | El permiso es asíncrono y el dispositivo era siempre `null`: la conexión USB nunca funcionó |
| `Toast` fuera del hilo de UI en `printFormattedTextAndOpenCashBox` | Crash cuando la impresora no estaba conectada |
| `onConsoleMessage` devolvía `true` sin registrar | Depuración a ciegas |
| Sin `onReceivedSslError` | Los certificados autofirmados de servidores en LAN fallaban en silencio |
| Selector de archivos fijo en `image/*` | Ignoraba el `accept` y el `multiple` de la página |
| `onActivityResult` y `onBackPressed` obsoletos | Reemplazados por `ActivityResultLauncher` y `OnBackPressedDispatcher` |

El puente de impresión **conserva todos sus nombres y firmas**. El código que ya usa `epos_prn.js` y `epos_prn_dantsu.js` sigue funcionando sin cambios; el `callbackName` es opcional en todos los métodos.

---

## Decisiones de diseño que conviene conocer

**El origen no cambia.** Los recursos precacheados se sirven desde disco pero la URL que ve la página sigue siendo la original. Por eso `localStorage`, `IndexedDB` y las cookies sobreviven a la instalación, y el backend no se vuelve cross-origin.

**No se usan Service Workers.** Muchos despliegues sirven por `http://` en red local, que no es origen seguro. El precache nativo funciona igual en HTTP y HTTPS, en todos los dispositivos, sin depender de la versión del WebView instalado.

**No hay lista blanca de orígenes.** Cualquier página puede pedir instalarse y cualquier página tiene acceso al puente de impresión. Quien instala e-browser en un equipo toma esa decisión deliberadamente; si no la quiere, Chrome hace mejor trabajo. La contención se ofrece como ergonomía —ocultar la barra de dirección— no como corral.

**Los bundles del lanzador no van firmados.** Decisión explícita: la ceremonia de custodia de llaves no se justifica todavía. La integridad descansa en TLS. Si algún día se activa, hará falta publicar un APK con las llaves públicas y convivir un tiempo con parque mixto.

**El diálogo de instalación es nativo, no HTML.** Si lo dibujara la propia página, cualquier sitio podría falsificarlo.

**`config.*`, `lock.*` y `launcher.*` solo funcionan desde el lanzador.** No es restringir la navegación: impide que un sitio cualquiera apague el candado del dueño.

---

## Versión del puente

`Constants.BRIDGE_VERSION` se incrementa **cada vez** que se agrega, cambia o elimina un método expuesto al JavaScript. El lanzador descargado declara `min_bridge` en su `bundle.json`; si este APK expone menos, la actualización se ignora y el equipo se queda donde está.

Olvidar subirlo se manifiesta como una pantalla en blanco en las cajas con APK viejo. La tabla de correspondencia está en `docs/manual-actualizacion-lanzador.md`, sección 3.

| APK | `BRIDGE_VERSION` |
|---|---|
| 1.1.0 | 1 |

---

## Estado de la verificación

El código se revisó estructuralmente y por lectura. **No se compiló**: el entorno donde se produjo no tiene el SDK de Android. Prueba en un dispositivo real antes de distribuir, con atención a:

- Conexión de impresora por USB, Bluetooth y red
- Instalación de una app local sobre `http://` en LAN, y su apertura sin red
- Que una app instalada conserve su `localStorage` tras la instalación
- El ciclo de actualización del lanzador con un servidor de pruebas (`update_base` es configurable desde el engrane)
