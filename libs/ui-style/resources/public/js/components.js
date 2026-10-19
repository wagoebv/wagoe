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

/**
 * Combobox (typeahead), rendered by wagoe.shared.ui.core.combobox.
 *
 * The server renders the options into the listbox; this sends the typed text
 * as the query, moves through the options with the arrow keys, picks one into
 * the hidden value input on Enter or click, and closes on Escape.
 */
document.addEventListener('alpine:init', function () {
  window.Alpine.data('combobox', function () {
    return {
      open: false,
      active: -1,
      init: function () {
        var self = this;
        // The text input has no name, so htmx would send nothing.
        this.$el.addEventListener('htmx:configRequest', function (e) {
          e.detail.parameters[self.$el.dataset.queryParam] = self.$refs.input.value;
        });
        this.$el.addEventListener('htmx:afterSwap', function () {
          self.active = -1;
          self.open = document.activeElement === self.$refs.input;
          self.highlight();
        });
      },
      items: function () {
        return Array.prototype.slice.call(
          this.$refs.listbox.querySelectorAll('[role="option"][data-value]'));
      },
      // Typed text no longer names the chosen row.
      typed: function () {
        this.$refs.value.value = '';
      },
      keydown: function (e) {
        var items = this.items();
        switch (e.key) {
          case 'ArrowDown':
          case 'ArrowUp':
            e.preventDefault();
            if (!items.length) {
              this.$refs.input.dispatchEvent(new CustomEvent('combobox:open'));
              return;
            }
            this.open = true;
            // From none, ArrowUp lands on the last option.
            var step = e.key === 'ArrowDown' ? 1 : -1;
            var from = this.active < 0 && step < 0 ? 0 : this.active;
            this.active = (from + step + items.length) % items.length;
            this.highlight();
            break;
          case 'Enter':
            if (this.open && this.active >= 0) {
              e.preventDefault();
              this.pick(items[this.active]);
            }
            break;
          case 'Escape':
            if (this.open) {
              e.preventDefault();
              e.stopPropagation();
              this.close();
            }
            break;
          case 'Tab':
            this.close();
            break;
        }
      },
      highlight: function () {
        var active = this.active;
        var items = this.items();
        items.forEach(function (el, i) {
          el.setAttribute('aria-selected', i === active ? 'true' : 'false');
        });
        if (items[active]) {
          var el = items[active];
          var list = this.$refs.listbox;
          this.$refs.input.setAttribute('aria-activedescendant', el.id);
          // Scroll the list only: scrollIntoView would scroll every ancestor.
          if (el.offsetTop < list.scrollTop) {
            list.scrollTop = el.offsetTop;
          } else if (el.offsetTop + el.offsetHeight > list.scrollTop + list.clientHeight) {
            list.scrollTop = el.offsetTop + el.offsetHeight - list.clientHeight;
          }
        } else {
          this.$refs.input.removeAttribute('aria-activedescendant');
        }
      },
      choose: function (e) {
        var el = e.target.closest('[role="option"][data-value]');
        if (el) this.pick(el);
      },
      pick: function (el) {
        this.$refs.value.value = el.dataset.value;
        this.$refs.input.value = el.dataset.label;
        this.close();
        this.$refs.value.dispatchEvent(new Event('change', { bubbles: true }));
      },
      close: function () {
        this.open = false;
        this.active = -1;
        this.highlight();
      }
    };
  });
});
