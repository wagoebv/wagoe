/**
 * Stitch Admin - Keyboard Shortcuts
 *
 * Global shortcuts:
 *   g h  - Go to Dashboard (/web/dashboard)
 *   g u  - Go to Users (/web/admin/user)
 *
 * Context shortcuts:
 *   n    - New/Create (in list view)
 *   e    - Edit (in detail view)
 *   /    - Focus search
 *   Esc  - Close modal/cancel edit
 */

(function() {
  'use strict';

  // Shortcut state
  let pendingPrefix = null;
  let prefixTimeout = null;

  // The fields '/' jumps to
  const SEARCH_SELECTOR =
    'input[type="search"], input[name="search"], input[name="q"], .search-input, #search';

  // Configuration
  const SHORTCUTS = {
    // Go-to shortcuts (two-key combos: g + key)
    goto: {
      'h': '/web/dashboard',       // g h -> Dashboard
      'u': '/web/admin/user'       // g u -> Users (entity name is singular)
    },

    // Single-key shortcuts
    single: {
      'n': handleNew,
      'e': handleEdit,
      '/': handleSearch,
      'Escape': handleEscape,
      // List rows (admin-ux.js): move, open, select
      'j': function () { return rowAction('moveRow', 1); },
      'k': function () { return rowAction('moveRow', -1); },
      'o': function () { return rowAction('openCursorRow'); },
      'Enter': handleEnter,
      'PageDown': function () { return rowAction('turnPage', 'next'); },
      'PageUp': function () { return rowAction('turnPage', 'prev'); },
      'x': function () { return rowAction('toggleCursorRow'); },
      'f': function () { return rowAction('openFilters'); },
      'u': handleUpToList,
      // Previous / next record of the list, on a detail or edit page
      '[': function () { return clickIfThere('[data-nav="prev"]'); },
      ']': function () { return clickIfThere('[data-nav="next"]'); },
      // Close the drawer, then clear the selection. Esc does the same, but in
      // a full-screen browser Esc is the browser's: it leaves full screen.
      'q': closeOrClear
    }
  };

  /**
   * Check if focus is in an input element
   */
  function isInputFocused() {
    const active = document.activeElement;
    if (!active) return false;

    const tagName = active.tagName.toLowerCase();
    if (tagName === 'input' || tagName === 'textarea' || tagName === 'select') {
      return true;
    }

    // Also check contenteditable
    return active.isContentEditable;
  }

  /**
   * Navigate to URL
   */
  function navigateTo(url) {
    if (url && window.location.pathname !== url) {
      window.location.href = url;
    }
  }

  /**
   * Handle 'n' - New/Create
   * Clicks the first "Create" or "New" button found
   */
  function handleNew() {
    // Look for create button in toolbar
    const createBtn = document.querySelector(
      '.page-header-compact .button.primary, ' +
      '.toolbar .button.primary, ' +
      'a[href*="/new"], ' +
      'button[data-action="create"]'
    );

    if (createBtn) {
      createBtn.click();
      return true;
    }
    return false;
  }

  /**
   * A list-row action from admin-ux.js, when it is loaded
   */
  function rowAction(name, arg) {
    const ux = window.AdminUX;
    return Boolean(ux && ux[name] && ux[name](arg));
  }

  /**
   * Close the record drawer, else clear the row selection
   */
  function closeOrClear() {
    if (window.AdminUX && window.AdminUX.closeDrawer && window.AdminUX.closeDrawer()) {
      return true;
    }
    if (document.querySelector('.bulk-bar:not([style*="display: none"])')) {
      window.dispatchEvent(new CustomEvent('bulk-clear'));
      return true;
    }
    return false;
  }

  /**
   * Whether the browser is full screen: the Fullscreen API, or a window that
   * fills the screen (macOS full screen, F11), which no API reports
   */
  function isFullScreen() {
    return Boolean(document.fullscreenElement) ||
      (window.innerHeight >= window.screen.height && window.innerWidth >= window.screen.width);
  }

  function clickIfThere(selector) {
    const el = document.querySelector(selector);
    if (!el) return false;
    el.click();
    return true;
  }

  /**
   * Handle 'u' - up to the list a detail or edit page came from (the list
   * with its filters and page, via the breadcrumb). Unsaved changes still
   * ask first: the form's leave-page warning catches it.
   */
  function handleUpToList() {
    const link = document.querySelector('[data-action="list"], .page-breadcrumbs a:last-of-type');
    if (!link || !link.getAttribute('href')) return false;
    link.click();
    return true;
  }

  /**
   * Handle Enter - open the cursor row, unless Enter means something already
   * (a focused link or button)
   */
  function handleEnter() {
    const el = document.activeElement;
    if (el && el.closest('a, button, summary')) return false;
    return rowAction('openCursorRow');
  }

  /**
   * Handle 'e' - Edit
   * Clicks the Edit button in detail view
   */
  function handleEdit() {
    const editBtn = document.querySelector(
      '#record-drawer [data-action="edit"], ' +
      '.detail-actions .button[data-action="edit"], ' +
      'a[href*="/edit"], ' +
      'button[data-action="edit"]'
    );

    if (editBtn) {
      editBtn.click();
      return true;
    }
    // On a list without a drawer open: the row the cursor is on
    return rowAction('editCursorRow');
  }

  /**
   * Handle '/' - Focus search
   */
  function handleSearch() {
    const searchInput = document.querySelector(SEARCH_SELECTOR);

    if (searchInput) {
      searchInput.focus();
      searchInput.select();
      return true;
    }
    return false;
  }

  /**
   * Handle Escape - Close modal or cancel
   */
  function handleEscape() {
    // First Esc leaves a field ('/' put you in the search): blur it, keep
    // what was typed. Also in full screen, where the browser takes Esc too.
    // An inline cell edit is left by cancelling it, below.
    const active = document.activeElement;
    if (active && active !== document.body && active.matches('input, select, textarea') &&
        !document.querySelector('.inline-cancel')) {
      active.blur();
      return true;
    }

    // Close confirm modal (styled delete dialog)
    const confirmBackdrop = document.querySelector('.confirm-modal-backdrop');
    if (confirmBackdrop) {
      const cancelBtn = confirmBackdrop.querySelector('.confirm-cancel');
      if (cancelBtn) {
        cancelBtn.click();
        return true;
      }
    }

    // Close any open modals
    const modal = document.querySelector('.modal[open], .modal.active, .modal-backdrop');
    if (modal) {
      // Try clicking close button first
      const closeBtn = modal.querySelector('.modal-close, [data-action="close"], button[type="button"]');
      if (closeBtn) {
        closeBtn.click();
        return true;
      }
      // Or trigger close event
      modal.dispatchEvent(new CustomEvent('close'));
      return true;
    }

    // Close the record drawer, then clear a row selection — unless the
    // browser is full screen, where Esc already leaves full screen and doing
    // both at once surprises (q does it there)
    if (!isFullScreen() && closeOrClear()) {
      return true;
    }

    // Cancel inline edit
    const cancelBtn = document.querySelector('.inline-cancel');
    if (cancelBtn) {
      cancelBtn.click();
      return true;
    }

    // Close mobile sidebar
    const shell = document.querySelector('.admin-shell[data-sidebar-open="true"]');
    if (shell) {
      shell.setAttribute('data-sidebar-open', 'false');
      return true;
    }

    return false;
  }

  /**
   * Clear pending prefix state
   */
  function clearPrefix() {
    pendingPrefix = null;
    if (prefixTimeout) {
      clearTimeout(prefixTimeout);
      prefixTimeout = null;
    }
  }

  /**
   * Main keyboard handler
   */
  function handleKeydown(event) {
    const key = event.key.toLowerCase();

    // Debug logging (remove in production)
    if (window.stitch && window.stitch.debug) {
      console.log('[Stitch] Key pressed:', event.key, '| Lowercased:', key, '| Pending prefix:', pendingPrefix);
      console.log('[Stitch] Input focused:', isInputFocused(), '| Modifiers: ctrl=', event.ctrlKey, 'meta=', event.metaKey, 'alt=', event.altKey);
    }

    // Enter in the list search: the search already ran as you typed, so
    // leave the field and let j / k / x work on the results
    if (event.key === 'Enter' && document.activeElement &&
        document.activeElement.matches(SEARCH_SELECTOR)) {
      document.activeElement.blur();
      return;
    }

    // Don't handle if in input (except Escape)
    if (isInputFocused() && event.key !== 'Escape') {
      if (window.stitch && window.stitch.debug) {
        console.log('[Stitch] Skipping - input is focused');
      }
      return;
    }

    // Don't handle if modifier keys (except for our shortcuts)
    if (event.ctrlKey || event.metaKey || event.altKey) {
      if (window.stitch && window.stitch.debug) {
        console.log('[Stitch] Skipping - modifier key held');
      }
      return;
    }

    // Handle pending 'g' prefix
    if (pendingPrefix === 'g') {
      clearPrefix();

      const url = SHORTCUTS.goto[key];
      if (url) {
        if (window.stitch && window.stitch.debug) {
          console.log('[Stitch] Executing: g', key, '-> navigate to', url);
        }
        event.preventDefault();
        navigateTo(url);
        return;
      } else {
        if (window.stitch && window.stitch.debug) {
          console.log('[Stitch] No goto shortcut for g', key);
        }
      }
    }

    // Start 'g' prefix
    if (key === 'g' && !pendingPrefix) {
      if (window.stitch && window.stitch.debug) {
        console.log('[Stitch] Prefix "g" started - waiting for next key...');
      }
      event.preventDefault();
      pendingPrefix = 'g';

      // Clear after 1 second
      prefixTimeout = setTimeout(function() {
        if (window.stitch && window.stitch.debug) {
          console.log('[Stitch] Prefix "g" timed out');
        }
        clearPrefix();
      }, 1000);
      return;
    }

    // Handle single-key shortcuts
    const handler = SHORTCUTS.single[event.key] || SHORTCUTS.single[key];
    if (handler) {
      if (window.stitch && window.stitch.debug) {
        console.log('[Stitch] Executing single-key shortcut:', key);
      }
      const handled = handler();
      if (handled) {
        event.preventDefault();
      }
    }
  }

  /**
   * Show keyboard shortcuts help (optional)
   */
  function showHelp() {
    console.log('%c Keyboard Shortcuts ', 'background: #0891b2; color: white; padding: 4px 8px; border-radius: 4px;');
    console.log('g h  - Go to Dashboard (/web/dashboard)');
    console.log('g u  - Go to Users (/web/admin/user)');
    console.log('n    - New/Create');
    console.log('e    - Edit');
    console.log('/    - Focus search');
    console.log('j k  - Next / previous row (the drawer follows)');
    console.log('PgDn PgUp - Next / previous page (j / k also cross the edge)');
    console.log('o ↵  - Open the row');
    console.log('x    - Select the row');
    console.log('f    - Filters');
    console.log('u    - Up to the list (from a detail or edit page)');
    console.log('[ ]  - Previous / next record of that list');
    console.log('⌘↵   - Save and back to the list');
    console.log('q    - Close the drawer / clear the selection (also Esc, outside full screen)');
    console.log('Esc  - Close/Cancel');
  }

  /**
   * Initialize keyboard shortcuts
   */
  function init() {
    document.addEventListener('keydown', handleKeydown);

    // Expose help function and enable debugging
    window.stitch = window.stitch || {};
    window.stitch.debug = false;
    window.stitch.shortcuts = {
      help: showHelp,
      config: SHORTCUTS,
      test: function() {
        console.log('Keyboard shortcuts test - navigating to dashboard...');
        navigateTo('/web/dashboard');
      }
    };

    // Log initialization
    console.log('%c Stitch Keyboard Shortcuts loaded ', 'background: #0891b2; color: white; padding: 2px 6px; border-radius: 3px;');
    console.log('Type stitch.shortcuts.help() for available shortcuts');
    console.log('Debug mode enabled - key presses will be logged to console');
  }

  // Initialize when DOM is ready
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
