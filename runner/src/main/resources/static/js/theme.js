/* Running Coach — light/dark theme.
   Runs in <head> before the stylesheet so the first paint already has the right colours.
   No stored choice = follow the operating system, and light when it says nothing. A click stores an explicit choice. */
(function () {
    var KEY = 'rc-theme';
    var root = document.documentElement;
    var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
    var listeners = [];

    function stored() {
        try {
            var v = localStorage.getItem(KEY);
            return v === 'light' || v === 'dark' ? v : null;
        } catch (e) {
            return null;
        }
    }

    /* ?theme=dark / ?theme=light previews a theme without saving it. */
    function fromUrl() {
        var m = /[?&]theme=(light|dark)(?:[&#]|$)/.exec(window.location.search);
        return m ? m[1] : null;
    }

    /* Light unless the operating system explicitly asks for dark. */
    function system() {
        return media && media.matches ? 'dark' : 'light';
    }

    function current() {
        return root.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
    }

    function metaColor(theme) {
        return theme === 'dark' ? '#0d151c' : '#f2f4f5';
    }

    function apply(theme, persist) {
        theme = theme === 'dark' ? 'dark' : 'light';
        root.setAttribute('data-theme', theme);
        if (persist) {
            try {
                localStorage.setItem(KEY, theme);
            } catch (e) {
                /* private mode: the theme still works for this page */
            }
        }
        var meta = document.getElementById('metaThemeColor');
        if (meta) meta.setAttribute('content', metaColor(theme));
        var dark = theme === 'dark';
        document.querySelectorAll('.theme-toggle').forEach(function (button) {
            button.setAttribute('aria-pressed', dark ? 'true' : 'false');
            button.setAttribute('aria-label', dark ? 'Schakel naar licht thema' : 'Schakel naar donker thema');
            button.setAttribute('title', dark ? 'Licht thema' : 'Donker thema');
        });
        listeners.forEach(function (fn) {
            try {
                fn(theme);
            } catch (e) {
                /* a listener must never break the switch */
            }
        });
        window.dispatchEvent(new CustomEvent('rc-themechange', { detail: { theme: theme } }));
    }

    function toggle() {
        var reduced = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        if (!reduced) {
            root.classList.add('theme-switching');
            window.setTimeout(function () {
                root.classList.remove('theme-switching');
            }, 340);
        }
        apply(current() === 'dark' ? 'light' : 'dark', true);
    }

    function wire() {
        document.querySelectorAll('.theme-toggle').forEach(function (button) {
            if (button.__rcThemeWired) return;
            button.__rcThemeWired = true;
            button.addEventListener('click', toggle);
        });
        apply(current(), false);
    }

    /* Set the attribute immediately: this script sits above the stylesheet link. The meta colour goes
       along right away, so a mobile browser bar does not flash the other theme first. */
    var initial = fromUrl() || stored() || system();
    root.setAttribute('data-theme', initial);
    var initialMeta = document.getElementById('metaThemeColor');
    if (initialMeta) initialMeta.setAttribute('content', metaColor(initial));

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', wire);
    } else {
        wire();
    }

    /* While no explicit choice is stored, follow the system when it changes. */
    if (media) {
        var onSystemChange = function () {
            if (!stored() && !fromUrl()) apply(system(), false);
        };
        if (media.addEventListener) media.addEventListener('change', onSystemChange);
        else if (media.addListener) media.addListener(onSystemChange);
    }

    window.RCTheme = {
        current: current,
        apply: function (theme) { apply(theme, true); },
        toggle: toggle,
        onChange: function (fn) { listeners.push(fn); }
    };
})();
