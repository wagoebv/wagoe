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
 * Toasts the server cannot render: one carried across an HX-Redirect in the
 * X-Toast header (kept in sessionStorage until the next page loads), and one
 * an error response names in HX-Trigger {"showToast": {type, message}},
 * whose body htmx does not swap. Each fills in a template the region holds.
 */
(function () {
  // A swap of the whole body runs this script again: wire it once
  if (window.wagoeToastsWired) return;
  window.wagoeToastsWired = true;

  function showToast(data) {
    var template = data && document.querySelector(
      '#toasts template[data-toast-type="' + (data.type || 'info') + '"]');
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

  document.addEventListener('DOMContentLoaded', function () {
    try {
      var pending = sessionStorage.getItem('pendingToast');
      if (!pending) return;
      sessionStorage.removeItem('pendingToast');
      showToast(JSON.parse(pending));
    } catch (e) {}
  });
})();
