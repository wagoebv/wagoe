/**
 * Theme Management System
 * 
 * Handles theme switching between light and dark modes with:
 * - System preference detection (prefers-color-scheme)
 * - User preference persistence (localStorage)
 * - Smooth transitions between themes
 * - No flash of unstyled content (FOUC)
 * 
 * Usage:
 *   ThemeManager.init();           // Initialize on page load
 *   ThemeManager.toggle();         // Toggle between light/dark
 *   ThemeManager.getTheme();       // Get current theme
 */

const ThemeManager = (() => {
  // ============================================================================
  // Constants
  // ============================================================================
  
  const STORAGE_KEY = 'wagoe-theme';
  const THEME_ATTR = 'data-theme';
  const THEMES = {
    LIGHT: 'light',
    DARK: 'dark'
  };
  
  // ============================================================================
  // State
  // ============================================================================
  
  let currentTheme = null;
  let systemPreference = null;
  
  // ============================================================================
  // System Preference Detection
  // ============================================================================
  
  /**
   * Get the user's system color scheme preference
   * @returns {'light'|'dark'} System theme preference
   */
  function getSystemPreference() {
    if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
      return THEMES.DARK;
    }
    return THEMES.LIGHT;
  }
  
  /**
   * Listen for system preference changes
   */
  function watchSystemPreference() {
    if (!window.matchMedia) return;
    
    const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)');
    
    // Modern browsers
    if (mediaQuery.addEventListener) {
      mediaQuery.addEventListener('change', (e) => {
        systemPreference = e.matches ? THEMES.DARK : THEMES.LIGHT;
        
        // Only apply if user hasn't set explicit preference
        const storedTheme = localStorage.getItem(STORAGE_KEY);
        if (!storedTheme) {
          applyTheme(systemPreference);
        }
      });
    }
    // Legacy browsers
    else if (mediaQuery.addListener) {
      mediaQuery.addListener((e) => {
        systemPreference = e.matches ? THEMES.DARK : THEMES.LIGHT;
        
        const storedTheme = localStorage.getItem(STORAGE_KEY);
        if (!storedTheme) {
          applyTheme(systemPreference);
        }
      });
    }
  }
  
  // ============================================================================
  // Theme Application
  // ============================================================================
  
  /**
   * Apply theme to document
   * @param {string} theme - 'light' or 'dark'
   */
  function applyTheme(theme) {
    if (theme !== THEMES.LIGHT && theme !== THEMES.DARK) {
      console.warn('Invalid theme:', theme);
      return;
    }
    
    currentTheme = theme;
    document.documentElement.setAttribute(THEME_ATTR, theme);
    
    // Emit custom event for other components to react to theme changes
    const event = new CustomEvent('themechange', { 
      detail: { theme }
    });
    window.dispatchEvent(event);
  }
  
  /**
   * Get the theme that should be applied on init
   * Priority: localStorage > system preference > default (light)
   * @returns {string} Theme to apply
   */
  function getInitialTheme() {
    // 1. Check localStorage for user preference
    const storedTheme = localStorage.getItem(STORAGE_KEY);
    if (storedTheme === THEMES.LIGHT || storedTheme === THEMES.DARK) {
      return storedTheme;
    }
    
    // 2. Fall back to system preference
    systemPreference = getSystemPreference();
    return systemPreference;
  }
  
  // ============================================================================
  // Public API
  // ============================================================================
  
  /**
   * Initialize theme system
   * Should be called as early as possible to prevent FOUC
   */
  function init() {
    const theme = getInitialTheme();
    applyTheme(theme);
    watchSystemPreference();
  }
  
  /**
   * Toggle between light and dark themes
   * Saves preference to localStorage
   */
  function toggle() {
    const newTheme = currentTheme === THEMES.DARK ? THEMES.LIGHT : THEMES.DARK;
    applyTheme(newTheme);
    localStorage.setItem(STORAGE_KEY, newTheme);
  }
  
  /**
   * Set specific theme
   * @param {string} theme - 'light' or 'dark'
   */
  function setTheme(theme) {
    if (theme !== THEMES.LIGHT && theme !== THEMES.DARK) {
      console.warn('Invalid theme:', theme);
      return;
    }
    
    applyTheme(theme);
    localStorage.setItem(STORAGE_KEY, theme);
  }
  
  /**
   * Get current theme
   * @returns {string} Current theme ('light' or 'dark')
   */
  function getTheme() {
    return currentTheme;
  }
  
  /**
   * Clear stored preference (revert to system preference)
   */
  function clearPreference() {
    localStorage.removeItem(STORAGE_KEY);
    systemPreference = getSystemPreference();
    applyTheme(systemPreference);
  }
  
  // ============================================================================
  // Export Public API
  // ============================================================================
  
  return {
    init,
    toggle,
    setTheme,
    getTheme,
    clearPreference,
    THEMES
  };
})();

// ============================================================================
// Auto-initialize theme as early as possible (prevent FOUC)
// ============================================================================

// Run immediately - don't wait for DOMContentLoaded
ThemeManager.init();

// ============================================================================
// Theme Toggle Button Handler
// ============================================================================

// Set up toggle button when DOM is ready
document.addEventListener('DOMContentLoaded', () => {
  const toggleButtons = document.querySelectorAll('[data-theme-toggle]');
  
  toggleButtons.forEach(button => {
    button.addEventListener('click', (e) => {
      e.preventDefault();
      ThemeManager.toggle();
      
      // Update button icon/text if needed
      updateToggleButton(button);
    });
    
    // Initialize button state
    updateToggleButton(button);
  });
  
  // Listen for theme changes to update all toggle buttons
  window.addEventListener('themechange', (e) => {
    toggleButtons.forEach(button => {
      updateToggleButton(button);
    });
  });
});

/**
 * Update toggle button appearance based on current theme
 * @param {HTMLElement} button - Theme toggle button
 */
function updateToggleButton(button) {
  const currentTheme = ThemeManager.getTheme();
  const isDark = currentTheme === 'dark';
  
  // Update aria-label for accessibility
  button.setAttribute('aria-label', isDark ? 'Switch to light mode' : 'Switch to dark mode');
  
  // Update data attribute for CSS styling
  button.setAttribute('data-theme-current', currentTheme);
  
  // Update icon if button contains theme icons
  const lightIcon = button.querySelector('[data-theme-icon="light"]');
  const darkIcon = button.querySelector('[data-theme-icon="dark"]');
  
  if (lightIcon && darkIcon) {
    if (isDark) {
      lightIcon.style.cssText = 'display: flex; position: relative; flex: 1; align-items: center; justify-content: center;';
      darkIcon.style.cssText = 'display: none; position: absolute;';
    } else {
      lightIcon.style.cssText = 'display: none; position: absolute;';
      darkIcon.style.cssText = 'display: flex; position: relative; flex: 1; align-items: center; justify-content: center;';
    }
  }
}
