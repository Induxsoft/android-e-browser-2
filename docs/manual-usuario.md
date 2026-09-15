# e-browser para Android

**Induxsoft Enterprise Browser**

---

## Qué es

e-browser es un navegador para equipos de trabajo: cajas registradoras, terminales de punto de venta, tabletas de almacén, kioscos.

Se ve y se usa como cualquier navegador, pero hace tres cosas que Chrome no puede hacer:

- **Imprime directo** en impresoras térmicas por red, Bluetooth o USB, y abre el cajón de dinero.
- **Guarda aplicaciones en el equipo** para que abran sin Internet.
- **Convierte el equipo en una terminal dedicada**, con las aplicaciones de trabajo en la pantalla de inicio.

Quien instala e-browser en un equipo está tomando una decisión deliberada: quiere esa libertad. Si no la necesita, Chrome hace mejor trabajo.

---

## La pantalla de inicio

Al encender, e-browser muestra:

- **La barra de dirección**, para escribir cualquier dirección.
- **Las aplicaciones instaladas**, cada una con su ícono y su nombre. Un toque las abre.
- **El engrane de configuración**, al pie.

Los íconos con **una nube pequeña** necesitan Internet para funcionar. Los demás abren aunque no haya conexión.

---

## Aplicaciones

### Instalar una aplicación

Escribe la dirección del sistema en la barra y ábrelo. Si es compatible con e-browser, te ofrecerá instalarse.

Verás un aviso que dice **qué aplicación** quiere instalarse y **de qué dirección viene**. Revísalo: si no reconoces la dirección, cancela.

Al aceptar, la aplicación queda en la pantalla de inicio. Si guarda sus archivos en el equipo, además abrirá sin Internet.

### Agregar un acceso directo

Para sistemas que no ofrecen instalarse solos, o para cualquier página que uses a diario:

1. Toca **Configuración**.
2. En **Aplicaciones**, toca **Agregar acceso directo**.
3. Escribe un nombre y la dirección.

Los accesos directos necesitan Internet. Sirven para tener todo a un toque sin teclear direcciones.

### Quitar una aplicación

En **Configuración → Aplicaciones**, toca **Quitar** junto a la que quieras eliminar. También puedes mantener presionado su ícono en la pantalla de inicio para llegar directo ahí.

Quitar una aplicación borra sus archivos guardados, pero no borra nada de tu sistema ni de tu servidor.

### Si una aplicación cambió de servidor

Cuando una aplicación que ya tenías instalada intenta instalarse desde una dirección distinta, e-browser te avisa y te dice **cuál tenías** y **cuál quiere reemplazarla**.

Si cambiaste de servidor o cambió la IP, es normal: acepta. Si no reconoces la nueva dirección, cancela.

### Volver a la pantalla de inicio

- El botón de retroceso del equipo.
- El botón de salir de la propia aplicación, si lo tiene.

---

## Modo operación

Cuando el equipo ya está configurado y lo va a usar personal de caja, conviene **ocultar la barra de dirección**. Así el operador solo ve las aplicaciones de trabajo y no anda tecleando direcciones durante el turno.

**Configuración → Ocultar la barra de dirección**

El engrane sigue visible al pie de la pantalla. Nadie queda encerrado: es ergonomía, no un candado.

### Abrir una aplicación al encender

Si el equipo se dedica a una sola cosa, puedes hacer que entre directo a ella:

**Configuración → Abrir una aplicación al encender**

El equipo arranca en esa aplicación. Para volver a la pantalla de inicio, usa el botón de retroceso.

### Mantener la pantalla encendida

Activada por omisión. En una caja evita que la pantalla se apague durante la operación. Si el equipo funciona con batería, considera desactivarla.

---

## Contraseña de configuración

Puedes proteger la configuración para que el personal no cambie los ajustes.

**Configuración → Contraseña → Establecer**

Una vez puesta, hay que escribirla para entrar a la configuración. Todo lo demás —abrir aplicaciones, navegar, imprimir— sigue funcionando normal.

> ### No hay forma de recuperar la contraseña
>
> Si se pierde, la única salida es **desinstalar e-browser**, lo que también borra las aplicaciones instaladas y la configuración.
>
> Esto es a propósito: no hay puerta trasera, ni para nosotros. **Anota la contraseña en un lugar seguro.**

