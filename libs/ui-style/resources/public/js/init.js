// Wagoe Framework — Early Init Script
// Loaded as a blocking script in <head> before CSS for FOUC prevention.
//
// 1. Theme detection: reads localStorage or system preference, sets data-theme
//    attribute on <html> before stylesheets parse.
// 2. Async CSS promotion: converts print-media stylesheets to all-media after
//    DOM is ready (works with the media="print" async CSS loading pattern).
// 3. CSRF: attaches the token from <meta name="csrf-token"> to every HTMX request
//    as the X-CSRF-Token header, so state-changing HTMX actions are protected.
// 4. Time zone: records the browser's IANA zone in the wagoe_tz cookie, so the
//    admin shows and reads timestamps in the user's zone (BOU-523). A name, not
//    an offset, so daylight saving is right for any date.

try{var t=localStorage.getItem('wagoe-theme')||((window.matchMedia&&window.matchMedia('(prefers-color-scheme:dark)').matches)?'dark':'light');document.documentElement.setAttribute('data-theme',t)}catch(e){}
document.addEventListener('DOMContentLoaded',function(){for(var l=document.querySelectorAll('link[media=print]'),i=0;i<l.length;i++)l[i].media='all'})
document.addEventListener('htmx:configRequest',function(e){var m=document.querySelector('meta[name="csrf-token"]');if(m&&m.content)e.detail.headers['X-CSRF-Token']=m.content})
try{var z=Intl.DateTimeFormat().resolvedOptions().timeZone;if(z)document.cookie='wagoe_tz='+encodeURIComponent(z)+';path=/;SameSite=Lax;max-age=31536000'}catch(e){}
