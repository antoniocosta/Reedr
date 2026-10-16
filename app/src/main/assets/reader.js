// Reedr reader page script: reading position and highlights. The article HTML itself is sanitized and contains no scripts.
// Talks to the app through the `Reedr` bridge: onProgress(fraction), onHighlightTap(id), onLinkTap(url).
(function () {
  'use strict';
  var content = document.getElementById('content');
  var init = window.__reedr || {};

  function textNodes() {
    var walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT);
    var nodes = [], n;
    while ((n = walker.nextNode())) nodes.push(n);
    return nodes;
  }
  function fullText(nodes) { return nodes.map(function (n) { return n.data; }).join(''); }

  function commonSuffix(a, b) { var i = 0; while (i < a.length && i < b.length && a[a.length - 1 - i] === b[b.length - 1 - i]) i++; return i; }
  function commonPrefix(a, b) { var i = 0; while (i < a.length && i < b.length && a[i] === b[i]) i++; return i; }

  // Where a highlight's text occurs, choosing the occurrence whose surroundings best match its saved prefix/suffix.
  function locate(text, h) {
    var best = -1, bestScore = -1, from = 0, idx;
    while (h.text && (idx = text.indexOf(h.text, from)) !== -1) {
      var pre = text.slice(Math.max(0, idx - h.prefix.length), idx);
      var suf = text.substr(idx + h.text.length, h.suffix.length);
      var score = commonSuffix(pre, h.prefix) + commonPrefix(suf, h.suffix);
      if (score > bestScore) { bestScore = score; best = idx; }
      from = idx + 1;
    }
    return best;
  }

  function wrap(start, end, id) {
    var nodes = textNodes(), pos = 0;
    for (var i = 0; i < nodes.length && pos < end; i++) {
      var node = nodes[i], len = node.data.length;
      var s = Math.max(start, pos), e = Math.min(end, pos + len);
      if (s < e && node.data.slice(s - pos, e - pos).trim()) {
        if (e - pos < len) node.splitText(e - pos);
        var target = s - pos > 0 ? node.splitText(s - pos) : node;
        var mark = document.createElement('mark');
        mark.setAttribute('data-hid', id);
        target.parentNode.insertBefore(mark, target);
        mark.appendChild(target);
      }
      pos += len;
    }
  }

  function apply(list) {
    list.forEach(function (h) {
      var start = locate(fullText(textNodes()), h);
      if (start >= 0) wrap(start, start + h.text.length, h.id);
    });
  }

  window.ReedrJS = {
    applyHighlights: function (list) { apply(list); },
    removeHighlight: function (id) {
      document.querySelectorAll('mark[data-hid="' + id + '"]').forEach(function (m) {
        while (m.firstChild) m.parentNode.insertBefore(m.firstChild, m);
        m.remove();
      });
      content.normalize();
    },
    // The selected text with 32 characters of context either side, or null.
    captureSelection: function () {
      var sel = window.getSelection();
      if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
      var range = sel.getRangeAt(0);
      if (!content.contains(range.commonAncestorContainer)) return null;
      var text = range.toString();
      if (!text.trim()) return null;
      var before = document.createRange();
      before.setStart(content, 0);
      before.setEnd(range.startContainer, range.startOffset);
      var full = fullText(textNodes());
      var start = before.toString().length;
      if (full.substr(start, text.length) !== text) start = full.indexOf(text);
      if (start < 0) return null;
      sel.removeAllRanges();
      return JSON.stringify({ text: text, prefix: full.slice(Math.max(0, start - 32), start), suffix: full.substr(start + text.length, 32) });
    },
    scrollToHighlight: function (id) {
      var m = document.querySelector('mark[data-hid="' + id + '"]');
      if (!m) return;
      m.scrollIntoView({ block: 'center' });
      m.classList.add('flash');
    },
    setStyle: function (vars) {
      var at = window.scrollY / maxScroll();
      for (var k in vars) document.documentElement.style.setProperty(k, vars[k]);
      window.scrollTo(0, at * maxScroll());
    }
  };

  apply(init.highlights || []);

  function maxScroll() { return Math.max(1, document.documentElement.scrollHeight - window.innerHeight); }
  var timer = null;
  window.addEventListener('scroll', function () {
    if (timer) return;
    timer = setTimeout(function () {
      timer = null;
      Reedr.onProgress(Math.min(1, window.scrollY / maxScroll()));
    }, 300);
  }, { passive: true });

  window.addEventListener('load', function () {
    if (init.progress > 0) window.scrollTo(0, init.progress * maxScroll());
    if (maxScroll() <= 1) Reedr.onProgress(1);
  });

  document.addEventListener('click', function (e) {
    var mark = e.target.closest('mark[data-hid]');
    if (mark) { e.preventDefault(); Reedr.onHighlightTap(Number(mark.getAttribute('data-hid'))); return; }
    var link = e.target.closest('a[href]');
    if (link) { e.preventDefault(); Reedr.onLinkTap(link.href); }
  });
})();
