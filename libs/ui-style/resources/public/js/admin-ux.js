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

      // Don't navigate if clicking on interactive elements
      var target = event.target;
      if (target.closest('a, button, input, select, textarea, .checkbox-cell')) {
        return;
      }

      var href = row.getAttribute('data-href');
      if (!href) return;

      // Delay navigation to allow double-click (inline edit) to cancel it.
      // 400ms matches the typical OS double-click interval and accommodates
      // accessibility settings for slower double-clicks.
      clickTimer = setTimeout(function () {
        clickTimer = null;
        window.location.href = href;
      }, 400);
    });
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
