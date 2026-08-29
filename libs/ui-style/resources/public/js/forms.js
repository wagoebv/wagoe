/**
 * Form Interactions
 *
 * Swaps in a re-rendered form returned with a 4xx, which htmx discards by
 * default.
 */

(function() {
  'use strict';

  // htmx marks [45].. as `swap: false` — right for a 500 or a 404, wrong for a
  // failed form POST, which this application answers with the same form
  // re-rendered and a 400. Opted in per response rather than globally: the body
  // must carry field markup, so a JSON error or an error page still falls
  // through to htmx:responseError and the transport toast (BOU-381).
  document.body.addEventListener('htmx:beforeSwap', function(event) {
    const status = event.detail.xhr.status;
    if (status !== 400 && status !== 422) return;

    // The body decides, not the element: on beforeSwap `event.detail.elt` is the
    // swap target, which for hx-target="#create-user-form" is a div, not a form.
    const body = event.detail.xhr.responseText || '';
    if (!/class="(field-errors|validation-errors|form-field)"/.test(body)) return;

    event.detail.shouldSwap = true;
    // Otherwise htmx still fires htmx:responseError and admin-ux.js shows a
    // "something went wrong" toast beside the specific message just swapped in.
    event.detail.isError = false;
  });

})();
