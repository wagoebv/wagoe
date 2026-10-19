/**
 * Shared Alpine.js component registrations.
 *
 * Required by all JS bundles that use the Alpine CSP build because the CSP
 * build does not support inline x-data expressions (e.g. x-data="{open:false}").
 * Components referenced by shared UI (e.g. main-navigation dropdown) must be
 * registered here so they are available to base, pilot, and admin-pilot bundles.
 *
 * Admin-only components (sidebar store, bulkSelection, etc.) remain in admin-ux.js.
 */
document.addEventListener('alpine:init', function () {
  // Dropdown: manages open/close state with click-outside and escape key support.
  // Used by main-navigation in all page layouts.
  window.Alpine.data('dropdown', function () {
    return {
      open: false,
      toggle: function () { this.open = !this.open; },
      close: function () { this.open = false; }
    };
  });
});

/**
 * Modal: wagoe.shared.ui.core.modal. The native <dialog> closes itself on Esc,
 * backdrop and close button, traps focus and returns it; this only opens it.
 */
(function () {
  function show(dialog) {
    if (dialog && !dialog.open) dialog.showModal();
  }

  // A trigger: [data-modal-open="<dialog id>"]
  document.addEventListener('click', function (event) {
    var trigger = event.target.closest && event.target.closest('[data-modal-open]');
    if (trigger) show(document.getElementById(trigger.getAttribute('data-modal-open')));
  });

  // A dialog an htmx swap brought in, rendered with :open?
  document.addEventListener('htmx:load', function (event) {
    var root = event.detail.elt;
    if (root.matches('dialog[data-modal-show]')) show(root);
    root.querySelectorAll('dialog[data-modal-show]').forEach(show);
  });

  // Confirm: the request waits for the dialog's answer instead of window.confirm
  document.addEventListener('htmx:confirm', function (event) {
    var id = event.detail.elt.getAttribute('data-confirm-modal');
    if (!id) return;
    event.preventDefault();
    var dialog = document.getElementById(id);
    // No dialog to ask, or it is already asking: send nothing
    if (!dialog) {
      console.error('No confirm dialog #' + id + '; request not sent');
      return;
    }
    if (dialog.open) return;
    dialog.returnValue = '';
    dialog.addEventListener('close', function () {
      if (dialog.returnValue === 'confirm') event.detail.issueRequest(true);
    }, { once: true });
    show(dialog);
  });
})();

/**
 * Cross-page toast persistence via X-Toast response header + sessionStorage.
 *
 * Works on ALL page layouts (base, pilot, admin-pilot) because components.js
 * is included in every bundle. The toast DISPLAY is handled by admin-ux.js
 * (showToast) on admin pages, or by this script's simple fallback on non-admin pages.
 *
 * Flow:
 * 1. HTMX POST → server returns X-Toast header + HX-Redirect
 * 2. htmx:afterRequest fires BEFORE the redirect navigates away
 * 3. This listener reads X-Toast from XHR and stores in sessionStorage
 * 4. After page load, pending toast is shown via AdminUX.showToast or fallback
 */
(function () {
  // Intercept XHR responses to capture X-Toast header before HTMX redirects.
  // htmx:afterRequest does NOT fire before HX-Redirect (HTMX does location.href
  // before dispatching the event). The XHR load listener fires reliably.
  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function () {
    this.addEventListener('load', function () {
      var toast = this.getResponseHeader('X-Toast');
      if (toast) {
        try { sessionStorage.setItem('pendingToast', toast); } catch (e) {}
      }
    });
    return origOpen.apply(this, arguments);
  };

  // Show pending toast on page load.
  // Uses setTimeout(0) to defer until after ALL DOMContentLoaded handlers have run,
  // ensuring AdminUX.showToast (registered in admin-ux.js DOMContentLoaded) is available.
  document.addEventListener('DOMContentLoaded', function () {
    setTimeout(function () {
      try {
        var pending = sessionStorage.getItem('pendingToast');
        if (!pending) return;
        sessionStorage.removeItem('pendingToast');
        var data = JSON.parse(pending);

        // Use AdminUX toast system if available (admin pages), otherwise simple alert banner
        if (window.AdminUX && window.AdminUX.showToast) {
          window.AdminUX.showToast(data);
        } else {
          var el = document.createElement('div');
          el.className = 'toast-banner toast-banner-' + (data.type || 'success');
          el.textContent = data.message || '';
          el.style.cssText = 'position:fixed;top:16px;right:16px;z-index:9999;padding:12px 20px;border-radius:8px;background:#065f46;color:#fff;font-size:14px;box-shadow:0 4px 12px rgba(0,0,0,.15);transition:opacity .3s';
          document.body.appendChild(el);
          setTimeout(function () { el.style.opacity = '0'; setTimeout(function () { el.remove(); }, 300); }, 5000);
        }
      } catch (e) {}
    }, 0);
  });
})();
