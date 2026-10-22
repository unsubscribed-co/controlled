// Controlled's pages carry English and Spanish side by side (class "en" / "es"); this picks one before the page paints:
// ?lang=en|es (the page's switch link, remembered), then an earlier pick, then the browser's first English or Spanish language.
(function () {
  var asked = new URLSearchParams(location.search).get('lang'), saved = null;
  try { saved = localStorage.getItem('controlled-lang'); } catch (e) {}
  var lang = asked === 'en' || asked === 'es' ? asked : saved;
  if (asked && asked === lang) try { localStorage.setItem('controlled-lang', lang); } catch (e) {}
  if (lang !== 'en' && lang !== 'es') {
    var first = (navigator.languages || [navigator.language]).filter(function (l) { return /^(en|es)\b/i.test(l); })[0];
    lang = first ? first.slice(0, 2).toLowerCase() : 'en';
  }
  var root = document.documentElement, title = document.querySelector('title');
  root.lang = lang;
  root.setAttribute('data-lang', lang);
  if (lang === 'es' && title && title.getAttribute('data-es')) title.textContent = title.getAttribute('data-es');
})();
