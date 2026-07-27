/**
 * Theme bootstrap.
 *
 * Kept as a separate file rather than an inline <script> so the Content-Security
 * Policy can use script-src 'self' without 'unsafe-inline'. Loaded blocking in
 * <head> so the theme attribute is set before first paint (no flash).
 */
(function () {
  try {
    var theme = localStorage.getItem('pulse_theme') || 'dark';
    document.documentElement.setAttribute('data-theme', theme);
  } catch (e) {
    // Private mode / storage disabled: fall back to the default dark theme
    document.documentElement.setAttribute('data-theme', 'dark');
  }
})();
