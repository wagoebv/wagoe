/**
 * Form Interactions
 *
 * Handles:
 * - Rendering server-side validation errors returned with a 4xx
 * - HTMX form event handling
 */

(function() {
  'use strict';

  // Swap 422 and 400 responses that carry a re-rendered form.
  //
  // htmx's default responseHandling marks [45].. as `swap: false`, which is the
  // right default for a 500 or a 404 — you do not want a stack trace or an error
  // page injected into the element that made the request. But this application
  // answers a failed form POST with the same form, re-rendered, with the field
  // errors filled in and a 400. That is a response the user must see, and htmx
  // was discarding it: pressing "Create User" with an invalid email did nothing
  // at all, no message, no change (BOU-381).
  //
  // Rather than widening the global config to swap every 4xx, this opts in per
  // response: only when the target is a form and the body actually looks like a
  // re-rendered form is the swap allowed. A 400 from anything else — a JSON
  // error, an HTML error page — still falls through to htmx:responseError and
  // the transport-failure toast in admin-ux.js.
  document.body.addEventListener('htmx:beforeSwap', function(event) {
    const status = event.detail.xhr.status;
    if (status !== 400 && status !== 422) return;

    // The response body decides, not the element. `event.detail.elt` on
    // beforeSwap is the swap *target* — for hx-target="#create-user-form" that
    // is a div, not the form that submitted — so gating on tagName === 'FORM'
    // silently rejected every real case.
    //
    // A re-rendered form carries its field markup back. Requiring that means a
    // 400 whose body is a JSON error or an HTML error page is not mistaken for
    // one, which would replace the form with something nobody can submit. Those
    // still fall through to htmx:responseError and the transport toast.
    const body = event.detail.xhr.responseText || '';
    if (!/class="(field-errors|validation-errors|form-field)"/.test(body)) return;

    event.detail.shouldSwap = true;
    // Without this htmx still treats the response as an error and fires
    // htmx:responseError, which admin-ux.js answers with a "something went
    // wrong" toast — beside the specific, accurate message just swapped in.
    event.detail.isError = false;
  });

  // Report constraint-validation failures instead of stopping silently.
  //
  // htmx checks HTML5 form validity before issuing a request and aborts when it
  // fails. htmx.config.reportValidityOfForms defaults to false, so the browser's
  // own message is suppressed too — and typing "not-an-email" into a
  // type="email" field made the submit button do nothing whatsoever: no request,
  // no message, no state change (BOU-381).
  //
  // This is the other half of the same defect. The 400 path above only runs once
  // a request is actually made, and for a malformed email one never was.
  document.body.addEventListener('htmx:validation:halted', function(event) {
    const form = event.detail.elt;
    if (!form || typeof form.reportValidity !== 'function') return;
    form.reportValidity();
  });

})();
