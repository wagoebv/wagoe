/**
 * Admin UX Enhancements
 *
 * Features:
 * 0. Alpine.js sidebar store (must run before Alpine init)
 * 1. Top-of-page progress bar for HTMX requests
 * 2. Toast notification system (triggered via HTMX HX-Trigger or JS API)
 * 3. Clickable table rows
 * 4. Skeleton loading for tables
 * 5. Styled delete confirmation modal (replaces hx-confirm)
 */

// =========================================================================
// 0. Alpine.js Sidebar Store
//    Registered before Alpine.start() via alpine:init event.
//    Must be outside the IIFE so it runs immediately.
// =========================================================================
document.addEventListener('alpine:init', function () {
  var persist = function (value, key) {
    if (window.Alpine && window.Alpine.$persist) {
      return window.Alpine.$persist(value).as(key);
    }
    return value;
  };

  window.Alpine.store('sidebar', {
    state: persist('expanded', 'wagoe-admin-sidebar-state'),
    pinned: persist(false, 'wagoe-admin-sidebar-pinned'),
    mobileOpen: false,

    toggle: function () {
      this.state = this.state === 'expanded' ? 'collapsed' : 'expanded';
    },

    togglePin: function () {
      this.pinned = !this.pinned;
    },

    expand: function () {
      if (!this.pinned && window.innerWidth > 768) {
        this.state = 'expanded';
      }
    },

    collapse: function () {
      if (!this.pinned && window.innerWidth > 768) {
        this.state = 'collapsed';
      }
    },

    toggleMobile: function () {
      this.mobileOpen = !this.mobileOpen;
    },

    closeMobile: function () {
      this.mobileOpen = false;
    },

    handleNavLinkClick: function () {
      if (window.innerWidth <= 768) {
        this.closeMobile();
      }
    }
  });

  // =========================================================================
  // Alpine.data component registrations (required for CSP build).
  // All x-data values in templates must reference a registered component.
  // Shared components (dropdown) are registered in components.js so they
  // are available to base/pilot bundles as well.
  // =========================================================================

  // Empty: provides an Alpine scope without data (used for store-only bindings).
  window.Alpine.data('empty', function () {
    return {};
  });

  // Collapsible: simple open/close panel with optional initial state.
  window.Alpine.data('collapsible', function (initialOpen) {
    return {
      open: initialOpen !== undefined ? initialOpen : false
    };
  });

  // BulkSelection: manages a selectedIds array for table checkbox bulk actions.
  window.Alpine.data('bulkSelection', function () {
    return {
      selectedIds: [],

      getCheckboxes: function () {
        return this.$root.querySelectorAll('input[name="ids[]"]');
      },

      isAllChecked: function () {
        var checkboxes = this.getCheckboxes();
        return this.selectedIds.length > 0 && this.selectedIds.length === checkboxes.length;
      },

      isIndeterminate: function () {
        var checkboxes = this.getCheckboxes();
        return this.selectedIds.length > 0 && this.selectedIds.length < checkboxes.length;
      },

      toggleAll: function (event) {
        var checkboxes = Array.from(this.getCheckboxes());
        this.selectedIds = event.target.checked
          ? checkboxes.map(function (cb) { return cb.value; })
          : [];
      },

      clearSelection: function () {
        this.selectedIds = [];
      }
    };
  });

  // DirtyForm: counts the fields that differ from how the form was rendered,
  // for the save bar; Discard resets them, ⌘S / Ctrl+S submits, and leaving
  // the page with changes asks first.
  window.Alpine.data('dirtyForm', function () {
    return {
      dirty: 0,
      changedText: '',
      initial: {},

      init: function () {
        var self = this;
        this.initial = this.snapshot();
        this.onUnload = function (event) {
          if (self.dirty > 0) {
            event.preventDefault();
            event.returnValue = '';
          }
        };
        window.addEventListener('beforeunload', this.onUnload);
        this.$nextTick(function () { self.focusFirst(); });
        // Submitting is not leaving with unsaved changes. A field checking
        // itself on the server is not submitting.
        this.$root.addEventListener('htmx:beforeRequest', function (event) {
          if (event.detail.elt !== self.$root) return;
          window.removeEventListener('beforeunload', self.onUnload);
        });
      },

      destroy: function () {
        window.removeEventListener('beforeunload', this.onUnload);
      },

      // name -> value(s); hidden plumbing fields (`__zone`) are not changes
      snapshot: function () {
        var values = {};
        new FormData(this.$root).forEach(function (value, key) {
          if (key.indexOf('__') === 0) return;
          var v = typeof value === 'string' ? value : value.name;
          values[key] = key in values ? values[key] + '\u0000' + v : v;
        });
        return values;
      },

      track: function () {
        var now = this.snapshot();
        var initial = this.initial;
        var root = this.$root;
        var keys = {};
        Object.keys(initial).concat(Object.keys(now)).forEach(function (k) { keys[k] = true; });
        var changed = Object.keys(keys).filter(function (k) { return initial[k] !== now[k]; });
        this.dirty = changed.length;
        // The changed fields by their labels, for the summary beside the form
        this.changedText = changed.map(function (k) {
          var input = root.querySelector('[name="' + k + '"]');
          var label = input && input.id && root.querySelector('label[for="' + input.id + '"]');
          var field = input && input.closest('.form-field');
          var text = (label || (field && field.querySelector('label')) || {}).textContent || k;
          return text.replace('*', '').trim();
        }).join(', ');
      },

      // The cursor starts where the work is: the first field with an error,
      // else the first field that can be edited. Not when the page was opened
      // at an anchor, or something else already has focus.
      focusFirst: function () {
        if (window.location.hash) return;
        var active = document.activeElement;
        if (active && active !== document.body && this.$root.contains(active) === false &&
            active.matches('input, select, textarea, [contenteditable]')) return;
        var editable = 'input:not([type=hidden]):not([type=submit]):not([type=button])' +
          ':not([readonly]):not([disabled]), select:not([disabled]), textarea:not([readonly]):not([disabled])';
        var visible = function (el) { return el.offsetParent !== null; };
        var field = Array.from(this.$root.querySelectorAll('[aria-invalid="true"]')).filter(visible)[0] ||
          Array.from(this.$root.querySelectorAll(editable)).filter(visible)[0];
        if (!field) return;
        field.focus({ preventScroll: false });
        // Text: the caret at the end, not the whole value selected
        if (typeof field.setSelectionRange === 'function' && /^(text|search|email|url|tel)$/.test(field.type || 'text')) {
          var end = field.value.length;
          try { field.setSelectionRange(end, end); } catch (e) { /* not every type allows it */ }
        }
      },

      discard: function () {
        this.$root.reset();
        this.track();
      },

      save: function () {
        this.$root.requestSubmit();
      },

      // Save, then back to the list: the handler answers with a redirect
      saveAndBack: function () {
        var flag = this.$root.querySelector('input[name="__then"]');
        if (!flag) {
          flag = document.createElement('input');
          flag.type = 'hidden';
          flag.name = '__then';
          this.$root.appendChild(flag);
        }
        flag.value = 'list';
        this.$root.requestSubmit();
      }
    };
  });

  // CollapsibleOptionalFields: details element with localStorage persistence.
  window.Alpine.data('collapsibleOptionalFields', function (storageKey) {
    return {
      open: true,

      init: function () {
        var stored = localStorage.getItem(storageKey);
        this.open = (stored !== null ? stored : 'true') === 'true';
      },

      onToggle: function (event) {
        localStorage.setItem(storageKey, event.target.open ? 'true' : 'false');
      }
    };
  });
});

