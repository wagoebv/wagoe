// Viewing the app as another user is read-only (BOU-868): every control that
// would change something is switched off and says why, on load and after each
// htmx swap. Forms that only read (GET, hx-get), the view-as bar and signing
// out stay. The server refuses any change all the same.
(function () {
  var WRITES = '[hx-post], [hx-put], [hx-patch], [hx-delete]';
  function reads(form) {
    return (form.getAttribute('method') || 'get').toLowerCase() === 'get' && !form.matches(WRITES);
  }
  function kept(el) {
    var form = el.closest('form');
    return el.closest('#view-as-bar') || (form && (form.getAttribute('action') === '/web/logout' || (reads(form) && !el.matches(WRITES))));
  }
  function off(root) {
    var title = (document.getElementById('view-as-bar') || {getAttribute: function () { return ''; }}).getAttribute('data-disabled-title') || '';
    var sel = 'form button, form input, form select, form textarea, ' + WRITES;
    var els = Array.prototype.slice.call(root.querySelectorAll(sel));
    if (root.matches && root.matches(sel)) els.push(root);
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (kept(el)) continue;
      el.setAttribute('disabled', 'disabled');
      el.setAttribute('aria-disabled', 'true');
      el.setAttribute('title', title);
      if (el.matches(WRITES)) el.setAttribute('hx-disable', '');
    }
  }
  if (!document.body || document.body.getAttribute('data-viewing-as') !== 'true') return;
  off(document);
  document.addEventListener('htmx:afterSwap', function (e) { off(e.target || document); });
})();
