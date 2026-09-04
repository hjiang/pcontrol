// Rewrite <time class="js-local-time" datetime="RFC3339 UTC"> text in the
// browser's local timezone. Idempotent: always reads the datetime attribute,
// never the current text, so HTMX re-polls can re-run it safely.
(function () {
  'use strict';
  function formatLocal(iso) {
    var d = new Date(iso);
    if (isNaN(d.getTime())) return null;
    // Default locale + default timezone = what the browser reports (issue #66).
    return d.toLocaleString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric',
      hour: '2-digit', minute: '2-digit'
    });
  }
  function localize(scope) {
    var nodes = (scope || document).querySelectorAll('time.js-local-time[datetime]');
    Array.prototype.forEach.call(nodes, function (el) {
      var local = formatLocal(el.getAttribute('datetime'));
      if (local !== null) el.textContent = local; // textContent only — never innerHTML
    });
  }
  function init() {
    localize(document);
    document.body.addEventListener('htmx:afterSettle', function (evt) {
      localize(evt.target && evt.target.querySelectorAll ? evt.target : document);
    });
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
