# Actualización del lanzador de e-browser

**Documento interno de Induxsoft.** No publicar.

El lanzador es la pantalla de inicio de e-browser: la rejilla de aplicaciones, la barra de dirección y el engrane de configuración. Vive en `browser.html` y se actualiza **por red, sin publicar un APK**.

Esa es la razón de ser de todo este mecanismo: el APK solo se toca cuando cambian el instalador, el interceptor de caché o el puente JavaScript. Todo lo visible del producto itera por aquí.

---

## 1. Cómo funciona, en tres párrafos

Al arrancar, e-browser consulta en segundo plano un archivo `latest.json`. Si anuncia una versión mayor a la instalada, descarga un ZIP, lo extrae en `pending/` y **no lo aplica**. En el siguiente encendido sirve ese pendiente y espera a que el propio lanzador confirme que arrancó bien llamando a `ebrowser.launcher.ok()`. Si la confirmación llega, el pendiente se promueve a `current`. Si no llega —porque el HTML tenía un error de JavaScript y nunca terminó de inicializar—, el arranque siguiente lo descarta y vuelve al anterior.

Ese commit en dos fases es la única red de seguridad real que tenemos, y hace que el peor caso de un despliegue malo sea *"las cajas se quedaron en la versión anterior"* en lugar de *"las cajas se quedaron sin pantalla"*.

La segunda red es el `rollout`: un porcentaje del parque que recibe la versión. Se sube editando un número en el JSON, sin republicar nada.

> **Nota sobre firma.** Hoy los bundles **no van firmados** por decisión explícita: la ceremonia de custodia de llaves no se justifica todavía. Eso significa que la integridad del lanzador descansa enteramente en TLS. Si algún día se activa la firma, hará falta publicar un APK con las llaves públicas embebidas y convivir un tiempo con parque mixto.

---

## 2. Estructura en el servidor

Todo son archivos estáticos en un bucket con CDN. No hay servicio ni base de datos.

```
https://ebrowser.induxsoft.net/android/launcher/stable/latest.json
https://ebrowser.induxsoft.net/android/launcher/stable/bundles/14.zip
https://ebrowser.induxsoft.net/android/launcher/stable/bundles/13.zip
https://ebrowser.induxsoft.net/android/launcher/beta/latest.json
https://ebrowser.induxsoft.net/android/launcher/beta/bundles/15.zip
```

El segmento `android/` existe para que la futura versión de Windows o iOS no obligue a migrar URLs. Va en minúsculas y fijo.

Los bundles se nombran con **el entero de versión**, no con el nombre comercial. `14.zip` es lo que compara el código; `1.4.0` es para humanos y vive dentro del manifiesto. Nombrar el archivo con el semver termina en `1.4.0.zip` y `1.4.0-fix.zip` sin saber cuál está en campo.

### Cabeceras de caché

| Ruta | `Cache-Control` | Por qué |
|---|---|---|
| `bundles/*.zip` | `public, max-age=31536000, immutable` | Nunca cambian |
| `latest.json` | `no-cache`, con `ETag` | Para que el 304 funcione y el cambio de `rollout` se propague en minutos |

Si el `latest.json` queda cacheado de forma agresiva, se pierde el control de despliegue, que es justamente el punto del mecanismo.

### No borrar bundles viejos

Nunca. Una caja apagada seis meses puede tener un `pending` a medias, y la única forma de revertir globalmente es apuntar `latest.json` a un ZIP anterior. Si se borró, no hay revert.

---

## 3. Formato de `latest.json`

```json
{
  "version": 14,
  "version_name": "1.4.0",
  "min_bridge": 1,
  "rollout": 25,
  "bundle_url": "https://ebrowser.induxsoft.net/android/launcher/stable/bundles/14.zip"
}
```

| Campo | Tipo | Qué hace |
|---|---|---|
| `version` | entero | Lo único que se compara. **Solo sube, nunca baja.** |
| `version_name` | texto | Para humanos y para mostrar en el engrane |
| `min_bridge` | entero | Versión mínima del puente nativo que este lanzador necesita |
| `rollout` | 0–100 | Porcentaje del parque que lo aplica |
| `bundle_url` | URL | Absoluta. **El cliente nunca la construye.** |

Dos campos merecen explicación.

**`bundle_url` absoluta y tomada del manifiesto** es deliberado: el día que los bundles se muevan a otro CDN, se cambia este campo y el parque completo sigue actualizándose sin tocar el APK. Si el cliente armara la ruta, quedaríamos amarrados al host original.

**`min_bridge`** es la única validación estructural que tenemos antes de aplicar un bundle. Es el campo que la gente olvida, y su olvido se manifiesta como una pantalla en blanco en las cajas con APK viejo. Cada vez que el lanzador empiece a usar un método nuevo de `window.ebrowser`, hay que subirlo.

### Tabla de correspondencia puente ↔ APK

Llevarla actualizada en este mismo documento. Suena burocrático; evita varios incidentes.

| Versión de APK | `BRIDGE_VERSION` | Métodos añadidos |
|---|---|---|
| 1.1.0 | 1 | Versión inicial del puente `window.ebrowser` |

---

## 4. Formato del bundle

Un ZIP con el lanzador en la raíz:

```
browser.html
offline.html
bundle.json
assets/…        (opcional)
```

`bundle.json`:

```json
{
  "entry": "browser.html",
  "version": 14,
  "version_name": "1.4.0",
  "min_bridge": 1
}
```

`entry` es explícito para poder renombrar el archivo principal mañana sin tocar Kotlin.

