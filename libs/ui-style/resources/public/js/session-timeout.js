// Asks before the session ends (BOU-1007): whether the user is still there,
// two minutes before it times out, and for the password five minutes before
// its last day ends. No answer signs out. Tabs share what each one learns.
(function () {
  var dlg = document.getElementById('session-timeout');
  if (!dlg || typeof dlg.showModal !== 'function') return;
  var IDLE_WARN = 2 * 60 * 1000, REAUTH_WARN = 5 * 60 * 1000, KEY = 'wagoe-session';
  var expires = +dlg.dataset.expires, ends = +dlg.dataset.ends, timer = null, tick = null;
  var idle = dlg.querySelector('.session-idle'), reauth = dlg.querySelector('.session-reauth');
  var mfa = dlg.querySelector('.session-mfa'), refused = dlg.querySelector('.session-reauth-error');

  // Capped at its last day: it slides no further, so only the password helps
  function lastDay() { return ends - expires < 60 * 1000; }
  function warnAt() { return expires - (lastDay() ? REAUTH_WARN : IDLE_WARN); }

  function close() { clearInterval(tick); if (dlg.open) dlg.close(); }
  function schedule() {
    clearTimeout(timer);
    if (Date.now() < warnAt()) close();
    timer = setTimeout(ask, Math.max(0, warnAt() - Date.now()));
  }
  function learn(e, n, share) {
    if (!(e > 0)) return;
    expires = e;
    if (n > 0) ends = n;
    if (share) { try { localStorage.setItem(KEY, expires + ':' + ends); } catch (_) {} }
    schedule();
  }
  function fromResponse(res, share) {
    learn(+res.headers.get('X-Session-Expires'), +res.headers.get('X-Session-Ends'), share);
  }

  function count() {
    var left = Math.max(0, expires - Date.now()), s = Math.ceil(left / 1000);
    var text = Math.floor(s / 60) + ':' + ('0' + (s % 60)).slice(-2);
    dlg.querySelectorAll('.session-countdown').forEach(function (el) { el.textContent = text; });
    if (left === 0) { clearInterval(tick); dlg.querySelector('.session-logout').submit(); }
  }
  function ask() {
    var again = lastDay();
    idle.hidden = again;
    reauth.hidden = !again;
    if (!dlg.open) dlg.showModal();
    clearInterval(tick);
    tick = setInterval(count, 1000);
    count();
    (again ? reauth.querySelector('input[name="password"]') : dlg.querySelector('.session-stay')).focus();
  }

  // A fresh CSRF token for the session, after signing in again opened a new one
  function useToken(res) {
    var t = res.headers.get('X-CSRF-Token');
    if (!t) return;
    var meta = document.querySelector('meta[name="csrf-token"]');
    if (meta) meta.content = t;
    document.querySelectorAll('input[name="__anti-forgery-token"]').forEach(function (el) { el.value = t; });
  }
  function extend() {
    return fetch(dlg.dataset.extend, { credentials: 'same-origin' }).then(function (res) {
      if (!res.ok) { location.reload(); return; }
      useToken(res);
      close();
      fromResponse(res, true);
    });
  }

  dlg.addEventListener('cancel', function (e) { e.preventDefault(); }); // an answer, not Escape
  dlg.querySelector('.session-stay').addEventListener('click', extend);
  reauth.addEventListener('submit', function (e) {
    e.preventDefault();
    refused.hidden = true;
    var meta = document.querySelector('meta[name="csrf-token"]');
    fetch(dlg.dataset.reauth, {
      method: 'POST', credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-CSRF-Token': meta ? meta.content : '' },
      body: new URLSearchParams(new FormData(reauth)).toString()
    }).then(function (res) {
      if (res.ok) { reauth.reset(); mfa.hidden = true; return extend(); }
      if (res.headers.get('X-Session-Reauth') === 'mfa') {
        mfa.hidden = false;
        mfa.querySelector('input').focus();
        return;
      }
      refused.hidden = false;
    });
  });

  // Every request slides the session; htmx ones say where it now ends
  document.addEventListener('htmx:afterRequest', function (e) {
    var xhr = e.detail && e.detail.xhr;
    if (xhr) learn(+xhr.getResponseHeader('X-Session-Expires'), +xhr.getResponseHeader('X-Session-Ends'), true);
  });
  window.addEventListener('storage', function (e) {
    if (e.key !== KEY || !e.newValue) return;
    var parts = e.newValue.split(':');
    learn(+parts[0], +parts[1], false);
  });
  learn(expires, ends, true);
})();
