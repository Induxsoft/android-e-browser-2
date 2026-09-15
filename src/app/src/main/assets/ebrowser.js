/*!
 * e-browser — API de pagina
 * Induxsoft Enterprise Browser para Android
 *
 * Este archivo lo inyecta e-browser en TODA pagina, antes de que corra el
 * codigo del sitio. No hay que incluirlo ni descargarlo: si `window.ebrowser`
 * existe, la pagina se esta ejecutando dentro de e-browser.
 *
 * Documentacion: docs/manual-programador.md
 */
(function () {
  "use strict";

  if (window.ebrowser) return;
  var N = window.__ebNative;
  if (!N) return;

  var seq = 0;
  var pending = {};

  // El nativo entrega aqui tanto el progreso como el resultado final.
  window.__ebResolve = function (callId, json) {
    var p = pending[callId];
    if (!p) return;
    var data;
    try { data = JSON.parse(json); } catch (e) { data = { ok: false, error: "bad_json" }; }

    if (data && data.__progress) {
      if (typeof p.onProgress === "function") {
        try { p.onProgress(data.done, data.total); } catch (e) {}
      }
      return;
    }
    delete pending[callId];
    if (data && data.ok === false) {
      var err = new Error(data.error || "error");
      err.code = data.error;
      err.detail = data;
      p.reject(err);
    } else {
      p.resolve(data);
    }
  };

  function call(method, args, onProgress) {
    return new Promise(function (resolve, reject) {
      var id = "eb" + (++seq) + "_" + Date.now();
      pending[id] = { resolve: resolve, reject: reject, onProgress: onProgress };
      try {
        N.call(method, JSON.stringify(args || {}), id);
      } catch (e) {
        delete pending[id];
        reject(e);
      }
    });
  }

  var info = {};
  try { info = JSON.parse(N.info()) || {}; } catch (e) {}

  var api = {

    /** Siempre true dentro de e-browser. Sirve como deteccion. */
    isEBrowser: true,

    /** Version del puente nativo de este APK. */
    bridge: info.bridge || 0,

    /** Version del APK instalado. */
    appVersion: info.app_version || "",

    platform: "android",

    /** true si esta pagina es el lanzador. */
    isLauncher: !!info.is_launcher,

    /** Datos de instalacion de ESTA pagina en el momento de la carga, o null. */
    installed: info.installed || null,

    /** Llamada cruda; util para metodos nuevos aun no envueltos. */
    call: call,

    apps: {
      /**
       * Instala o actualiza esta pagina como aplicacion.
       * Con `precache` queda como aplicacion local (abre sin Internet);
       * sin `precache` queda como acceso directo.
       */
      install: function (options, onProgress) {
        return call("apps.install", options || {}, onProgress);
      },
      /** Datos de la app instalada que corresponde a esta pagina, o null. */
      current: function () {
        return call("apps.current", {}).then(function (r) { return r.app; });
      },
      get: function (appId) {
        return call("apps.get", { app_id: appId }).then(function (r) { return r.app; });
      },
      list: function () {
        return call("apps.list", {}).then(function (r) { return r.apps; });
      },
      uninstall: function (appId) { return call("apps.uninstall", { app_id: appId }); },
      open: function (appId) { return call("apps.open", { app_id: appId }); },
      /** Vuelve al lanzador. */
      exit: function () { return call("apps.exit", {}); },
      /** Solo desde el lanzador. */
      addShortcut: function (o) { return call("apps.addShortcut", o); },
      reorder: function (order) { return call("apps.reorder", { order: order }); }
    },

    nav: {
      open: function (url) { return call("nav.open", { url: url }); }
    },

    config: {
      get: function () { return call("config.get", {}); },
      set: function (patch) { return call("config.set", patch || {}); }
    },

    lock: {
      status: function () { return call("lock.status", {}); },
      unlock: function (pwd) { return call("lock.unlock", { password: pwd }); },
      set: function (pwd, current) {
        return call("lock.set", { password: pwd, current: current || "" });
      },
      clear: function (pwd) { return call("lock.clear", { password: pwd || "" }); },
      relock: function () { return call("lock.relock", {}); }
    },

    launcher: {
      /** Confirma que el lanzador arranco bien (commit en dos fases). */
      ok: function () { return call("launcher.ok", {}); },
      info: function () { return call("launcher.info", {}); },
      check: function (force) { return call("launcher.check", { force: force !== false }); },
      discardPending: function () { return call("launcher.discardPending", {}); }
    },

    system: {
      info: function () { return call("system.info", {}).then(function (r) { return r.info; }); }
    },

    printer: window.dsEscPrn || null
  };

  window.ebrowser = api;

  // Aviso a la pagina, por si se cargo antes de que el shim existiera.
  try {
    window.dispatchEvent(new CustomEvent("ebrowserready", { detail: api }));
  } catch (e) {}
})();
