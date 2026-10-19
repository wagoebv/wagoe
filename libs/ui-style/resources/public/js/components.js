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

/**
 * Combobox (typeahead), rendered by wagoe.shared.ui.core.combobox.
 *
 * The server renders the options into the listbox; this sends the typed text
 * as the query, moves through the options with the arrow keys, picks one into
 * the hidden value input on Enter or click, and closes on Escape. A multiple
 * one (data-multiple) adds a chip per pick instead, from its chip template,
 * and leaves out the options already chosen; Backspace in the empty input
 * removes the last chip.
 */
document.addEventListener('alpine:init', function () {
  window.Alpine.data('combobox', function () {
    return {
      open: false,
      active: -1,
      // Declared, so init sets them here: an undeclared property lands on the
      // enclosing form's scope, which every combobox in it shares.
      multiple: false,
      chosen: null,
      init: function () {
        var self = this;
        this.multiple = this.$el.dataset.multiple === 'true';
        this.chosen = this.multiple ? null : { value: this.$refs.value.value, label: this.$refs.input.value };
        // The text input has no name, so htmx would send nothing. Only its
        // request: the hidden input posts the field's live validation.
        this.$el.addEventListener('htmx:configRequest', function (e) {
          if (e.detail.elt !== self.$refs.input) return;
          e.detail.parameters[self.$el.dataset.queryParam] = self.$refs.input.value;
        });
        this.$el.addEventListener('htmx:afterSwap', function () {
          if (self.multiple) {
            var picked = self.values();
            self.$refs.listbox.querySelectorAll('[role="option"][data-value]').forEach(function (el) {
              if (picked.indexOf(el.dataset.value) >= 0) el.remove();
            });
          }
          self.active = -1;
          self.open = document.activeElement === self.$refs.input;
          self.highlight();
        });
      },
      items: function () {
        return Array.prototype.slice.call(
          this.$refs.listbox.querySelectorAll('[role="option"][data-value]'));
      },
      values: function () {
        return Array.prototype.map.call(
          this.$refs.chips.querySelectorAll('.link-chip'), function (el) { return el.dataset.value; });
      },
      // Typed text no longer names the chosen row.
      typed: function () {
        if (!this.multiple) this.$refs.value.value = '';
      },
      remove: function (e) {
        e.target.closest('.link-chip').remove();
        this.$el.dispatchEvent(new Event('change', { bubbles: true }));
        this.$refs.input.focus();
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
            } else {
              this.settle();
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
          case 'Backspace':
            if (this.multiple && !this.$refs.input.value) {
              var chips = this.$refs.chips.querySelectorAll('.link-chip');
              if (chips.length) {
                chips[chips.length - 1].remove();
                this.$el.dispatchEvent(new Event('change', { bubbles: true }));
              }
            }
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
        if (this.multiple) {
          if (this.values().indexOf(el.dataset.value) < 0) {
            var chip = this.$refs.chip.content.firstElementChild.cloneNode(true);
            chip.dataset.value = el.dataset.value;
            chip.querySelector('.link-chip-label').textContent = el.dataset.label;
            chip.querySelector('input').value = el.dataset.value;
            this.$refs.chips.appendChild(chip);
          }
          this.$refs.input.value = '';
          this.close();
          this.$el.dispatchEvent(new Event('change', { bubbles: true }));
          return;
        }
        this.chosen = { value: el.dataset.value, label: el.dataset.label };
        this.$refs.value.value = el.dataset.value;
        this.$refs.input.value = el.dataset.label;
        this.close();
        this.$refs.value.dispatchEvent(new Event('change', { bubbles: true }));
      },
      // Left without a pick: back to the chosen row, so the text shown is what
      // the form submits. Emptied text clears the value.
      settle: function () {
        // Text typed into a multiple one is a query only
        if (this.multiple) {
          this.$refs.input.value = '';
          return;
        }
        if (!this.$refs.value.value && this.$refs.input.value.trim()) {
          this.$refs.value.value = this.chosen.value;
          this.$refs.input.value = this.chosen.label;
        }
      },
      close: function () {
        this.open = false;
        this.active = -1;
        this.highlight();
        this.settle();
      }
    };
  });
});