### Lo que el cliente verifica al extraer

- **Zip-slip**: rechaza cualquier entrada con `..` o ruta absoluta, y comprueba que el destino canónico quede dentro del directorio.
- **Zip-bomb**: corta si lo descomprimido supera 20 MB.
- **Bundle válido**: exige `bundle.json` con un `entry` que exista.

Si algo de esto falla, se registra en el log, se borra el temporal y se reintenta en el próximo arranque. El usuario no ve nada: no hay nada que pueda hacer al respecto y la caja sigue operando.

---

## 5. Publicar

### 5.1 Repositorio

Separado del repo de Android:

```
src/           browser.html, offline.html, assets/
bundle.json    entry + version + min_bridge
CHANGELOG.md
publish.sh
```

### 5.2 El script

Un solo comando. Los pasos manuales son donde se cuelan los errores.

1. Valida `bundle.json`: `version` mayor que la publicada, `entry` existe.
2. Valida `min_bridge` contra la tabla de la sección 3: si el HTML usa un método que no existe en esa versión de puente, **falla la publicación**.
3. Arma el ZIP de forma **determinista** — orden de entradas fijo, timestamps en cero — para que el mismo código produzca siempre el mismo hash y se pueda verificar qué se publicó.
4. Genera `latest.json`.
5. **Sube el ZIP primero. El `latest.json` al final.**

El orden del paso 5 importa: al revés hay una ventana en la que las cajas ven una versión nueva cuyo bundle todavía no existe y descargan un 404.

### 5.3 Lista de verificación de cada publicación

- [ ] `version` subida
- [ ] `min_bridge` revisado contra la tabla
- [ ] Probado en una caja real con el **APK más viejo que esté en campo**, no solo con el último
- [ ] Publicado en `beta` primero
- [ ] `rollout` inicial en 10–25
- [ ] Esperar un día y revisar el ratio de confirmaciones
- [ ] Subir a 100

---

## 6. Canales

`stable` y `beta` son directorios paralelos. El canal se elige desde el engrane y por defecto es `stable`.

El uso previsto: nuestras propias cajas y las de dos o tres distribuidores de confianza en `beta`, validación en operación real, y luego se promueve el mismo ZIP a `stable` sin volver a construir nada.

Con canales, el `rollout` pasa a ser la segunda red de seguridad más que el mecanismo principal. Conviene tener ambos: beta para cambios de fondo, rollout para todo lo demás.

---

## 7. Telemetría de confirmación

```
POST https://ebrowser.api.induxsoft.net/v1/android/launcher/ack
Content-Type: application/json
```

```json
{
  "iid": "9f2c…",
  "launcher": 14,
  "bridge": 1,
  "app": "1.1.0",
  "channel": "stable"
}
```

| Campo | Qué es |
|---|---|
| `iid` | UUID aleatorio generado en el primer arranque. No contiene nada del dispositivo ni del cliente. |
| `launcher` | Versión del lanzador que acaba de arrancar bien |
| `bridge` | `BRIDGE_VERSION` del APK |
| `app` | `versionName` del APK |
| `channel` | Canal configurado |

Se envía justo después de `launcher.ok()`. Es **best-effort**: si falla o tarda, el lanzador arranca igual. No es una dependencia.

### Para qué sirve de verdad

Sin esto, se publica y uno se entera del resultado por el teléfono de soporte.

Lo mínimo que hay que mirar tras cada despliegue: **cuántas cajas descargaron una versión y nunca la confirmaron**. Esos son exactamente los equipos que revirtieron, o sea la señal temprana de que algo salió mal. Es media tarde de trabajo del lado del servidor —un endpoint que escribe una fila— y cambia por completo la operación.

El endpoint debe responder rápido y no requerir autenticación; si se cae, no pasa nada.

---

## 8. Desarrollo y pruebas

No hay que publicar a producción para probar un cambio de CSS.

El engrane permite cambiar `update_base` (campo `update_base` de la configuración). Apuntándolo a un servidor local se itera sin tocar el CDN:

```
http://192.168.1.10:8080/android/launcher
```

Con `ebrowser.launcher.check(true)` se fuerza la comprobación ignorando el ETag, y con `ebrowser.launcher.discardPending()` se descarta un pendiente para repetir la prueba.

El bundle embebido en el APK (`assets/launcher/`) es siempre el respaldo: si `current` y `pending` faltan o están incompletos, se sirve ese. Por eso una caja recién instalada, sin Internet, arranca igual.

---

## 9. El dominio

`ebrowser.induxsoft.net` va compilado en APKs que vivirán en cajas durante años, probablemente más de lo que dure cualquier decisión de infraestructura que se tome hoy.

- El registro DNS y su certificado deben tener **dueño explícito y estar en un calendario**.
- El bucket detrás debe ser reemplazable **sin cambiar el nombre**.
- El día que ese host deje de responder, se pierde la capacidad de actualizar el parque completo y la única salida es redistribuir APK.

---

## 10. Revertir

1. Editar `latest.json` para que `version` y `bundle_url` apunten al bundle anterior.

   Pero `version` solo sube, así que **no se baja el número**: se publica el contenido viejo con un número nuevo. Ejemplo: si la 14 salió mal, se republica el ZIP de la 13 como `15.zip` con `"version": 15`.

2. `rollout` en 100 para que llegue a todos de inmediato.

3. Las cajas lo aplican en su siguiente encendido.

Las que ya habían fallado con la 14 revierten solas por el commit en dos fases; esto es para las que la aplicaron con éxito pero tiene un problema que no impide arrancar.
