# Manual del programador — e-browser

**Induxsoft Enterprise Browser para Android**
Versión del puente: **1** · Documento público

e-browser es un navegador para equipos de trabajo: cajas registradoras, terminales de punto de venta, tabletas de almacén. Da a las páginas web acceso a cosas que un navegador convencional no permite —impresoras térmicas, cajón de dinero, instalación local— porque quien lo instala en un equipo lo hace justamente para eso.

Este manual describe todo lo que una página puede hacer cuando corre dentro de e-browser.

---

## Índice

1. [Detección](#1-detección)
2. [La API en un vistazo](#2-la-api-en-un-vistazo)
3. [Autoinstalación de aplicaciones](#3-autoinstalación-de-aplicaciones) ← empieza aquí
4. [Impresión ESC/POS](#4-impresión-escpos)
5. [Referencia completa](#5-referencia-completa)
6. [Comportamiento del navegador](#6-comportamiento-del-navegador)
7. [Preguntas frecuentes](#7-preguntas-frecuentes)

---

## 1. Detección

e-browser inyecta `window.ebrowser` en **toda página**, antes de que corra tu código. No hay nada que descargar ni incluir.

```js
if (window.ebrowser) {
  // Estamos dentro de e-browser
  console.log(ebrowser.bridge);      // 1
  console.log(ebrowser.appVersion);  // "1.1.0"
}
```

Si tu código corre muy temprano y quieres estar seguro:

```js
window.addEventListener("ebrowserready", function (e) {
  const eb = e.detail;
});
```

Tu aplicación debe seguir funcionando en Chrome normal. Envuelve todo lo específico de e-browser en una comprobación y ofrece una alternativa razonable.

### Versión del puente

`ebrowser.bridge` es un entero que sube cuando se agregan métodos. Compruébalo antes de usar algo nuevo:

```js
if (ebrowser && ebrowser.bridge >= 2) {
  // usar una función introducida en la versión 2
}
```

---

## 2. La API en un vistazo

Todos los métodos devuelven **promesas**, salvo las propiedades de solo lectura.

```
ebrowser
├── isEBrowser, bridge, appVersion, platform, isLauncher, installed
├── apps
│   ├── install(opciones, onProgress)   instalar esta página como aplicación
│   ├── current()                       datos de instalación de esta página
│   ├── get(appId) · list()
│   ├── open(appId) · uninstall(appId)
│   └── exit()                          volver a la pantalla de inicio
├── nav.open(url)
├── printer                             alias de dsEscPrn (ver sección 4)
├── system.info()
├── config, lock, launcher              solo desde el lanzador
└── call(metodo, args, onProgress)      llamada cruda
```

Los errores llegan como rechazo de la promesa, con `error.code` legible:

```js
try {
  await ebrowser.apps.install({ app_id: "…" });
} catch (e) {
  if (e.code === "user_rejected") { /* el usuario canceló */ }
}
```

---

## 3. Autoinstalación de aplicaciones

Esta es la parte que más cambia cómo se construye una aplicación para e-browser, así que va con detalle.

### 3.1 El problema que resuelve

Sin esto, el usuario tiene que teclear una dirección cada vez que enciende el equipo, y tú tendrías que compilar un APK distinto por cada aplicación web solo para que abra sola.

Con `install()`, tu página se registra en la pantalla de inicio de e-browser con su nombre y su ícono. El usuario la abre con un toque. Y si declaras un `precache`, además funciona sin Internet.

### 3.2 Los dos tipos

| | **Aplicación local** | **Acceso directo** |
|---|---|---|
| Se declara con | `precache: [...]` | sin `precache` |
| Abre sin Internet | Sí | No |
| Para qué | Punto de venta que opera aislado | Sistema en la nube |
| En el lanzador | Ícono normal | Ícono con una nube pequeña |

**Es el mismo método.** e-browser deduce el tipo según le mandes o no una lista de recursos. No hay dos funciones que mantener en paralelo.

### 3.3 Ejemplo mínimo — acceso directo

Para un sistema que vive en la nube y siempre tendrá red:

```js
async function ofrecerInstalacion() {
  if (!window.ebrowser) return;                 // no estamos en e-browser
  if (await ebrowser.apps.current()) return;    // ya está instalada

  document.getElementById("btnInstalar").hidden = false;
}

document.getElementById("btnInstalar").addEventListener("click", async () => {
  try {
    await ebrowser.apps.install({
      app_id: "induxsoft.v12",
      manifest: "/manifest.json"
    });
    alert("Listo. Ya puedes abrirla desde la pantalla de inicio.");
  } catch (e) {
    if (e.code !== "user_rejected") alert("No se pudo instalar: " + e.message);
  }
});
```

### 3.4 Ejemplo completo — aplicación local

Para un punto de venta que debe seguir operando si se cae el enlace:

```js
async function instalarMiniPOS() {
  const ya = await ebrowser.apps.current();
  if (ya && ya.version === APP_VERSION) return;   // al día

  const barra = document.getElementById("barraProgreso");
  barra.hidden = false;

  await ebrowser.apps.install({
      app_id: "induxsoft.minipos",
      manifest: "/manifest.json",
      version: APP_VERSION,
      scope: "/minipos/",
      precache: PRECACHE           // generado en el build
    },
    (hechos, total) => {
      barra.value = hechos;
      barra.max = total;
    }
  );

  barra.hidden = true;
}
```

### 3.5 Parámetros

```js
ebrowser.apps.install({
  app_id:     "induxsoft.minipos",   // OBLIGATORIO
  manifest:   "/manifest.json",      // recomendado
  name:       "MiniPOS",             // si no hay manifest
  version:    "1.4.2",
  scope:      "/minipos/",
  start_url:  "/minipos/",
  precache:   ["/app.js", "/app.css"],
  user_agent: "…"                    // raro; solo si tu página lo necesita
}, onProgress)
```

#### `app_id` — lee esto

Es **la llave de identidad** de tu aplicación. Solo admite letras, números, punto, guion y guion bajo.

Usa espacio de nombres por convención: `induxsoft.minipos`, `distribuidor.producto`. Nadie lo valida, pero reduce a casi cero las colisiones accidentales entre socios, que es el escenario realista.

Como el `app_id` lo declara la propia página, cualquiera podría reclamar uno ajeno. e-browser lo maneja así:

| Situación | Qué pasa |
|---|---|
| `app_id` nuevo | Se pide autorización al usuario |
| `app_id` existente, **mismo origen** | Actualización **silenciosa**, sin diálogo |
| `app_id` existente, **origen distinto** | Se pide autorización con aviso explícito de reemplazo |

Ese último caso es el que cubre tanto el uso legítimo —cambió la IP del servidor en LAN— como el intento de secuestro: el diálogo dice exactamente qué origen tiene instalado y cuál quiere reemplazarlo.

#### `manifest`

Usa un **`manifest.json` estándar de PWA**. De ahí se toman `name`, `short_name`, `icons`, `start_url`, `scope` y `theme_color`.

```json
{
  "name": "MiniPOS",
  "short_name": "MiniPOS",
  "start_url": "/minipos/",
  "scope": "/minipos/",
  "theme_color": "#CC0000",
  "icons": [
    { "src": "/icons/192.png", "sizes": "192x192", "type": "image/png" },
    { "src": "/icons/512.png", "sizes": "512x512", "type": "image/png" }
  ]
}
```

La ventaja de usar el formato estándar es que tu aplicación sigue siendo instalable desde Chrome sin cambios. e-browser solo aporta el disparador y la pantalla de inicio.

Si el manifest no se puede leer, la instalación continúa con lo que hayas mandado en la llamada. No falla por eso.

#### `precache`

La lista de URLs que se guardan en el equipo. Rutas relativas o absolutas; se resuelven contra la página actual.

**Genérala en tu build, no a mano.** Un bundler cambia los nombres de los chunks en cada compilación y una lista escrita a mano queda desactualizada en silencio. Con Vite, por ejemplo, un plugin que lea el `manifest` de salida y escriba un `precache.js`.

Reglas que conviene conocer:

- Si un recurso no se puede descargar, **no falla la instalación**: se omite y esa URL irá a la red cuando se pida.
- El límite por recurso es 25 MB; el total por aplicación, 200 MB.
- La URL se guarda tal cual, **incluyendo el query string**. Si versionas con `?v=123`, precachea exactamente esa URL.

#### `scope`

Define qué URLs pertenecen a tu aplicación. Se usa para saber a qué app corresponde una página al navegar. Si no lo mandas, se deriva del `start_url`.

No puede salir de tu propio origen: si lo intentas, e-browser lo recorta.

### 3.6 Cómo funciona el offline

Esto es importante entenderlo porque determina qué código tienes que escribir.

**e-browser no cambia el origen de tu aplicación.** Los recursos precacheados se sirven desde disco, pero la URL que ve tu página sigue siendo la original.

Consecuencia práctica: `localStorage`, `IndexedDB` y las cookies **siguen funcionando exactamente igual**, y tus llamadas al backend no se vuelven cross-origin. No tienes que adaptar nada.

No se usan Service Workers. Muchos despliegues sirven por `http://` en red local, que no es origen seguro, y ahí los Service Workers no existen. El precache nativo funciona igual en HTTP y en HTTPS, en todos los dispositivos, sin depender de la versión del WebView.

### 3.7 Lo que sigue siendo tu responsabilidad

**La sincronización con tu backend.** e-browser cachea recursos estáticos; no encola peticiones ni reintenta llamadas a tu API. Si tu aplicación tiene que operar sin red y sincronizar después, esa lógica es tuya, con `IndexedDB` o lo que prefieras.

Si tu aplicación **necesita el backend para funcionar**, no la instales como local: instálala como acceso directo. Una app local que muestra una pantalla rota sin red es peor que un acceso directo que dice claramente "sin conexión".

### 3.8 Actualización

e-browser no comprueba versiones por su cuenta. **Tu página decide cuándo actualizarse**, que es lo correcto porque solo tú sabes si es buen momento.

El patrón habitual:

```js
window.addEventListener("load", async () => {
  if (!window.ebrowser) return;
  const inst = await ebrowser.apps.current();
  if (!inst) return;

  if (inst.version !== APP_VERSION && navigator.onLine) {
    // Mismo app_id y mismo origen: se actualiza sin molestar al usuario.
    ebrowser.apps.install({
      app_id: inst.app_id,
      manifest: "/manifest.json",
      version: APP_VERSION,
      precache: PRECACHE
    }).catch(console.warn);
  }
});
```

Como el `app_id` y el origen coinciden, **no se muestra ningún diálogo**. El usuario no se entera.

### 3.9 Salir de la aplicación

```js
ebrowser.apps.exit();   // vuelve a la pantalla de inicio
```

Úsalo en el botón "Salir" o "Cerrar sesión". El botón físico de retroceso también funciona: recorre el historial y termina en la pantalla de inicio.

---

## 4. Impresión ESC/POS

El objeto `dsEscPrn` (también accesible como `ebrowser.printer`) habla con impresoras térmicas por red, Bluetooth o USB.

Se conserva la interfaz histórica. Si ya usas `epos_prn.js` y `epos_prn_dantsu.js`, tu código sigue funcionando sin cambios.

### 4.1 Conexión

```js
// Red
dsEscPrn.setCharsetEncoding("windows-1252");
dsEscPrn.setCharsetId(16);
dsEscPrn.openPrinterTCP("192.168.1.87", 9100, 3000, 203, 48, 32, "alConectar");

// Bluetooth — usa la primera impresora emparejada
dsEscPrn.openPrinterBluetooth(203, 48, 32, "alConectar");

// USB — pide permiso al usuario la primera vez
dsEscPrn.openPrinterUSB(203, 48, 32, "alConectar");

function alConectar(r) {
  if (r.ok) console.log("Impresora lista");
  else console.error(r.error);
}
```

| Parámetro | Qué es | Valor típico |
|---|---|---|
| `prnDpi` | Resolución | `203` |
| `prnWidth` | Ancho del papel en mm | `48` (58 mm) · `72` (80 mm) |
| `prnCharPerLine` | Caracteres por línea | `32` (58 mm) · `48` (80 mm) |

El `callbackName` es **opcional** en todos los métodos. Si lo omites, el comportamiento es el de siempre.

### 4.2 Imprimir

```js
const ticket =
  "[C]<b>MI NEGOCIO</b>\n" +
  "[C]RFC XAXX010101000\n" +
  "[L]\n" +
  "[L]Refresco 600ml[R]$18.00\n" +
  "[L]Pan dulce[R]$12.50\n" +
  "[L]\n" +
  "[R]<b>TOTAL $30.50</b>\n";

dsEscPrn.printFormattedTextAndCut(ticket, "alImprimir");
dsEscPrn.printFormattedTextAndOpenCashBox(ticket, 20, "alImprimir");

function alImprimir(r) {
  if (!r.ok) alert("No se pudo imprimir: " + r.error);
}
```

Etiquetas de formato: `[L]` `[C]` `[R]` para alineación, `<b>` negritas, `<u>` subrayado, `<font size='big'>` tamaño.

### 4.3 Imágenes

```js
dsEscPrn.printImage(logoBase64, "alTenerHex", 384, 0);

function alTenerHex(hex) {
  dsEscPrn.printFormattedTextAndCut("[C]<img>" + hex + "</img>\n");
}
```

### 4.4 Estado

```js
dsEscPrn.isConnected();   // true / false
dsEscPrn.closePrinter();
```

---

## 5. Referencia completa

### `ebrowser` — propiedades

| Propiedad | Tipo | Qué es |
|---|---|---|
| `isEBrowser` | boolean | Siempre `true` |
| `bridge` | número | Versión del puente nativo |
| `appVersion` | texto | Versión del APK |
| `platform` | texto | `"android"` |
| `isLauncher` | boolean | `true` solo en la pantalla de inicio |
| `installed` | objeto \| null | Datos de instalación al cargar la página |

### `ebrowser.apps`

| Método | Devuelve | Notas |
|---|---|---|
| `install(opciones, onProgress?)` | `{ok, app}` | Ver sección 3 |
| `current()` | objeto \| null | La app correspondiente a esta página |
| `get(appId)` | objeto \| null | |
| `list()` | array | Todas las instaladas |
| `open(appId)` | `{ok}` | Navega a su `start_url` |
| `uninstall(appId)` | `{ok}` | Una app puede desinstalarse a sí misma |
| `exit()` | `{ok}` | Vuelve a la pantalla de inicio |
| `addShortcut({app_id,name,url,icon?})` | `{ok, app}` | Solo desde el lanzador |
| `reorder(ids)` | `{ok}` | Solo desde el lanzador |

### Objeto de aplicación

```js
{
  app_id: "induxsoft.minipos",
  name: "MiniPOS",
  origin: "http://192.168.1.50",
  start_url: "http://192.168.1.50/minipos/",
  scope: "http://192.168.1.50/minipos/",
  version: "1.4.2",
  kind: "local",              // "local" | "shortcut"
  theme_color: "#CC0000",
  installed_at: 1757808000000,
  updated_at: 1757808000000,
  resource_count: 42,
  bytes: 1843200
}
```

### `ebrowser.system.info()`

```js
{ iid, bridge, app_version, android_sdk, model, online }
```

`iid` es un identificador aleatorio de la instalación. No contiene nada del dispositivo ni del usuario.

### Códigos de error

| `error.code` | Significa |
|---|---|
| `user_rejected` | El usuario canceló el diálogo |
| `app_id es obligatorio` | Falta el campo |
| `Metodo desconocido: …` | El puente de este APK no lo tiene; revisa `ebrowser.bridge` |
| `… solo puede llamarse desde el lanzador` | Método restringido |

### Métodos restringidos al lanzador

`config.*`, `lock.*` y `launcher.*` solo funcionan desde la pantalla de inicio de e-browser. No es una restricción a la navegación: impide que un sitio cualquiera apague la configuración o el candado del dueño del equipo.

---

## 6. Comportamiento del navegador

Cosas que conviene saber al construir para e-browser.

**User-Agent.** Por omisión se presenta como Chrome de escritorio en Windows, por compatibilidad histórica. Si tu diseño responsivo depende del UA, tenlo en cuenta —o mejor, usa media queries. Es configurable por equipo y por aplicación.

**Contenido mixto y cleartext.** Permitidos, porque muchos despliegues sirven por `http://` en red local.

**Certificados no válidos.** Si el servidor tiene un certificado autofirmado, e-browser pregunta una vez por host y recuerda la respuesta. Normal en LAN.

**Ventanas.** `window.open` y las ventanas múltiples están deshabilitadas. Toda la navegación ocurre en la misma vista.

**Esquemas no web.** `tel:`, `mailto:`, `whatsapp:`, `intent:` y demás se entregan a la aplicación correspondiente del sistema.

**Permisos de hardware.** Cámara, micrófono y demás se conceden automáticamente. e-browser existe para dar libertad al equipo; quien lo instaló ya tomó esa decisión.

**Archivos.** `<input type="file">` abre el selector nativo y respeta `accept` y `multiple`.

**Depuración.** En compilaciones de desarrollo, el WebView es inspeccionable desde `chrome://inspect` en una computadora conectada por USB.

---

## 7. Preguntas frecuentes

**¿Tengo que cambiar mi aplicación para que funcione offline?**
No, si tu aplicación ya funciona una vez cargada. Declara el `precache` y listo: el origen no cambia, así que `localStorage` y todo lo demás siguen igual. Lo que sí es tuyo es la sincronización con tu backend.

**¿Puedo instalar una aplicación que no es mía?**
Desde el código, no: `install()` siempre registra el origen de la página que lo llama. Desde el engrane, el dueño del equipo puede agregar cualquier dirección como acceso directo.

**¿Qué pasa si dos socios usan el mismo `app_id`?**
El segundo verá un diálogo de reemplazo. Usa espacio de nombres (`tuempresa.producto`) y no ocurre.

**¿Puedo saber si hay red?**
`navigator.onLine` funciona, y `ebrowser.system.info()` devuelve `online`.

**¿Funciona mi Service Worker?**
En HTTPS sí, porque es un WebView basado en Chromium. Pero no lo necesitas para el offline de e-browser, y en `http://` de red local no estará disponible. Si lo usas, que sea como mejora, no como requisito.

**¿Mi aplicación sigue funcionando en Chrome?**
Sí, si envuelves lo específico de e-browser en `if (window.ebrowser)`. Con `manifest.json` estándar, además sigue siendo instalable como PWA.

**¿Cómo pruebo sin un equipo?**
Cualquier navegador sirve para el grueso del desarrollo. Para lo específico de e-browser necesitas el dispositivo; puedes simular el objeto `window.ebrowser` con promesas resueltas para avanzar en la interfaz.

---

## Soporte

Repositorio: `github.com/Induxsoft/android-e-browser`
