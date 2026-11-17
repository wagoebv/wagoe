// Viewing the app as another user is read-only (BOU-868): every form and
// control outside the view-as bar is switched off and marked, on load and
// after each htmx swap. The server refuses any change all the same.
(function () {
  function off(root) {
    var els = root.querySelectorAll('form button, form input, form select, form textarea, button[hx-post], button[hx-put], button[hx-patch], button[hx-delete], [hx-post], [hx-put], [hx-patch], [hx-delete]');
    for (var i = 0; i < els.length; i++) {
      var el = els[i];
      if (el.closest('#view-as-bar')) continue;
      el.setAttribute('disabled', 'disabled');
      el.setAttribute('aria-disabled', 'true');
      el.classList.add('view-as-disabled');
      el.setAttribute('title', document.getElementById('view-as-bar').getAttribute('data-disabled-title') || '');
      if (el.hasAttribute('hx-post') || el.hasAttribute('hx-put') || el.hasAttribute('hx-patch') || el.hasAttribute('hx-delete')) {
        el.setAttribute('hx-disable', '');
      }
    }
  }
  if (!document.body || document.body.getAttribute('data-viewing-as') !== 'true') return;
  off(document);
  document.addEventListener('htmx:afterSwap', function (e) { off(e.target || document); });
})();
