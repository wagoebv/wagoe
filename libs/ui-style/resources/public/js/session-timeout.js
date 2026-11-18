// Asks before the session ends (BOU-1007): whether the user is still there,
// two minutes before it times out, and for the password five minutes before
// its last day ends. No answer signs out. Tabs share what each one learns.
// Times are the server's: its clock, not this one, ends the session.
(function () {
  var dlg = document.getElementById('session-timeout');
  if (!dlg || typeof dlg.showModal !== 'function') return;
  var IDLE_WARN = 2 * 60 * 1000, REAUTH_WARN = 5 * 60 * 1000, KEY = 'wagoe-session';
  var expires = +dlg.dataset.expires, ends = +dlg.dataset.ends, skew = +dlg.dataset.now - Date.now();
  var timer = null, tick = null;
  var idle = dlg.querySelector('.session-idle'), reauth = dlg.querySelector('.session-reauth');
  var mfa = dlg.querySelector('.session-mfa'), refused = dlg.querySelector('.session-reauth-error');

  function now() { return Date.now() + skew; }
  // Capped at its last day: it slides no further, so only signing in again helps
  function lastDay() { return ends - expires < 60 * 1000; }
  function warnAt() { return expires - (lastDay() ? REAUTH_WARN : IDLE_WARN); }

  function close() { clearInterval(tick); if (dlg.open) dlg.close(); }
  function schedule() {
    clearTimeout(timer);
    if (now() < warnAt()) close();
    timer = setTimeout(ask, Math.max(0, warnAt() - now()));
  }
  function learn(e, n, serverNow, share) {
    if (!(e > 0)) return;
    if (serverNow > 0) skew = serverNow - Date.now();
    var renewed = n > 0 && n !== ends;
    expires = e;
    if (n > 0) ends = n;
    if (share) { try { localStorage.setItem(KEY, expires + ':' + ends); } catch (_) {} }
    schedule();
    return renewed;
  }
  function header(get) {
    return [+get('X-Session-Expires'), +get('X-Session-Ends'), +get('X-Session-Now')];
  }

  function count() {
    var left = Math.max(0, expires - now()), s = Math.ceil(left / 1000);
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
    (again ? reauth.querySelector('input[name="reauth-password"]') : dlg.querySelector('.session-stay')).focus();
  }

  // A fresh CSRF token for the session: signing in again opens a new one
  function useToken(res) {
    var t = res.headers.get('X-CSRF-Token');
    if (!t) return;
    var meta = document.querySelector('meta[name="csrf-token"]');
    if (meta) meta.content = t;
    document.querySelectorAll('input[name="__anti-forgery-token"]').forEach(function (el) { el.value = t; });
  }
  // A session that ended answers with a redirect to sign in: then go there
  function extend(share) {
    return fetch(dlg.dataset.extend, { credentials: 'same-origin', redirect: 'manual' }).then(function (res) {
      if (res.status !== 204) { location.reload(); return; }
      useToken(res);
      close();
      var h = header(function (k) { return res.headers.get(k); });
      learn(h[0], h[1], h[2], share);
    }, function () { /* offline: the countdown goes on, and Stay can be tried again */ });
  }

  dlg.addEventListener('cancel', function (e) { e.preventDefault(); }); // an answer, not Escape
  dlg.querySelector('.session-stay').addEventListener('click', function () { extend(true); });
  var again = dlg.querySelector('.session-sign-in');
  if (again) again.href = again.getAttribute('href') + '?return-to=' + encodeURIComponent(location.pathname + location.search);
  reauth.addEventListener('submit', function (e) {
    e.preventDefault();
    refused.hidden = true;
    var meta = document.querySelector('meta[name="csrf-token"]');
    fetch(dlg.dataset.reauth, {
      method: 'POST', credentials: 'same-origin', redirect: 'manual',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-CSRF-Token': meta ? meta.content : '' },
      body: new URLSearchParams(new FormData(reauth)).toString()
    }).then(function (res) {
      if (res.status === 204) { reauth.reset(); mfa.hidden = true; return extend(true); }
      if (res.headers.get('X-Session-Reauth') === 'mfa') {
        mfa.hidden = false;
        mfa.querySelector('input').focus();
        return;
      }
      refused.hidden = false;
    }, function () { refused.hidden = false; });
  });

  // Every request slides the session; htmx ones say where it now ends
  document.addEventListener('htmx:afterRequest', function (e) {
    var xhr = e.detail && e.detail.xhr;
    if (!xhr) return;
    var h = header(function (k) { return xhr.getResponseHeader(k); });
    learn(h[0], h[1], h[2], true);
  });
  // Another tab learnt something. A new last day is a new session, signed in
  // there: this page needs its CSRF token too.
  window.addEventListener('storage', function (e) {
    if (e.key !== KEY || !e.newValue) return;
    var parts = e.newValue.split(':');
    if (learn(+parts[0], +parts[1], 0, false)) extend(false);
  });
  learn(expires, ends, 0, true);
})();
