// Minimal JS shim. The real bootstrap lives in boot.cljs and is
// evaluated inside Scittle. This file exists only because we need
// JavaScript to bridge the gap between scittle.js loading and the
// first eval_string call.
(function () {
  "use strict";
  var cfg = window.__SUPA_CONFIG__;
  var url = cfg.url + "/storage/v1/object/public/shell/boot.cljs";
  fetch(url)
    .then(function (r) {
      if (!r.ok) throw new Error("boot.cljs fetch -> HTTP " + r.status);
      return r.text();
    })
    .then(function (src) { scittle.core.eval_string(src); })
    .catch(function (e) {
      console.error(e);
      document.getElementById("app").innerHTML =
        '<div style="color:#b00020;font-family:ui-monospace,monospace">' +
        'Boot failed: ' + (e && e.message ? e.message : e) + '</div>';
    });
})();
