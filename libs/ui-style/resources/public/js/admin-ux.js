/**
 * Admin UX Enhancements
 *
 * Features:
 * 0. Alpine.js sidebar store (must run before Alpine init)
 * 1. Top-of-page progress bar for HTMX requests
 * 3. Clickable table rows
 * 4. Skeleton loading for tables
 */

// =========================================================================
// 0. Alpine.js Sidebar Store
//    Registered before Alpine.start() via alpine:init event.
//    Must be outside the IIFE so it runs immediately.
// =========================================================================
document.addEventListener('alpine:init', function () {
  // Plain localStorage rather than Alpine's Persist plugin: alpine.min.js is
  // the core build. Storage can throw (disabled, private mode); then state
  // lives per page.
  var load = function (key) {
    try {
      return JSON.parse(window.localStorage.getItem(key));
    } catch (e) {
      return null;
    }
  };
  var save = function (key, value) {
    try {
      window.localStorage.setItem(key, JSON.stringify(value));
    } catch (e) { /* not persisted */ }
  };
  // The sidebar is open or closed and changes only when the user toggles it.
  // init.js puts the stored state on <html> as data-sidebar before first
  // paint; CSS reads it there. Small screens show the sidebar as an overlay
  // drawer instead, closed on every page, without touching the stored state.
  var SIDEBAR_KEY = 'wagoe-admin-sidebar';
  var root = document.documentElement;
  var smallScreen = window.matchMedia('(max-width: 768px)');
  // Stored raw ('open' / 'closed'), as init.js reads it.
  var storedOpen = function () {
    try {
      return window.localStorage.getItem(SIDEBAR_KEY) !== 'closed';
    } catch (e) {
      return true;
    }
  };

  window.Alpine.store('sidebar', {
    open: root.getAttribute('data-sidebar') !== 'closed',
    small: smallScreen.matches,
    mobileOpen: false,

    init: function () {
      var self = this;
      // A page that renders the shell without init.js still gets its state
      if (!root.hasAttribute('data-sidebar')) self.show(storedOpen());
      smallScreen.addEventListener('change', function (e) {
        self.small = e.matches;
        self.mobileOpen = false;
      });
      // Another tab, or this page back from the bfcache, may hold an older
      // state than the one last chosen.
      window.addEventListener('storage', function (e) {
        if (e.key === SIDEBAR_KEY) self.show(e.newValue !== 'closed');
      });
      window.addEventListener('pageshow', function (e) {
        if (e.persisted) self.show(storedOpen());
      });
    },

    show: function (open) {
      this.open = open;
      root.setAttribute('data-sidebar', open ? 'open' : 'closed');
    },

    // What the toggle button controls at this width.
    expanded: function () {
      return this.small ? this.mobileOpen : this.open;
    },

    toggle: function () {
      if (this.small) {
        this.mobileOpen = !this.mobileOpen;
        return;
      }
      this.show(!this.open);
      try {
        window.localStorage.setItem(SIDEBAR_KEY, this.open ? 'open' : 'closed');
      } catch (e) { /* this page only */ }
    },

    closeMobile: function () {
      this.mobileOpen = false;
    },

    handleNavLinkClick: function () {
      this.closeMobile();
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

  // NavGroup: a module's sidebar group. The group holding the current page is
  // open, so its marked item shows; the others are as last left.
  window.Alpine.data('navGroup', function (id, active) {
    var key = 'wagoe-admin-nav-' + id;
    var stored = load(key);
    return {
      open: active || stored === true,

      toggle: function () {
        this.open = !this.open;
        save(key, this.open);
      }
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
      if (elt && elt === document.activeElement && elt.closest && elt.closest('.table-pagination')) {
        pagerFocused = true;
      }
    });

    // htmx refocuses a pager button by its id after the swap; one that is now
    // disabled (next, on the last page) cannot take it, so the current page does
    document.addEventListener('htmx:afterSettle', function () {
      if (!pagerFocused) return;
      pagerFocused = false;
      if (document.activeElement && document.activeElement !== document.body) return;
      var current = document.querySelector('.table-pagination [aria-current="page"]');
      if (current) current.focus();
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

    // An audit row's details, from the button's data attributes, into the
    // modal it opens (components.js opens it)
    document.addEventListener('click', function (event) {
      var trigger = event.target.closest('.audit-detail-trigger');
      if (!trigger) return;
      ['timestamp', 'action', 'actor', 'target', 'ip', 'userAgent', 'error'].forEach(function (key) {
        var node = document.getElementById('audit-detail-' + key.replace(/[A-Z]/g, '-$&').toLowerCase());
        if (node) node.textContent = trigger.dataset[key] || '-';
      });
      var errorRow = document.getElementById('audit-detail-error-row');
      if (errorRow) errorRow.hidden = !trigger.dataset.error;
    });

    // Back and forward restore a snapshot whose filter inputs show what the
    // server rendered, not what was chosen since; the URL holds the truth
    document.addEventListener('htmx:historyRestore', function () {
      var params = new URLSearchParams(window.location.search);
      document.querySelectorAll('select[hx-push-url], input[hx-push-url]').forEach(function (el) {
        if (el.name) el.value = params.get(el.name) || '';
      });
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
  var pagerFocused = false; // a focused pager button sent the request

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

  // Focus goes back to what opened the drawer: the record's row, or the
  // filter button
  function closeDrawer() {
    var slot = document.getElementById(DRAWER_SLOT);
    if (!slot || !slot.firstElementChild) return false;
    var filters = slot.querySelector('.filter-drawer');
    var origin = openHref ? rowByHref(openHref)
      : (filters && document.querySelector('[data-filter-open]'));
    slot.innerHTML = '';
    openHref = null;
    markOpenRow();
    if (origin) origin.focus();
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

    // Expose public API
    window.AdminUX = {
      closeDrawer: closeDrawer,
      moveRow: moveRow,
      turnPage: turnPage,
      openCursorRow: openCursorRow,
      editCursorRow: editCursorRow,
      openFilters: openFilters,
      toggleCursorRow: toggleCursorRow,
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
