/* ==========================================================================
   Lumen Glass — landing page behaviour
   https://subhankarcodegeek.github.io/lumen-glass/

   Everything here is progressive enhancement. Markup, navigation, the rim
   profile explorer and all code samples work with JavaScript disabled; this
   file only adds the sticky-header state, the mobile drawer's "close on
   navigate" behaviour, reveal transitions, the hero loop and copy buttons.
   ========================================================================== */

(function () {
  'use strict';

  var reduceMotion = window.matchMedia
    ? window.matchMedia('(prefers-reduced-motion: reduce)')
    : { matches: false };

  /* -- Sticky header state ------------------------------------------------- */

  function initHeader() {
    var header = document.getElementById('site-header');
    if (!header) return;

    function update() {
      header.classList.toggle('is-stuck', window.scrollY > 8);
    }

    // Toggling one class is cheaper than scheduling a frame, and unlike a
    // requestAnimationFrame wrapper it still runs where frames are throttled.
    window.addEventListener('scroll', update, { passive: true });
    update();
  }

  /* -- Mobile drawer ------------------------------------------------------- */

  function initDrawer() {
    var drawer = document.getElementById('nav-drawer');
    if (!drawer) return;

    // Close after following a link, otherwise the panel covers the section the
    // visitor just navigated to. Closing is deferred by a task: collapsing the
    // <details> synchronously removes the link from the layout before the
    // browser runs the fragment navigation, which can swallow the jump.
    drawer.addEventListener('click', function (event) {
      var link = event.target.closest ? event.target.closest('a') : null;
      if (!link) return;
      window.setTimeout(function () {
        drawer.open = false;
      }, 0);
    });

    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && drawer.open) {
        drawer.open = false;
        var summary = drawer.querySelector('summary');
        if (summary) summary.focus();
      }
    });

    document.addEventListener('click', function (event) {
      if (drawer.open && !drawer.contains(event.target)) drawer.open = false;
    });
  }

  /* -- Copy buttons -------------------------------------------------------- */

  function legacyCopy(text) {
    var area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.top = '-1000px';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();

    var copied = false;
    try {
      copied = document.execCommand('copy');
    } catch (error) {
      copied = false;
    }

    document.body.removeChild(area);
    return copied;
  }

  function writeClipboard(text) {
    if (navigator.clipboard && window.isSecureContext) {
      return navigator.clipboard.writeText(text).catch(function () {
        return legacyCopy(text) ? undefined : Promise.reject(new Error('copy failed'));
      });
    }
    return legacyCopy(text) ? Promise.resolve() : Promise.reject(new Error('copy failed'));
  }

  function selectCode(code) {
    var range = document.createRange();
    range.selectNodeContents(code);
    var selection = window.getSelection();
    if (!selection) return;
    selection.removeAllRanges();
    selection.addRange(range);
  }

  function announce(message) {
    var region = document.getElementById('copy-status');
    if (!region) {
      region = document.createElement('div');
      region.id = 'copy-status';
      region.className = 'sr-only';
      region.setAttribute('role', 'status');
      region.setAttribute('aria-live', 'polite');
      document.body.appendChild(region);
    }
    region.textContent = message;
  }

  function initCopyButtons() {
    var buttons = document.querySelectorAll('[data-copy]');
    if (!buttons.length) return;

    Array.prototype.forEach.call(buttons, function (button) {
      var defaultLabel = button.textContent;
      var timer = null;

      button.addEventListener('click', function () {
        var target = document.getElementById(button.getAttribute('data-copy'));
        if (!target) return;

        writeClipboard(target.textContent)
          .then(function () {
            button.dataset.state = 'done';
            button.textContent = 'Copied';
            announce('Copied to clipboard.');
          })
          .catch(function () {
            button.dataset.state = 'error';
            button.textContent = 'Failed';
            // Leave the code selected so the visitor can use their own shortcut.
            selectCode(target);
            announce('Copy failed. The code is selected — press your copy shortcut.');
          })
          .then(function () {
            window.clearTimeout(timer);
            timer = window.setTimeout(function () {
              button.removeAttribute('data-state');
              button.textContent = defaultLabel;
            }, 2000);
          });
      });
    });
  }

  /* -- Reveal transitions -------------------------------------------------- */

  function initReveal() {
    var targets = document.querySelectorAll('[data-reveal]');
    if (!targets.length) return;

    function reveal(target) {
      target.classList.add('is-visible');
    }

    // No IntersectionObserver (or motion is unwelcome): show everything now.
    if (!('IntersectionObserver' in window) || reduceMotion.matches) {
      Array.prototype.forEach.call(targets, reveal);
      return;
    }

    // Reveal what is already on screen synchronously, so the first paint never
    // shows blank space while waiting for the first observer callback.
    var viewportHeight = window.innerHeight || document.documentElement.clientHeight;
    Array.prototype.forEach.call(targets, function (target) {
      if (target.getBoundingClientRect().top < viewportHeight * 0.92) reveal(target);
    });

    var delivered = false;
    var observer = new IntersectionObserver(
      function (entries) {
        delivered = true;
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          reveal(entry.target);
          observer.unobserve(entry.target);
        });
      },
      { rootMargin: '0px 0px -6% 0px', threshold: 0.06 }
    );

    Array.prototype.forEach.call(targets, function (target) {
      observer.observe(target);
    });

    // Failsafe: if the observer never reports (some embedded and headless
    // renderers never deliver callbacks), show the content rather than leaving
    // the page invisible.
    window.setTimeout(function () {
      if (delivered) return;
      Array.prototype.forEach.call(targets, reveal);
    }, 1500);

    // Jumping straight to an anchor skips the sections in between, so reveal
    // them rather than leaving the page with holes if the visitor scrolls back.
    window.addEventListener('hashchange', function () {
      Array.prototype.forEach.call(targets, reveal);
    });
  }

  /* -- Hero loop ----------------------------------------------------------- */

  function initHeroVideo() {
    var video = document.getElementById('hero-video');
    var toggle = document.getElementById('media-toggle');
    if (!video || !toggle) return;

    if (typeof video.play !== 'function') return;

    toggle.hidden = false;

    function setLabel() {
      var paused = video.paused;
      toggle.textContent = paused ? 'Play' : 'Pause';
      toggle.setAttribute(
        'aria-label',
        paused ? 'Play the demo recording' : 'Pause the demo recording'
      );
    }

    function togglePlayback() {
      if (video.paused) {
        video.dataset.userPaused = '';
        video.play().catch(function () {});
      } else {
        video.dataset.userPaused = 'true';
        video.pause();
      }
    }

    toggle.addEventListener('click', togglePlayback);
    video.addEventListener('play', setLabel);
    video.addEventListener('pause', setLabel);
    setLabel();

    // Reduced motion: keep the poster and let the visitor opt in by pressing Play.
    if (reduceMotion.matches) return;

    // Only spend bandwidth and battery while the stage is actually on screen.
    if ('IntersectionObserver' in window) {
      var observer = new IntersectionObserver(
        function (entries) {
          entries.forEach(function (entry) {
            if (entry.isIntersecting) {
              if (video.dataset.userPaused) return;
              video.play().catch(function () {});
            } else {
              video.pause();
            }
          });
        },
        { threshold: 0.25 }
      );
      observer.observe(video);
    } else {
      video.play().catch(function () {});
    }
  }

  /* -- Optical console (CSS approximation) --------------------------------- */

  function initConsole() {
    var panel = document.getElementById('console-panel');
    if (!panel) return;

    var knobs = document.querySelectorAll('[data-knob]');
    if (!knobs.length) return;

    function setValue(knob) {
      var name = knob.getAttribute('data-knob');
      var raw = Number(knob.value);
      var output = document.getElementById('out-' + name);

      if (name === 'frost') {
        panel.style.setProperty('--frost', raw + 'px');
        if (output) output.textContent = raw + ' px';
      } else if (name === 'tint') {
        panel.style.setProperty('--tint', String(raw / 100));
        if (output) output.textContent = (raw / 100).toFixed(2);
      } else if (name === 'rim') {
        panel.style.setProperty('--rim', String(raw / 100));
        if (output) output.textContent = (raw / 100).toFixed(2);
      }
    }

    Array.prototype.forEach.call(knobs, function (knob) {
      setValue(knob);
      knob.addEventListener('input', function () {
        setValue(knob);
      });
    });
  }

  /* -- Boot ---------------------------------------------------------------- */

  function boot() {
    initHeader();
    initDrawer();
    initCopyButtons();
    initReveal();
    initHeroVideo();
    initConsole();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
