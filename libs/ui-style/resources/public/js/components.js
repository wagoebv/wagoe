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

  // Toast (shared-ui toast/toast): goes after `ms`, or when closed; 0 stays.
  window.Alpine.data('toast', function (ms) {
    return {
      init: function () {
        var self = this;
        if (ms > 0) setTimeout(function () { self.dismiss(); }, ms);
      },
      dismiss: function () { this.$root.remove(); }
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
 * Toasts the server cannot render: one carried across an HX-Redirect in the
 * X-Toast header (kept in sessionStorage until the next page loads), and one
 * an error response names in HX-Trigger {"showToast": {type, message}},
 * whose body htmx does not swap. Each fills in a template the region holds.
 * The region is hx-preserve: a swap of the body keeps it, and its toasts.
 */
(function () {
  // A swap of the whole body runs this script again: wire it once
  if (window.wagoeToastsWired) return;
  window.wagoeToastsWired = true;

  function showToast(data) {
    if (!data) return;
    // A type without a template shows as info rather than not at all
    var template = document.querySelector('#toasts template[data-toast-type="' + CSS.escape(String(data.type)) + '"]') ||
      document.querySelector('#toasts template[data-toast-type="info"]');
    if (!template) return;
    var toast = template.content.firstElementChild.cloneNode(true);
    toast.querySelector('span').textContent = data.message || '';
    template.parentNode.appendChild(toast);
  }

  // htmx:afterRequest does NOT fire before HX-Redirect (htmx sets
  // location.href first). The XHR load listener does.
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

  document.addEventListener('showToast', function (event) { showToast(event.detail); });

  // A history snapshot holds the toasts on screen when it was taken: not news on Back
  document.addEventListener('htmx:historyRestore', function () {
    document.querySelectorAll('#toasts > .alert').forEach(function (t) { t.remove(); });
  });

  // A flash waits in a template until the page is up: a live region
  // announces what is added to it, not what it was rendered with.
  function showFlash() {
    var region = document.getElementById('toasts');
    document.querySelectorAll('template[data-toast-flash]').forEach(function (t) {
      if (region) region.appendChild(t.content.cloneNode(true));
      t.remove();
    });
  }
  document.addEventListener('htmx:afterSettle', showFlash);

  document.addEventListener('DOMContentLoaded', function () {
    showFlash();
    try {
      var pending = sessionStorage.getItem('pendingToast');
      if (!pending) return;
      sessionStorage.removeItem('pendingToast');
      showToast(JSON.parse(pending));
    } catch (e) {}
  });
})();