(function () {
  'use strict';

  // =========================================================================
  // 1. Progress Bar
  // =========================================================================

  var progressBar = null;

  function createProgressBar() {
    progressBar = document.createElement('div');
    progressBar.className = 'htmx-progress-bar';
    document.body.appendChild(progressBar);
  }

  function showProgress() {
    if (!progressBar) createProgressBar();
    progressBar.classList.remove('done');
    // Force reflow to restart animation
    progressBar.offsetWidth;
    progressBar.classList.add('active');
  }

  function hideProgress() {
    if (!progressBar) return;
    progressBar.classList.remove('active');
    progressBar.classList.add('done');
  }

  // =========================================================================
  // 2. Toast Notification System
  // =========================================================================

  var toastContainer = null;
  var TOAST_ICONS = {
    success: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>',
    error: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/></svg>',
    warning: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>',
    info: '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>'
  };

  function getToastContainer() {
    if (!toastContainer) {
      // Reuse existing HTML-rendered container if present, otherwise create one
      toastContainer = document.querySelector('.toast-container');
      if (!toastContainer) {
        toastContainer = document.createElement('div');
        toastContainer.className = 'toast-container';
        toastContainer.setAttribute('role', 'status');
        toastContainer.setAttribute('aria-live', 'polite');
        document.body.appendChild(toastContainer);
      }
    }
    return toastContainer;
  }

  function showToast(opts) {
    var type = opts.type || 'info';
    var title = opts.title || '';
    var message = opts.message || '';
    var duration = opts.duration || 5000;

    var container = getToastContainer();

    var toast = document.createElement('div');
    toast.className = 'toast toast-' + type;
    toast.style.setProperty('--toast-duration', duration + 'ms');

    toast.innerHTML =
      '<div class="toast-icon">' + (TOAST_ICONS[type] || TOAST_ICONS.info) + '</div>' +
      '<div class="toast-body">' +
        (title ? '<div class="toast-title">' + escapeHtml(title) + '</div>' : '') +
        (message ? '<div class="toast-message">' + escapeHtml(message) + '</div>' : '') +
      '</div>' +
      '<button class="toast-close" aria-label="Dismiss">&times;</button>';

    // Close button handler
    toast.querySelector('.toast-close').addEventListener('click', function () {
      dismissToast(toast);
    });

    container.appendChild(toast);

    // Auto-dismiss
    if (duration > 0) {
      setTimeout(function () {
        dismissToast(toast);
      }, duration);
    }

    return toast;
  }

  function dismissToast(toast) {
    if (toast.classList.contains('removing')) return;
    toast.classList.add('removing');
    removeAfterAnimation(toast);
  }

  /**
   * Remove an element after its CSS animation completes, or immediately
   * when prefers-reduced-motion disables animations.
   */
  function removeAfterAnimation(el) {
    var prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (prefersReducedMotion) {
      if (el.parentNode) el.parentNode.removeChild(el);
    } else {
      el.addEventListener('animationend', function () {
        if (el.parentNode) el.parentNode.removeChild(el);
      });
    }
  }

  function escapeHtml(str) {
    var div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
  }

  // =========================================================================
  // 3. Clickable Table Rows
  // =========================================================================

  function initClickableRows() {
    var clickTimer = null;

    function cancelPendingNavigation() {
      if (clickTimer) {
        clearTimeout(clickTimer);
        clickTimer = null;
      }
    }

    // Cancel pending navigation on any double-click (inline edit takes over)
    document.addEventListener('dblclick', cancelPendingNavigation);

    document.addEventListener('click', function (event) {
      // Any click anywhere cancels a pending row navigation first,
      // so a correction click (checkbox, button, etc.) is never ignored.
      cancelPendingNavigation();

      // Find closest clickable row
      var row = event.target.closest('tr.clickable-row');
      if (!row) return;

      var target = event.target;
      var modified = event.metaKey || event.ctrlKey || event.shiftKey || event.altKey;

      // The title cell links to the record: with a drawer on the page, a plain
      // click on it opens the drawer like the rest of the row does
      var titleLink = target.closest('td.c-title a');
      if (titleLink && !modified && document.getElementById(DRAWER_SLOT)) {
        event.preventDefault();
        openDrawer(row, row.getAttribute('data-href') || titleLink.getAttribute('href'));
        return;
      }

      // Don't navigate if clicking on interactive elements
      if (target.closest('a, button, input, select, textarea, .checkbox-cell')) {
        return;
      }

      var href = row.getAttribute('data-href');
      if (!href) return;

      // With a drawer slot on the page a click opens the record beside the
      // list; a modified click (new tab, window) still navigates.
      var inDrawer = document.getElementById(DRAWER_SLOT) && !modified;

      // Delay navigation to allow double-click (inline edit) to cancel it.
      // 400ms matches the typical OS double-click interval and accommodates
      // accessibility settings for slower double-clicks.
      clickTimer = setTimeout(function () {
        clickTimer = null;
        if (inDrawer) {
          openDrawer(row, href);
        } else {
          window.location.href = href;
        }
      }, 400);
    });

    document.addEventListener('htmx:beforeRequest', function (event) {
      var elt = event.detail && event.detail.elt;
      if (elt && elt.classList && elt.classList.contains('filter-operator-select') &&
          elt.closest('.filter-drawer')) {
        lastOperatorField = elt.getAttribute('name');
      }
    });

    // The user menu closes on a click outside it
    document.addEventListener('click', function (event) {
      document.querySelectorAll('details.user-menu[open], details.view-menu[open]').forEach(function (menu) {
        if (!menu.contains(event.target)) menu.removeAttribute('open');
      });
    });

    // The filter drawer takes the record drawer's place: no record is open
    document.addEventListener('click', function (event) {
      if (event.target.closest('[data-filter-open]')) {
        openHref = null;
        markOpenRow();
      }
    });

    // Close, previous and next inside the drawer
    document.addEventListener('click', function (event) {
      var control = event.target.closest('[data-drawer]');
      if (!control) return;
      var action = control.getAttribute('data-drawer');
      if (action === 'close') closeDrawer();
      if (action === 'prev') moveRow(-1);
      if (action === 'next') moveRow(1);
    });

    // A refreshed table (sort, page, an edit elsewhere) keeps the open row
    // marked; after a keyboard page turn the cursor lands on the new page
    document.addEventListener('htmx:afterSwap', function (event) {
      // The filter drawer opens with the cursor in the first active filter's
      // value, else the first field's
      var target = event.detail && event.detail.target;
      if (target && target.id === DRAWER_SLOT && target.querySelector('.filter-drawer')) {
        var drawer = target.querySelector('.filter-drawer');
        // After an operator change: that field, where the user was
        var changed = lastOperatorField &&
          drawer.querySelector('[name="' + lastOperatorField + '"]');
        lastOperatorField = null;
        var scope = (changed && changed.closest('.filter-field')) ||
          drawer.querySelector('.filter-field.is-active') || drawer;
        var first = scope.querySelector('.filter-value-input, .filter-field-inputs input:not([type=hidden])');
        if (first && !drawer.contains(document.activeElement)) first.focus();
      }
      if (pendingLanding) {
        var rows = clickableRows();
        var row = pendingLanding === 'last' ? rows[rows.length - 1] : rows[0];
        pendingLanding = null;
        if (row) {
          cursorHref = row.getAttribute('data-href');
          row.scrollIntoView({ block: 'nearest' });
          if (openHref) { openDrawer(row, cursorHref); return; }
        }
      }
      markOpenRow();
    });
  }

  // =========================================================================
  // 3a. List view: density and columns, per entity, in this browser
  // =========================================================================

  function viewKey(entity) { return 'wagoe.admin.view.' + entity; }

  function readView(entity) {
    try { return JSON.parse(localStorage.getItem(viewKey(entity))) || {}; } catch (e) { return {}; }
  }

  function writeView(entity, view) {
    try { localStorage.setItem(viewKey(entity), JSON.stringify(view)); } catch (e) { /* private mode */ }
  }

  // Apply the stored view to the page, and the page's controls to match
  function applyView() {
    var menu = document.querySelector('details.view-menu[data-view-entity]');
    if (!menu) return;
    var view = readView(menu.getAttribute('data-view-entity'));
    var hidden = view.hidden || [];
    var page = document.querySelector('.entity-list-page');
    if (page) page.setAttribute('data-density', view.density || 'compact');
    menu.querySelectorAll('[data-view="density"]').forEach(function (r) {
      r.checked = r.value === (view.density || 'compact');
    });
    menu.querySelectorAll('[data-view="column"]').forEach(function (c) {
      c.checked = hidden.indexOf(c.value) === -1;
    });
    var style = document.getElementById('admin-column-visibility');
    if (!style) {
      style = document.createElement('style');
      style.id = 'admin-column-visibility';
      document.head.appendChild(style);
    }
    style.textContent = hidden.map(function (f) {
      var c = '.field-' + CSS.escape(f);
      return '.data-table col' + c + ', .data-table th' + c + ', .data-table td' + c + ' { display: none; }';
    }).join('\n');
  }

  function initListView() {
    document.addEventListener('change', function (event) {
      var input = event.target.closest('[data-view]');
      if (!input) return;
      var menu = input.closest('details.view-menu');
      var entity = menu && menu.getAttribute('data-view-entity');
      if (!entity) return;
      var view = readView(entity);
      if (input.getAttribute('data-view') === 'density') {
        view.density = input.value;
      } else {
        var hidden = (view.hidden || []).filter(function (f) { return f !== input.value; });
        if (!input.checked) hidden.push(input.value);
        view.hidden = hidden;
      }
      writeView(entity, view);
      applyView();
    });
    // A swapped table keeps the view
    document.addEventListener('htmx:afterSwap', applyView);
    applyView();
  }

  // =========================================================================
  // 3b. Record Drawer
  // =========================================================================

  var DRAWER_SLOT = 'record-drawer-slot';
  var openHref = null;    // the record in the drawer
  var cursorHref = null;  // the row j/k is on
  var pendingLanding = null; // 'first' / 'last': where the cursor goes after a page turn
  var lastOperatorField = null; // the filter whose operator re-rendered the drawer

  function clickableRows() {
    return Array.from(document.querySelectorAll('tr.clickable-row'));
  }

  function rowByHref(href) {
    return clickableRows().find(function (r) { return r.getAttribute('data-href') === href; });
  }

  // Marks follow the hrefs, so they survive a table swap (sort, page, edit)
  function markOpenRow() {
    var rows = clickableRows();
    rows.forEach(function (r) {
      var href = r.getAttribute('data-href');
      r.classList.toggle('row-open', href === openHref);
      r.classList.toggle('row-cursor', href === cursorHref);
    });
    // Previous / next in the drawer cross to the adjacent page, so they stop
    // only at the ends of the list
    var i = rows.indexOf(rowByHref(openHref));
    var canTurn = function (which) {
      var b = document.querySelector('.table-pagination [data-page="' + which + '"]');
      return Boolean(b && !b.disabled);
    };
    var prev = document.querySelector('#record-drawer [data-drawer="prev"]');
    var next = document.querySelector('#record-drawer [data-drawer="next"]');
    if (prev) prev.disabled = i === -1 || (i === 0 && !canTurn('prev'));
    if (next) next.disabled = i === -1 || (i === rows.length - 1 && !canTurn('next'));
  }

  // j / k and the drawer's arrows: move the cursor; with the drawer open, the
  // drawer follows it
  function moveRow(delta) {
    var rows = clickableRows();
    if (!rows.length) return false;
    var i = rows.indexOf(rowByHref(cursorHref || openHref));
    // Past the edge of the page: on to the next or previous page
    if (i !== -1 && i + delta >= rows.length) return turnPage('next', 'first');
    if (i !== -1 && i + delta < 0) return turnPage('prev', 'last');
    var row = rows[i === -1 ? (delta > 0 ? 0 : rows.length - 1)
                            : Math.max(0, Math.min(rows.length - 1, i + delta))];
    cursorHref = row.getAttribute('data-href');
    row.scrollIntoView({ block: 'nearest' });
    if (openHref) {
      openDrawer(row, cursorHref);
    } else {
      markOpenRow();
    }
    return true;
  }

  // The pagination's own button, so the URL, the filters and the table swap
  // are exactly what a click does. `landOn` is where the cursor goes.
  function turnPage(which, landOn) {
    var button = document.querySelector('.table-pagination [data-page="' + which + '"]');
    if (!button || button.disabled) return false;
    pendingLanding = landOn || 'first';
    button.click();
    return true;
  }

  // Enter / o on the cursor row: the drawer when the page has one, else the page
  function openCursorRow() {
    var row = rowByHref(cursorHref);
    if (!row) return false;
    if (document.getElementById(DRAWER_SLOT)) {
      openDrawer(row, cursorHref);
    } else {
      window.location.href = cursorHref;
    }
    return true;
  }

  // e on the cursor row (no drawer open): its edit page, coming back to this
  // list. Without a cursor yet, e puts it on the first row rather than
  // editing a record nobody pointed at; the next e edits it.
  function editCursorRow() {
    var row = rowByHref(cursorHref);
    if (!row) return moveRow(1);
    var returnTo = window.location.pathname + window.location.search;
    window.location.href = row.getAttribute('data-href') +
      '?return_to=' + encodeURIComponent(returnTo);
    return true;
  }

  // f: open the filter drawer
  function openFilters() {
    var button = document.querySelector('[data-filter-open]');
    if (!button) return false;
    button.click();
    return true;
  }

  // x on the cursor row: select it, as a click on its checkbox would
  function toggleCursorRow() {
    var row = rowByHref(cursorHref || openHref);
    var box = row && row.querySelector('input[name="ids[]"]');
    if (!box) return false;
    box.click();
    return true;
  }

  function openDrawer(row, href) {
    var returnTo = window.location.pathname + window.location.search;
    var url = href + (href.indexOf('?') === -1 ? '?' : '&') +
      'peek=1&return_to=' + encodeURIComponent(returnTo);
    openHref = href;
    cursorHref = href;
    markOpenRow();
    window.htmx.ajax('GET', url, { target: '#' + DRAWER_SLOT, swap: 'innerHTML' })
      .then(markOpenRow);
  }

  function closeDrawer() {
    var slot = document.getElementById(DRAWER_SLOT);
    if (!slot || !slot.firstElementChild) return false;
    slot.innerHTML = '';
    openHref = null;
    markOpenRow();
    return true;
  }

  // =========================================================================
  // 4. Skeleton Loading
  // =========================================================================

  function createSkeletonRows(count, colCount) {
    var widths = ['w-3-4', 'w-full', 'w-1-2', 'w-1-3', 'w-3-4', 'w-1-4'];
    var html = '';
    for (var i = 0; i < count; i++) {
      html += '<tr class="skeleton-row">';
      for (var j = 0; j < colCount; j++) {
        var w = widths[(i + j) % widths.length];
        html += '<td><div class="skeleton-cell ' + w + '">&nbsp;</div></td>';
      }
      html += '</tr>';
    }
    return html;
  }

  // Per-request snapshots, so overlapping HTMX requests targeting the same
  // table don't clobber each other. Each entry is {xhr, tbody, originalHtml}.
  // The list is expected to stay short (one entry per in-flight table
  // request), so linear scans are fine.
  var pendingTableRequests = [];

  function findPendingIndexByXhr(xhr) {
    for (var i = 0; i < pendingTableRequests.length; i++) {
      if (pendingTableRequests[i].xhr === xhr) return i;
    }
    return -1;
  }

  function anyPendingForTbody(tbody) {
    for (var i = 0; i < pendingTableRequests.length; i++) {
      if (pendingTableRequests[i].tbody === tbody) return true;
    }
    return false;
  }

  function findOriginalHtmlForTbody(tbody) {
    for (var i = 0; i < pendingTableRequests.length; i++) {
      if (pendingTableRequests[i].tbody === tbody) {
        return pendingTableRequests[i].originalHtml;
      }
    }
    return null;
  }

  function removePendingRequest(xhr, shouldRestore) {
    if (!xhr) return;
    var idx = findPendingIndexByXhr(xhr);
    if (idx < 0) return;
    var entry = pendingTableRequests[idx];
    pendingTableRequests.splice(idx, 1);
    if (!shouldRestore) return;
    // Only restore the original markup if no other in-flight request is
    // still displaying a skeleton on this tbody. Otherwise the other
    // request's own completion will clear its skeleton.
    if (!anyPendingForTbody(entry.tbody)) {
      entry.tbody.innerHTML = entry.originalHtml;
    }
  }

  // Show skeleton when table container is being loaded via HTMX
  function handleTableSkeleton(event) {
    var target = event.detail.target || event.target;
    if (!target) return;
    var tbody = target.querySelector && target.querySelector('.data-table tbody');
    if (!tbody) return;
    var xhr = event.detail.xhr;
    if (!xhr) return;
    // If another in-flight request already replaced this tbody with a
    // skeleton, reuse its captured original HTML instead of stashing the
    // skeleton markup. Otherwise on error we'd restore the skeleton and
    // the table would stay stuck in a loading state.
    var originalHtml = findOriginalHtmlForTbody(tbody);
    if (originalHtml === null) {
      originalHtml = tbody.innerHTML;
    }
    pendingTableRequests.push({ xhr: xhr, tbody: tbody, originalHtml: originalHtml });
    var thead = target.querySelector('.data-table thead');
    var colCount = thead ? thead.querySelectorAll('th').length : 5;
    tbody.innerHTML = createSkeletonRows(8, colCount);
  }

  function restoreTableOnError(xhr) {
    removePendingRequest(xhr, true);
  }

  function clearSavedTable(xhr) {
    removePendingRequest(xhr, false);
  }

  // =========================================================================
  // 5. Delete Confirmation Modal
  // =========================================================================

  function showConfirmModal(opts) {
    var title = opts.title || 'Confirm Delete';
    var message = opts.message || 'Are you sure you want to delete this item? This action cannot be undone.';
    var detail = opts.detail || '';
    var cancelLabel = opts.cancelLabel || 'Cancel';
    var confirmLabel = opts.confirmLabel || 'Delete';
    var onConfirm = opts.onConfirm || null;

    var backdrop = document.createElement('div');
    backdrop.className = 'confirm-modal-backdrop';
    backdrop.setAttribute('role', 'dialog');
    backdrop.setAttribute('aria-modal', 'true');

    backdrop.innerHTML =
      '<div class="confirm-modal">' +
        '<div class="confirm-modal-header">' +
          '<div class="confirm-modal-icon danger">' +
            '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>' +
          '</div>' +
          '<div class="confirm-modal-title">' + escapeHtml(title) + '</div>' +
        '</div>' +
        '<div class="confirm-modal-body">' +
          '<p>' + escapeHtml(message) + '</p>' +
          (detail ? '<div class="confirm-detail">' + escapeHtml(detail) + '</div>' : '') +
        '</div>' +
        '<div class="confirm-modal-footer">' +
          '<button class="button secondary confirm-cancel">' + escapeHtml(cancelLabel) + '</button>' +
          '<button class="button danger confirm-delete">' + escapeHtml(confirmLabel) + '</button>' +
        '</div>' +
      '</div>';

    // Focus trap — focus the cancel button first
    document.body.appendChild(backdrop);
    var cancelBtn = backdrop.querySelector('.confirm-cancel');
    var deleteBtn = backdrop.querySelector('.confirm-delete');
    cancelBtn.focus();

    // Close on Escape
    function onEsc(e) {
      if (e.key === 'Escape') {
        close(false);
      }
    }
    document.addEventListener('keydown', onEsc);

    function close(confirmed) {
      // Always clean up the Escape handler
      document.removeEventListener('keydown', onEsc);

      backdrop.classList.add('closing');
      removeAfterAnimation(backdrop);

      if (confirmed && onConfirm) {
        onConfirm();
      }
    }

    cancelBtn.addEventListener('click', function () { close(false); });
    deleteBtn.addEventListener('click', function () { close(true); });

    // Close on backdrop click
    backdrop.addEventListener('click', function (e) {
      if (e.target === backdrop) close(false);
    });
  }

  // =========================================================================
  // Initialization
  // =========================================================================

  function init() {
    initListView();
    // Progress bar on HTMX requests
    document.addEventListener('htmx:beforeRequest', function () {
      showProgress();
    });
    document.addEventListener('htmx:afterRequest', function (event) {
      hideProgress();
      // htmx:afterRequest fires after error events too; restoreTableOnError
      // has already removed the entry in that case, so this is a no-op for
      // failed requests.
      clearSavedTable(event.detail && event.detail.xhr);
    });
    // htmx:responseError fires when the server returns a non-2xx status.
    // htmx:sendError and htmx:timeout fire when the request fails before any
    // response arrives (offline, DNS failure, CORS, connection timeout, etc.).
    // Without handling these, the progress bar stays stuck and the table body
    // is left permanently replaced by skeleton rows.
    function onTransportFailure(event) {
      hideProgress();
      restoreTableOnError(event && event.detail && event.detail.xhr);
    }
    document.addEventListener('htmx:responseError', onTransportFailure);
    document.addEventListener('htmx:sendError', onTransportFailure);
    document.addEventListener('htmx:timeout', onTransportFailure);

    // Skeleton on table requests
    document.addEventListener('htmx:beforeRequest', function (event) {
      var elt = event.detail.elt;
      if (!elt) return;
      // Only show skeleton for requests targeting the table container
      var targetSelector = elt.getAttribute('hx-target');
      if (targetSelector && targetSelector.indexOf('table-container') !== -1) {
        var target = document.querySelector(targetSelector);
        if (target) {
          handleTableSkeleton({
            detail: {
              target: target,
              xhr: event.detail.xhr
            }
          });
        }
      }
    });

    // Clickable rows
    initClickableRows();

    // Toast from HTMX HX-Trigger response header
    // Server can send: HX-Trigger: {"showToast": {"type": "success", "title": "Saved", "message": "Record updated"}}
    document.addEventListener('showToast', function (event) {
      var detail = event.detail || {};
      showToast({
        type: detail.type || detail.value || 'info',
        title: detail.title || '',
        message: detail.message || ''
      });
    });

    // XHR interception + pendingToast pickup handled by components.js
    // (loaded in every bundle). No duplication needed here.

    // Intercept hx-confirm on delete buttons with styled modal
    document.addEventListener('htmx:confirm', function (event) {
      var elt = event.detail.elt;
      if (!elt) return;

      // Only intercept for delete operations (has hx-delete or is a danger button)
      var isDelete = elt.hasAttribute('hx-delete') ||
                     elt.classList.contains('danger') ||
                     (elt.getAttribute('value') === 'delete');

      if (!isDelete) return;

      // Prevent the default browser confirm and the HTMX request
      event.preventDefault();

      var message = event.detail.question || 'Are you sure?';
      var title = elt.getAttribute('data-confirm-title') || 'Confirm Delete';
      var cancelLabel = elt.getAttribute('data-confirm-cancel') || 'Cancel';
      var confirmLabel = elt.getAttribute('data-confirm-label') || 'Delete';

      showConfirmModal({
        title: title,
        message: message,
        cancelLabel: cancelLabel,
        confirmLabel: confirmLabel,
        onConfirm: function () {
          // issueRequest() is HTMX's built-in way to proceed after htmx:confirm
          event.detail.issueRequest(true);
        }
      });
    });

    // Convert server-rendered flash messages into toasts. This must run
    // both on initial page load and after any HTMX body swap, because the
    // admin create/update flows swap the whole body via hx-swap="outerHTML"
    // and the fresh flash markup would otherwise stay inline (external
    // scripts are not re-executed on swaps).
    function convertFlashMessages(root) {
      var scope = root || document;
      var flashMessages = scope.querySelectorAll('.flash-messages .alert');
      flashMessages.forEach(function (el) {
        // Guard against double-conversion if a swap event re-visits the same
        // node (e.g. oob swaps, nested targets).
        if (el.dataset.toastConverted === '1') return;
        el.dataset.toastConverted = '1';

        var type = 'info';
        if (el.classList.contains('alert-success')) type = 'success';
        else if (el.classList.contains('alert-error') || el.classList.contains('alert-danger')) type = 'error';
        else if (el.classList.contains('alert-warning')) type = 'warning';

        showToast({
          type: type,
          title: type.charAt(0).toUpperCase() + type.slice(1),
          message: el.textContent.trim()
        });

        // Hide the inline flash
        el.style.display = 'none';
      });
    }

    convertFlashMessages();
    document.addEventListener('htmx:afterSwap', function (event) {
      // Scope the scan to the swapped subtree when possible to avoid
      // rescanning the whole document on every swap.
      convertFlashMessages(event.detail && event.detail.target);
    });

    // Expose public API
    window.AdminUX = {
      closeDrawer: closeDrawer,
      moveRow: moveRow,
      turnPage: turnPage,
      openCursorRow: openCursorRow,
      editCursorRow: editCursorRow,
      openFilters: openFilters,
      toggleCursorRow: toggleCursorRow,
      showToast: showToast,
      showConfirmModal: showConfirmModal,
      createSkeletonRows: createSkeletonRows
    };
  }

  // Boot
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
