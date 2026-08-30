/**
 * Form Interactions
 *
 * Swaps in a re-rendered form returned with a 4xx, which htmx discards by
 * default.
 */

(function() {
  'use strict';

  // Any one of these marks a body as a re-rendered form rather than an error page.
  const FORM_MARKERS = '.field-errors, .validation-errors, .form-field';

  // htmx marks [45].. as `swap: false` — right for a 500 or a 404, wrong for a
  // failed form POST, which this application answers with the same form
  // re-rendered and a 400. Opted in per response rather than globally: the body
  // must carry field markup, so a JSON error or an error page is left alone.
  // Nothing else picks those up — the only htmx:responseError listener is in
  // admin-ux.js, it is not in the base bundle, and it restores the table
  // rather than reporting anything. A handler that wants to be seen answers
  // with the form (BOU-381).
  document.body.addEventListener('htmx:beforeSwap', function(event) {
    const status = event.detail.xhr.status;
    if (status !== 400 && status !== 422) return;

    // The body decides, not the element: on beforeSwap `event.detail.elt` is the
    // swap target, which for hx-target="#create-user-form" is a div, not a form.
    // Parsed rather than pattern-matched: a regex over the raw HTML cannot tell
    // class="form-field" from class="form-field col-6", and admin's detail
    // fields render the second.
    const body = event.detail.xhr.responseText || '';
    if (!body) return;
    const parsed = new DOMParser().parseFromString(body, 'text/html');
    if (!parsed.querySelector(FORM_MARKERS)) return;

    event.detail.shouldSwap = true;
    // The form we just swapped in is the intended answer, so this is not a
    // failed request; leaving isError set fires htmx:responseError for a
    // response that was handled.
    event.detail.isError = false;
  });

})();