### Alcance real de la protección

La contraseña impide que se cambien los ajustes **desde la pantalla de e-browser**. No protege contra alguien con acceso técnico al equipo, que puede borrar los datos de la aplicación desde los ajustes de Android y quedarse sin contraseña.

Es un seguro para la operación diaria, no una caja fuerte. Si el equipo necesita protección real, usa las herramientas de administración de dispositivos de Android.

---

## Impresoras

Las aplicaciones compatibles pueden imprimir directo desde e-browser, sin apps intermedias ni servicios de impresión.

| Conexión | Qué necesitas |
|---|---|
| **Red** | La impresora en la misma red, con su dirección IP y puerto (normalmente 9100) |
| **Bluetooth** | La impresora emparejada desde los ajustes de Android **antes** de usarla |
| **USB** | Cable conectado. Android pedirá permiso la primera vez: acepta |

La configuración de la impresora se hace **dentro de tu sistema**, no en e-browser. e-browser solo provee el acceso al hardware.

También puede **abrir el cajón de dinero** conectado a la impresora, si tu sistema lo solicita.

---

## Actualizaciones

Hay dos cosas que se actualizan por separado.

**La pantalla de inicio** se actualiza sola desde los servidores de Induxsoft. Cuando hay versión nueva, se descarga en segundo plano y se aplica **al siguiente encendido** del equipo, nunca a media operación.

Si algo sale mal en esa actualización, e-browser vuelve solo a la versión anterior en el arranque siguiente. La caja nunca se queda sin pantalla.

**e-browser mismo** se actualiza instalando un APK nuevo, como cualquier aplicación de Android. Solo hace falta cuando hay funciones nuevas de fondo.

### Buscar actualizaciones ahora

**Configuración → Actualizaciones → Buscar ahora**

Si encuentra una versión nueva, te dirá que se aplicará al reiniciar.

### Canal

Déjalo en **Estable**. El canal **Beta** es para equipos de prueba de Induxsoft y de distribuidores que validan versiones antes de su publicación general.

---

## Si algo no funciona

**Una aplicación no abre y dice "Sin conexión"**
Es un acceso directo y necesita Internet. Revisa la red del equipo y toca **Reintentar**. Si es una aplicación que antes abría sin red, reinstálala desde su dirección.

**La impresora no responde**
Revisa que esté encendida y con papel. Si es de red, que esté en la misma red y que la IP no haya cambiado. Si es Bluetooth, que siga emparejada en los ajustes de Android. Si es USB, desconecta y vuelve a conectar el cable.

**Aparece un aviso de "Conexión no verificada"**
El servidor usa un certificado propio. Si es un servidor de tu red, es normal: toca **Continuar**. Si no reconoces el nombre del servidor, cancela.

**No encuentro la barra de dirección**
Está oculta por configuración. Toca **Configuración** y desactiva **Ocultar la barra de dirección**.

**Olvidé la contraseña**
No hay recuperación. Hay que desinstalar y volver a instalar e-browser, y luego reinstalar las aplicaciones.

**La pantalla de inicio se ve rara o incompleta**
Apaga y enciende el equipo. Si había una actualización con problemas, e-browser vuelve solo a la versión anterior.

---

## Lo que e-browser no hace

Vale la pena decirlo claro para evitar sorpresas.

- **No restringe la navegación.** Ocultar la barra de dirección esconde el teclado de URLs, pero no bloquea a dónde puede ir el equipo. Si necesitas control real de navegación, usa las herramientas de administración de dispositivos de Android.
- **No sincroniza datos.** Guardar una aplicación en el equipo guarda sus archivos, no su información. Cómo opera tu sistema sin red y cómo sincroniza después lo decide tu sistema.
- **No respalda nada.** La configuración, las aplicaciones y la contraseña son locales. Si se restablece el equipo, se pierden.
- **No filtra qué páginas pueden usar la impresora.** Cualquier sitio que abras tiene acceso al mismo hardware que tus aplicaciones. Esa es la libertad que e-browser ofrece a propósito, y la razón por la que conviene ocultar la barra de dirección en equipos de caja.

---

*Induxsoft · Funciona mejor*
