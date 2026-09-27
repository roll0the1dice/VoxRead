/*
 * Formula highlight and TTS follow for VoxRead.
 *
 * Locators created during content extraction carry the math element's selector.
 * This script only highlights or measures that element. It does not score text
 * overlap and does not fall back to the first math in the document.
 */
(function () {
  var INLINE_CONTAINER = "p, li, figcaption, dd, blockquote, h1, h2, h3, h4, h5, h6";

  function warn(message, detail) {
    if (window.console && console.warn) {
      console.warn(message, detail || "");
    }
  }

  function query(selector) {
    if (!selector) return null;
    try {
      var found = document.querySelector(selector);
      if (found) return found;
    } catch (error) {
      warn("voxread: selector failed", selector);
    }
    if (selector.indexOf("math") === -1) return null;
    var starred = selector.replace(/(^|[\s>+~])math\b/g, "$1*|math");
    try {
      return document.querySelector(starred);
    } catch (error) {
      warn("voxread: namespace selector failed", starred);
      return null;
    }
  }

  var appliedSeq = -1;
  var lastRequest = null;
  var formulasMeasured = false;

  function pageHref() {
    return document.documentElement.getAttribute("data-vox-href") || "";
  }

  // Same id rule as SpeechAnchors in SpeechMap.kt: vox:{href}#{localId}.
  function localMathId(id) {
    if (!id || id.indexOf("vox:") !== 0) return id || "";
    var hash = id.lastIndexOf("#");
    if (hash < 4) return id;
    return id.substring(hash + 1);
  }

  function findMath(request) {
    if (!request) return null;
    var local = localMathId(request.mathId);
    if (local) {
      var byId = document.getElementById(local);
      if (byId) return byId;
    }
    return query(request.mathSelector || request.selector);
  }

  function formulaNodes(target) {
    var local = localMathId(target && target.mathId);
    var found = [];
    var maths = allMath();
    var i;
    for (i = 0; i < maths.length; i++) {
      var id = maths[i].id || "";
      var source = maths[i].getAttribute("data-vox-source") || "";
      if (local && (id === local || source === local || id.indexOf(local + "-row-") === 0)) {
        found.push(maths[i]);
      }
    }
    if (found.length) return found;
    var fallback = findMath(target) || query(target && target.selector);
    return fallback ? [fallback] : [];
  }

  function usableRect(rect) {
    if (!rect) return false;
    if (!isFinite(rect.top) || !isFinite(rect.left)) return false;
    return rect.width >= 1 || rect.height >= 1;
  }

  function intersectsViewport(rect) {
    var width = window.innerWidth || document.documentElement.clientWidth;
    var height = window.innerHeight || document.documentElement.clientHeight;
    return rect.bottom > 0 && rect.right > 0 && rect.top < height && rect.left < width;
  }

  var textHighlightName = "voxread-tts";

  function clearVisual() {
    var nodes = document.querySelectorAll(".voxread-force-highlight");
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].classList.remove("voxread-force-highlight");
    }
    var boxes = document.querySelectorAll(".voxread-highlight-box");
    for (var j = 0; j < boxes.length; j++) {
      if (boxes[j].parentNode) boxes[j].parentNode.removeChild(boxes[j]);
    }
    if (window.CSS && CSS.highlights) CSS.highlights.delete(textHighlightName);
    var speaking = document.querySelectorAll(".vox-formula-block.is-speaking");
    for (var s = 0; s < speaking.length; s++) {
      speaking[s].classList.remove("is-speaking");
    }
  }

  function clearHighlight(seq) {
    if (typeof seq === "number" && seq < appliedSeq) return false;
    if (typeof seq === "number") appliedSeq = seq;
    lastRequest = null;
    clearVisual();
    return true;
  }

  function clipRect(rect, element) {
    var top = Math.max(rect.top, 0);
    var left = Math.max(rect.left, 0);
    var bottom = Math.min(rect.bottom, window.innerHeight || 0);
    var right = Math.min(rect.right, window.innerWidth || 0);
    var node = element && element.parentNode;
    while (node && node !== document.body && node.nodeType === 1) {
      var style = window.getComputedStyle(node);
      var overflow = (style.overflow || "") + (style.overflowX || "") + (style.overflowY || "");
      if (/(auto|scroll|hidden)/.test(overflow)) {
        var box = node.getBoundingClientRect();
        top = Math.max(top, box.top);
        left = Math.max(left, box.left);
        bottom = Math.min(bottom, box.bottom);
        right = Math.min(right, box.right);
      }
      node = node.parentNode;
    }
    if (right - left < 1 || bottom - top < 1) return null;
    return { top: top, left: left, width: right - left, height: bottom - top };
  }

  function placeBox(rect, element) {
    var visible = clipRect(rect, element);
    if (!visible) return false;
    var box = createEl("div");
    box.setAttribute("class", "voxread-highlight-box");
    box.style.position = "fixed";
    box.style.left = visible.left + "px";
    box.style.top = visible.top + "px";
    box.style.width = visible.width + "px";
    box.style.height = visible.height + "px";
    box.style.pointerEvents = "none";
    document.body.appendChild(box);
    return true;
  }

  function textNodeAt(target) {
    var root = query(target.selector);
    if (!root) return null;
    var nodes = [];
    var children = root.childNodes;
    for (var i = 0; i < children.length; i++) {
      if (children[i].nodeType === 3) nodes.push(children[i]);
    }
    return nodes[target.node] || null;
  }

  function directTextRange(target) {
    var node = textNodeAt(target);
    if (!node || !node.nodeValue) return null;
    var from = Math.max(0, target.from || 0);
    var to = target.to == null ? node.nodeValue.length : target.to;
    to = Math.min(node.nodeValue.length, to);
    if (to <= from) return null;
    var range = document.createRange();
    try {
      range.setStart(node, from);
      range.setEnd(node, to);
    } catch (error) {
      return null;
    }
    return range;
  }

  function findQuoteRange(root, quote) {
    if (!root || !quote) return null;
    var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
    var parts = [];
    var full = "";
    var node;
    while ((node = walker.nextNode())) {
      parts.push({ node: node, start: full.length });
      full += node.nodeValue || "";
    }
    var at = full.indexOf(quote);
    if (at < 0) return null;
    function point(offset) {
      for (var i = 0; i < parts.length; i++) {
        var next = i + 1 < parts.length ? parts[i + 1].start : full.length;
        if (offset <= next) {
          return { node: parts[i].node, offset: offset - parts[i].start };
        }
      }
      return null;
    }
    var start = point(at);
    var end = point(at + quote.length);
    if (!start || !end) return null;
    var range = document.createRange();
    try {
      range.setStart(start.node, start.offset);
      range.setEnd(end.node, end.offset);
    } catch (error) {
      return null;
    }
    return range;
  }

  function textRange(target) {
    var direct = directTextRange(target);
    var quote = target && target.text;
    if (direct && (!quote || direct.toString().indexOf(quote) >= 0)) return direct;
    if (!quote) return direct;
    return findQuoteRange(query(target.selector) || document.body, quote) || direct;
  }

  function paintTextRanges(ranges) {
    if (!ranges.length) return false;
    if (window.CSS && CSS.highlights && typeof Highlight === "function") {
      var paint = new Highlight();
      for (var i = 0; i < ranges.length; i++) paint.add(ranges[i]);
      CSS.highlights.set(textHighlightName, paint);
      return true;
    }
    var found = false;
    for (var r = 0; r < ranges.length; r++) {
      var rects = ranges[r].getClientRects();
      for (var i = 0; i < rects.length; i++) {
        if (usableRect(rects[i])) found = true;
        placeBox(rects[i], ranges[r].startContainer && ranges[r].startContainer.parentNode);
      }
    }
    return found;
  }

  function drawMath(target) {
    var nodes = formulaNodes(target);
    if (!nodes.length) {
      warn("voxread: formula element not found", target);
      return false;
    }
    for (var i = 0; i < nodes.length; i++) {
      var block = nodes[i].closest && nodes[i].closest(".vox-formula-block");
      var box = block || nodes[i];
      box.classList.add("voxread-force-highlight");
      if (block && block.classList.contains("is-preview")) block.classList.add("is-speaking");
      var rect = box.getBoundingClientRect();
      if (usableRect(rect)) placeBox(rect, box);
    }
    return true;
  }

  function drawTargets(request) {
    var targets = (request && request.targets) || [];
    if (!targets.length) return false;
    var textRanges = [];
    var ok = true;
    for (var i = 0; i < targets.length; i++) {
      var target = targets[i];
      if (target && target.kind === "text") {
        var range = textRange(target);
        if (range) textRanges.push(range);
        else ok = false;
      } else if (!drawMath(target)) {
        ok = false;
      }
    }
    if (textRanges.length && !paintTextRanges(textRanges)) ok = false;
    return ok;
  }

  function acceptRequest(request) {
    if (!request) return false;
    if (typeof request.seq === "number" && request.seq < appliedSeq) return false;
    var href = pageHref();
    if (request.href && href && request.href !== href) {
      clearVisual();
      lastRequest = null;
      return false;
    }
    if (request.href && !href) {
      document.documentElement.setAttribute("data-vox-href", request.href);
    }
    if (typeof request.seq === "number") appliedSeq = request.seq;
    return true;
  }

  function paint(request) {
    clearVisual();
    lastRequest = request;
    return drawTargets(request);
  }

  function repaintHighlight() {
    if (!lastRequest) return false;
    return paint(lastRequest);
  }

  function applyHighlight(request) {
    if (!acceptRequest(request)) return false;
    return paint(request);
  }

  function highlight(request) {
    if (!request) {
      clearHighlight();
      return false;
    }
    if (request.targets) return applyHighlight(request);
    if (request.mathId || request.mathSelector) {
      return applyHighlight({
        href: request.href || "",
        seq: request.seq,
        utterance: request.utterance || "",
        targets: [{
          kind: "math",
          mathId: request.mathId || "",
          selector: request.mathSelector || request.cssSelector || ""
        }]
      });
    }
    if (request.inline) {
      if (!acceptRequest(request)) return false;
      clearVisual();
      lastRequest = null;
      var anchor = query(request.cssSelector);
      if (!anchor) {
        warn("voxread: inline formula sentence has no target", request);
        return false;
      }
      var container = anchor.closest(INLINE_CONTAINER);
      if (!container) {
        warn("voxread: no semantic paragraph for inline formula", request);
        return false;
      }
      container.classList.add("voxread-force-highlight");
      return true;
    }
    return false;
  }

  function setPageHref(href) {
    if (!href) return;
    document.documentElement.setAttribute("data-vox-href", href);
  }

  function followTarget(locator) {
    var locations = (locator && locator.locations) || {};
    if (locations.isMath) {
      return query(locations.mathSelector || locations.cssSelector);
    }
    if (locations.hasInlineMath) {
      return query(locations.cssSelector);
    }
    return null;
  }

  function wrapScroll() {
    if (!window.readium || typeof window.readium.scrollToLocator !== "function") return;
    if (window.readium.scrollToLocator.__voxread) return;
    var original = window.readium.scrollToLocator.bind(window.readium);
    function wrapped(locator) {
      if (window.__voxFormulaOpen) return true;
      var locations = (locator && locator.locations) || {};
      if (locations.isMath || locations.hasInlineMath) {
        var element = followTarget(locator);
        if (!element) {
          warn("voxread: TTS target not found", locations.mathSelector || locations.cssSelector);
          return false;
        }
        var rect = element.getBoundingClientRect();
        if (!usableRect(rect)) {
          warn("voxread: TTS target has no layout box", locations.mathSelector || locations.cssSelector);
          return false;
        }
        if (intersectsViewport(rect)) return true;
      }
      return original(locator);
    }
    wrapped.__voxread = true;
    window.readium.scrollToLocator = wrapped;
  }

  var remeasureTimer = 0;
  var anchorTimer = 0;
  var restoringAnchor = false;
  var pendingAnchor = null;
  var restoreReady = false;

  function createEl(name) {
    var ns = document.documentElement && document.documentElement.namespaceURI;
    if (ns && ns.indexOf("xhtml") !== -1) {
      return document.createElementNS(ns, name);
    }
    return document.createElement(name);
  }

  function allMath() {
    var found = [];
    function add(list) {
      if (!list) return;
      for (var i = 0; i < list.length; i++) {
        if (found.indexOf(list[i]) === -1) found.push(list[i]);
      }
    }
    add(document.getElementsByTagName("math"));
    if (document.getElementsByTagNameNS) {
      add(document.getElementsByTagNameNS("*", "math"));
    }
    return found;
  }

  function hasKeptContent(node) {
    var kept = { img: 1, svg: 1, audio: 1, video: 1, a: 1, math: 1 };
    var nodes = node.getElementsByTagName ? node.getElementsByTagName("*") : [];
    for (var i = 0; i < nodes.length; i++) {
      if (kept[localName(nodes[i])]) return true;
    }
    return false;
  }

  function assignStableMathIds() {
    var maths = allMath();
    for (var i = 0; i < maths.length; i++) {
      if (!maths[i].id) maths[i].id = "vox-math-" + i;
    }
  }

  function localName(node) {
    return ((node && (node.localName || node.nodeName)) || "").toLowerCase();
  }

  function closestParagraph(math) {
    var current = math.parentNode;
    while (current && current.nodeType === 1) {
      var name = localName(current);
      if (name === "p" || /^h[1-6]$/.test(name)) return current;
      if (
        name === "div" || name === "section" || name === "body" ||
        name === "li" || name === "figure" || name === "blockquote" ||
        name === "dd" || name === "td" || name === "th"
      ) {
        return null;
      }
      current = current.parentNode;
    }
    return null;
  }

  function containsOnly(container, math) {
    var node = container.firstChild;
    while (node) {
      if (node === math || (node.contains && node.contains(math))) {
        node = node.nextSibling;
        continue;
      }
      if (node.nodeType === 3 && node.nodeValue && node.nodeValue.trim()) return false;
      if (node.nodeType === 1) return false;
      node = node.nextSibling;
    }
    return true;
  }

  function shouldWrap(math) {
    if (!math || (math.closest && math.closest(".vox-formula-block, .vox-formula-overlay"))) return false;
    var display = (math.getAttribute("display") || "").toLowerCase();
    if (display === "inline") return false;
    if (display === "block") return true;
    var block = math.parentNode;
    while (block && block.nodeType === 1 && localName(block) !== "body") {
      var name = localName(block);
      if (
        name === "p" || name === "div" || name === "li" || name === "figure" ||
        name === "section" || name === "blockquote" || name === "dd"
      ) {
        return containsOnly(block, math);
      }
      block = block.parentNode;
    }
    return false;
  }

  function carriedNode(math) {
    var node = math;
    while (node.parentNode && (localName(node.parentNode) === "a" || localName(node.parentNode) === "span")) {
      if (!containsOnly(node.parentNode, math)) break;
      node = node.parentNode;
    }
    return node;
  }

  var FENCE = {
    "(": 1, ")": 1, "[": 1, "]": 1, "{": 1, "}": 1,
    "|": 1, "||": 1, "‖": 1, "∣": 1,
    "⟨": 1, "⟩": 1, "〈": 1, "〉": 1,
    "⌈": 1, "⌉": 1, "⌊": 1, "⌋": 1,
    "⎡": 1, "⎣": 1, "⎤": 1, "⎦": 1,
    "⎧": 1, "⎨": 1, "⎩": 1, "⎫": 1, "⎬": 1, "⎭": 1
  };
  var RELATION = {
    "=": 1, "+": 1, "-": 1, "−": 1, "×": 1, "≈": 1, "≠": 1,
    "≤": 1, "≥": 1, "<": 1, ">": 1, "≡": 1, "⇒": 1, "→": 1
  };

  function elementList(root, name) {
    var found = [];
    var nodes = root.getElementsByTagName ? root.getElementsByTagName("*") : [];
    for (var i = 0; i < nodes.length; i++) {
      if (localName(nodes[i]) === name) found.push(nodes[i]);
    }
    return found;
  }

  function ownText(el) {
    var value = "";
    if (!el) return value;
    for (var i = 0; i < el.childNodes.length; i++) {
      if (el.childNodes[i].nodeType === 3) value += el.childNodes[i].nodeValue;
    }
    return value.replace(/\s+/g, "");
  }

  function elementChildren(el, name) {
    var found = [];
    if (!el) return found;
    for (var i = 0; i < el.childNodes.length; i++) {
      var child = el.childNodes[i];
      if (child.nodeType === 1 && (!name || localName(child) === name)) found.push(child);
    }
    return found;
  }

  function isFence(el) {
    if (!el || localName(el) !== "mo") return false;
    if (FENCE[ownText(el)]) return true;
    return (el.getAttribute("fence") || "").toLowerCase() === "true";
  }

  function ancestorNamed(node, name) {
    var current = node && node.parentNode;
    while (current && current.nodeType === 1) {
      if (localName(current) === name) return current;
      current = current.parentNode;
    }
    return null;
  }

  function tableRows(table) {
    return elementChildren(table).filter(function (el) {
      var name = localName(el);
      return name === "mtr" || name === "mlabeledtr";
    });
  }

  function columnCount(table) {
    var rows = tableRows(table);
    var max = 0;
    for (var i = 0; i < rows.length; i++) {
      var cells = elementChildren(rows[i], "mtd").length;
      if (cells > max) max = cells;
    }
    return max;
  }

  function tableIsFenced(table) {
    if (localName(table.parentNode) === "mfenced") return true;
    var siblings = elementChildren(table.parentNode);
    var index = siblings.indexOf(table);
    if (index < 0) return false;
    return isFence(siblings[index - 1]) || isFence(siblings[index + 1]);
  }

  function columnLooksLikeRelations(table) {
    var rows = tableRows(table);
    if (rows.length < 2) return false;
    var cols = columnCount(table);
    for (var c = 0; c < cols; c++) {
      var hits = 0;
      for (var r = 0; r < rows.length; r++) {
        var cells = elementChildren(rows[r], "mtd");
        var ops = cells[c] ? elementList(cells[c], "mo") : [];
        if (ops.length === 1 && RELATION[ownText(ops[0])]) hits++;
      }
      if (hits >= Math.ceil(rows.length * 0.6)) return true;
    }
    return false;
  }

  function tableIsMatrix(table) {
    var intent = (table.getAttribute("intent") || "").toLowerCase();
    if (/matrix|determinant|cases|piecewise/.test(intent)) return true;
    if (tableIsFenced(table)) return true;
    if ((table.hasAttribute("columnlines") || table.hasAttribute("rowlines")) &&
        columnCount(table) >= 2 &&
        !columnLooksLikeRelations(table)) {
      return true;
    }
    return false;
  }

  /*
   * Matrices, determinants and piecewise functions stay one box.
   * A multi-step derivation is split only when the document says
   * data-vox-break="rows" and the table itself is not a matrix.
   * One MathML blob has no reliable row boundary, so it stays whole.
   */
  function formulaKind(math) {
    var marked = (math.getAttribute("data-vox-kind") || "").toLowerCase();
    if (marked === "matrix" || marked === "determinant" || marked === "piecewise") return "matrix";
    var tables = elementList(math, "mtable");
    for (var i = 0; i < tables.length; i++) {
      if (tableIsMatrix(tables[i])) return "matrix";
    }
    return "equation";
  }

  function splitBreakableRows(math) {
    if ((math.getAttribute("data-vox-break") || "") !== "rows") return;
    var tables = elementList(math, "mtable");
    var table = null;
    for (var i = 0; i < tables.length; i++) {
      if (!tableIsMatrix(tables[i]) && !ancestorNamed(tables[i], "mtable")) {
        table = tables[i];
        break;
      }
    }
    if (!table) return;
    var rows = tableRows(table);
    if (rows.length < 2) return;
    var parent = math.parentNode;
    if (!parent) return;
    var baseId = math.id;
    var copies = [];
    for (var r = 0; r < rows.length; r++) {
      var copy = math.cloneNode(true);
      var copyTable = elementList(copy, "mtable").filter(function (item) {
        return !tableIsMatrix(item) && !ancestorNamed(item, "mtable");
      })[0];
      if (!copyTable) return;
      var copyRows = tableRows(copyTable);
      for (var k = copyRows.length - 1; k >= 0; k--) {
        if (k !== r) copyTable.removeChild(copyRows[k]);
      }
      if (r === 0) {
        if (baseId) copy.id = baseId;
      } else if (baseId) {
        copy.id = baseId + "-row-" + (r + 1);
      } else {
        copy.removeAttribute("id");
      }
      if (baseId) copy.setAttribute("data-vox-source", baseId);
      copy.setAttribute("data-vox-break", "row");
      copies.push(copy);
    }
    for (var c = 0; c < copies.length; c++) parent.insertBefore(copies[c], math);
    parent.removeChild(math);
  }

  function pageMetrics() {
    var root = document.documentElement;
    var style = window.getComputedStyle(root);
    var inlineStyle = root.style;
    var scrollMode = (inlineStyle.getPropertyValue("--USER__view") || "").indexOf("readium-scroll-on") !== -1 ||
      (inlineStyle.getPropertyValue("--USER__scroll") || "").indexOf("readium-scroll-on") !== -1;
    var paddingTop = parseFloat(style.paddingTop) || 0;
    var paddingBottom = parseFloat(style.paddingBottom) || 0;
    var height = (window.innerHeight || root.clientHeight) - paddingTop - paddingBottom;
    var writing = style.writingMode || style.webkitWritingMode || "";
    return {
      scrollMode: scrollMode,
      vertical: writing.indexOf("vertical") === 0,
      top: paddingTop,
      bottom: paddingTop + height,
      height: Math.max(height, 1)
    };
  }

  function breakTarget(block) {
    var parent = block.parentNode;
    if (!parent || !parent.classList) return block;
    if (
      parent.classList.contains("equation") ||
      parent.classList.contains("display-math") ||
      parent.classList.contains("math-block") ||
      (localName(parent) === "figure" && parent.classList.contains("math"))
    ) {
      return parent;
    }
    return block;
  }

  function clearBreak(block) {
    block.classList.remove("vox-break-before");
    var target = breakTarget(block);
    if (target !== block) target.classList.remove("vox-break-before");
  }

  function formulaMath(scroll) {
    var nodes = scroll.getElementsByTagName("*");
    for (var i = 0; i < nodes.length; i++) {
      if (localName(nodes[i]) === "math") return nodes[i];
    }
    return null;
  }

  function exportMath(math) {
    var clone = math.cloneNode(true);
    function keep(node) {
      if (node.getAttribute && node.getAttribute("id")) {
        node.setAttribute("data-vox-node", node.getAttribute("id"));
      }
      var children = node.children || [];
      for (var i = 0; i < children.length; i++) keep(children[i]);
    }
    keep(clone);
    if (!clone.getAttribute("xmlns")) {
      clone.setAttribute("xmlns", "http://www.w3.org/1998/Math/MathML");
    }
    if (math.id) clone.setAttribute("data-vox-source", math.id);
    if (window.XMLSerializer) {
      try {
        return new XMLSerializer().serializeToString(clone);
      } catch (error) {
        warn("voxread: formula serialize failed", error);
      }
    }
    return clone.outerHTML || "";
  }

  function notifyFormulaLayout() {
    if (!window.VoxFormula || !VoxFormula.layoutReady) return;
    try {
      VoxFormula.layoutReady();
    } catch (error) {
      warn("voxread: formula layout notify failed", error);
    }
  }

  function usableColor(color) {
    return color && color !== "transparent" && color !== "rgba(0, 0, 0, 0)";
  }

  function pageColors() {
    var root = window.getComputedStyle(document.documentElement);
    var body = document.body ? window.getComputedStyle(document.body) : root;
    var background = root.getPropertyValue("--USER__backgroundColor") || root.getPropertyValue("--RS__backgroundColor");
    var color = root.getPropertyValue("--USER__textColor") || root.getPropertyValue("--RS__textColor");
    return {
      background: usableColor(background) ? background.trim() : (usableColor(root.backgroundColor) ? root.backgroundColor : (usableColor(body.backgroundColor) ? body.backgroundColor : "")),
      color: usableColor(color) ? color.trim() : (usableColor(body.color) ? body.color : (usableColor(root.color) ? root.color : ""))
    };
  }

  window.voxReadPageColors = function () {
    return JSON.stringify(pageColors());
  };

  // Visible part of the formula, in CSS pixels, so the reader can copy it
  // before the fullscreen window covers the page.
  function previewRect(node) {
    if (!node || !node.getBoundingClientRect) return null;
    var rect = node.getBoundingClientRect();
    var viewport = window.visualViewport;
    var vw = viewport && viewport.width ? viewport.width : window.innerWidth;
    var vh = viewport && viewport.height ? viewport.height : window.innerHeight;
    var ox = viewport ? viewport.offsetLeft : 0;
    var oy = viewport ? viewport.offsetTop : 0;
    var left = Math.max(rect.left, ox);
    var top = Math.max(rect.top, oy);
    var right = Math.min(rect.right, ox + vw);
    var bottom = Math.min(rect.bottom, oy + vh);
    if (right - left < 2 || bottom - top < 2 || vw < 2 || vh < 2) return null;
    return {
      x: left,
      y: top,
      w: right - left,
      h: bottom - top,
      vw: vw,
      vh: vh,
      ox: ox,
      oy: oy
    };
  }

  // A formula is long when it is taller than the page, or wider than the
  // visible column, so a sideways swipe is needed to see the rest.
  window.voxReadLongFormula = function (localId) {
    if (!formulasMeasured) return JSON.stringify({ status: "unmeasured" });
    var nodes = formulaNodes({ mathId: localId });
    if (!nodes.length) return JSON.stringify({ status: "missing" });
    var longMath = null;
    var previewNode = null;
    for (var i = 0; i < nodes.length; i++) {
      var block = nodes[i].closest && nodes[i].closest(".vox-formula-block");
      if (!block) continue;
      var scroll = block.querySelector(".vox-formula-scroll");
      if (scroll && scroll.clientWidth < 2) return JSON.stringify({ status: "unmeasured" });
      if (isLongFormula(block)) {
        longMath = nodes[i];
        previewNode = scroll || nodes[i];
      }
    }
    if (!longMath) return JSON.stringify({ status: "short" });
    var colors = pageColors();
    return JSON.stringify({
      status: "long",
      id: longMath.id || localMathId(localId),
      mathml: exportMath(longMath),
      background: colors.background,
      color: colors.color,
      preview: previewRect(previewNode)
    });
  };

  function openFormula(block) {
    var scroll = block.querySelector(".vox-formula-scroll");
    var math = formulaMath(scroll);
    if (!math || !window.VoxFormula || !VoxFormula.open) return;
    try {
      var colors = pageColors();
      VoxFormula.open(JSON.stringify({
        id: math.id || "",
        mathml: exportMath(math),
        background: colors.background,
        color: colors.color,
        preview: previewRect(scroll || math)
      }));
    } catch (error) {
      warn("voxread: unable to open formula viewer", error);
    }
  }

  function stopPageGesture(event) {
    event.preventDefault();
    event.stopPropagation();
  }

  function buildEquation(math) {
    var scroll = createEl("div");
    scroll.setAttribute("class", "vox-formula-scroll");
    var equation = createEl("div");
    equation.setAttribute("class", "vox-formula-block");
    if (formulaKind(math) === "matrix") {
      equation.classList.add("vox-formula-matrix");
      equation.setAttribute("data-vox-kind", "matrix");
    }
    var button = createEl("button");
    button.setAttribute("type", "button");
    button.setAttribute("class", "vox-formula-expand");
    button.setAttribute("aria-label", "查看完整公式");
    button.appendChild(document.createTextNode("\u2922"));
    var hint = createEl("p");
    hint.setAttribute("class", "vox-formula-hint");
    hint.appendChild(document.createTextNode("点击查看完整公式"));
    var speaking = createEl("p");
    speaking.setAttribute("class", "vox-formula-speak");
    speaking.appendChild(document.createTextNode("正在朗读公式 · 点击展开"));
    equation.appendChild(scroll);
    equation.appendChild(button);
    equation.appendChild(hint);
    equation.appendChild(speaking);
    function open(event) {
      stopPageGesture(event);
      openFormula(equation);
    }
    button.addEventListener("click", open);
    equation.addEventListener("click", open);
    return { equation: equation, scroll: scroll };
  }

  function extractFromParagraph(paragraph, math, built) {
    var child = math;
    while (child.parentNode && child.parentNode !== paragraph) child = child.parentNode;
    var after = createEl(localName(paragraph) || "p");
    var node = child.nextSibling;
    while (node) {
      var next = node.nextSibling;
      after.appendChild(node);
      node = next;
    }
    // The equation is a sibling of the paragraph; child still belongs to the
    // paragraph. Using child here throws NotFoundError and aborts page setup.
    paragraph.parentNode.insertBefore(built.equation, paragraph.nextSibling);
    built.scroll.appendChild(carriedNode(math));
    if (
      child !== math &&
      child.parentNode &&
      child.parentNode !== built.scroll &&
      !child.textContent.trim() &&
      !hasKeptContent(child)
    ) {
      child.parentNode.removeChild(child);
    }
    var empty = !paragraph.textContent.trim() && !hasKeptContent(paragraph);
    if (empty) {
      if (paragraph.id && !built.equation.id) built.equation.id = paragraph.id;
      if (paragraph.parentNode) paragraph.parentNode.removeChild(paragraph);
    }
    if ((after.textContent && after.textContent.trim()) || hasKeptContent(after)) {
      built.equation.parentNode.insertBefore(after, built.equation.nextSibling);
    }
  }

  function wrapDisplayMath() {
    var maths = allMath();
    for (var i = 0; i < maths.length; i++) splitBreakableRows(maths[i]);
    maths = allMath();
    for (var j = 0; j < maths.length; j++) {
      var math = maths[j];
      if (!shouldWrap(math)) continue;
      var built = buildEquation(math);
      var paragraph = closestParagraph(math);
      if (paragraph) {
        extractFromParagraph(paragraph, math, built);
      } else {
        var carried = carriedNode(math);
        carried.parentNode.insertBefore(built.equation, carried);
        built.scroll.appendChild(carried);
      }
    }
  }

  function captureAnchor() {
    var nodes = document.querySelectorAll("[id]");
    for (var i = 0; i < nodes.length; i++) {
      if (!nodes[i].id) continue;
      var rect = nodes[i].getBoundingClientRect();
      if (rect.width < 1 && rect.height < 1) continue;
      if (
        rect.bottom > 12 &&
        rect.top < window.innerHeight * 0.65 &&
        rect.right > 0 &&
        rect.left < window.innerWidth
      ) {
        return nodes[i].id;
      }
    }
    return null;
  }

  function rememberAnchor() {
    if (restoringAnchor) return;
    clearTimeout(anchorTimer);
    anchorTimer = setTimeout(function () {
      var id = captureAnchor();
      if (id) pendingAnchor = id;
    }, 80);
  }

  function contentWidth(node) {
    if (!node) return 0;
    var rect = node.getBoundingClientRect();
    return Math.max(node.scrollWidth || 0, node.offsetWidth || 0, rect.width || 0);
  }

  function visibleColumnWidth() {
    var root = document.documentElement;
    var style = window.getComputedStyle(root);
    var pad = (parseFloat(style.paddingLeft) || 0) + (parseFloat(style.paddingRight) || 0);
    var view = Math.max((window.innerWidth || root.clientWidth) - pad, 1);
    var columns = parseInt(style.columnCount, 10);
    if (!(columns > 1)) columns = 1;
    return view / columns;
  }

  function widerThanColumn(math, scroller) {
    var pageWidth = visibleColumnWidth();
    var box = scroller.clientWidth;
    if (pageWidth > 2 && box > pageWidth) box = pageWidth;
    var width = Math.max(contentWidth(math), scroller.scrollWidth || 0);
    return width > box + 4 || scroller.scrollWidth > scroller.clientWidth + 4;
  }

  function isLongFormula(block) {
    if (!block) return false;
    if (block.classList.contains("is-preview")) return true;
    var scroller = block.querySelector(".vox-formula-scroll");
    var math = formulaMath(scroller);
    if (!scroller || !math || scroller.clientWidth < 2) return false;
    return widerThanColumn(math, scroller);
  }

  function fitPreview(block, page) {
    var scroller = block.querySelector(".vox-formula-scroll");
    var math = formulaMath(scroller);
    if (!scroller || !math) return;
    math.style.fontSize = "";
    scroller.style.maxHeight = "";
    block.classList.remove("is-preview", "is-shrunk");
    var pageWidth = visibleColumnWidth();
    var box = scroller.clientWidth;
    if (box < 2) return;
    if (pageWidth > 2 && pageWidth < box) box = pageWidth;
    var limit = page.height - 48;
    var tooWide = widerThanColumn(math, scroller);
    var tooTall = math.offsetHeight > limit;
    if (tooWide) {
      var ratio = box / Math.max(contentWidth(math), 1);
      var scale = ratio >= 0.85 ? ratio : 0.85;
      math.style.fontSize = (Math.round(scale * 1000) / 1000) + "em";
      if (scale < 0.999) block.classList.add("is-shrunk");
      tooWide = widerThanColumn(math, scroller);
      tooTall = math.offsetHeight > limit;
    }
    if (tooWide || tooTall) {
      block.classList.add("is-preview");
      scroller.style.maxHeight = Math.max(120, Math.min(page.height * 0.42, limit)) + "px";
    }
  }

  function classifyFormulas() {
    scheduleRemeasure();
  }

  var BUDGET_MS = 6;
  var enhanceStarted = false;
  var pumping = false;
  var userScrolled = false;
  var refitTimer = 0;
  var remeasureCount = 0;
  var phase = "idle";
  var nearMath = [];
  var farMath = [];
  var planMath = null;
  var planIndex = 0;
  var planStartedAt = 0;
  var nearCursor = 0;
  var farCursor = 0;
  var wrapCount = 0;
  var fitCount = 0;
  var wrapStartedAt = 0;
  var fitStartedAt = 0;
  var enhanceStartedAt = 0;
  var anchorPoint = null;

  function clock() {
    return (window.performance && performance.now) ? performance.now() : Date.now();
  }

  function inPriorityRange(rect) {
    if (!rect || (rect.width < 1 && rect.height < 1)) return false;
    var height = window.innerHeight || 800;
    var width = window.innerWidth || 400;
    return rect.bottom > -height * 0.25 &&
      rect.top < height * 1.2 &&
      rect.right > -width &&
      rect.left < width * 2;
  }

  function report(stage, extra) {
    if (!window.Android || !Android.voxStage) return;
    try {
      Android.voxStage(stage, extra || "");
    } catch (error) {
      warn("voxread: timing report failed", error);
    }
  }

  function captureAnchorPoint() {
    var id = pendingAnchor || captureAnchor();
    if (!id) return null;
    var element = document.getElementById(id);
    if (!element || !element.getBoundingClientRect) return null;
    var rect = element.getBoundingClientRect();
    return { id: id, top: rect.top, left: rect.left };
  }

  function holdAnchor(point) {
    if (!point || userScrolled) return;
    var element = document.getElementById(point.id);
    if (!element || !element.getBoundingClientRect) return;
    var rect = element.getBoundingClientRect();
    var dx = rect.left - point.left;
    var dy = rect.top - point.top;
    if (Math.abs(dx) < 1 && Math.abs(dy) < 1) return;
    var scrolling = document.scrollingElement;
    if (!scrolling) return;
    restoringAnchor = true;
    scrolling.scrollTop += dy;
    scrolling.scrollLeft += dx;
    restoringAnchor = false;
    pendingAnchor = point.id;
  }

  function wrapSingle(math) {
    if (!shouldWrap(math)) return;
    var built = buildEquation(math);
    var paragraph = closestParagraph(math);
    if (paragraph) {
      extractFromParagraph(paragraph, math, built);
    } else {
      var carried = carriedNode(math);
      carried.parentNode.insertBefore(built.equation, carried);
      built.scroll.appendChild(carried);
    }
    wrapCount++;
  }

  function wrapMath(math) {
    if (!math || !math.parentNode) return;
    var parent = math.parentNode;
    var marker = math.nextSibling;
    var willSplit = (math.getAttribute("data-vox-break") || "") === "rows";
    splitBreakableRows(math);
    if (math.parentNode) {
      wrapSingle(math);
      return;
    }
    if (!willSplit) return;
    var node = parent.firstChild;
    while (node && node !== marker) {
      var next = node.nextSibling;
      if (node.nodeType === 1 && localName(node) === "math" && node.getAttribute("data-vox-break") === "row") {
        wrapSingle(node);
      }
      node = next;
    }
  }

  function clearBlockStyle(block) {
    clearBreak(block);
    var scroll = block.querySelector(".vox-formula-scroll");
    var math = scroll && formulaMath(scroll);
    if (scroll) scroll.style.maxHeight = "";
    if (math) math.style.fontSize = "";
    block.classList.remove("is-preview", "is-shrunk");
    return { block: block, scroll: scroll, math: math, skip: !scroll || !math };
  }

  function readFit(item, page) {
    if (item.skip || item.scroll.clientWidth < 2) {
      item.skip = true;
      return;
    }
    var pageWidth = visibleColumnWidth();
    var box = item.scroll.clientWidth;
    if (pageWidth > 2 && pageWidth < box) box = pageWidth;
    var width = Math.max(contentWidth(item.math), item.scroll.scrollWidth || 0);
    var limit = page.height - 48;
    item.tooWide = width > box + 4 || item.scroll.scrollWidth > item.scroll.clientWidth + 4;
    item.tooTall = item.math.offsetHeight > limit;
    item.limit = limit;
    item.page = page;
    item.scale = 1;
    if (item.tooWide) {
      var ratio = box / Math.max(width, 1);
      item.scale = ratio >= 0.85 ? ratio : 0.85;
    }
  }

  function writeScale(item) {
    if (item.skip || !(item.scale < 0.999)) return;
    item.math.style.fontSize = (Math.round(item.scale * 1000) / 1000) + "em";
    item.block.classList.add("is-shrunk");
    item.scaled = true;
  }

  function readConfirm(item) {
    if (item.skip) return;
    if (!item.scaled) {
      item.stillWide = item.tooWide;
      item.stillTall = item.tooTall;
      return;
    }
    item.stillWide = widerThanColumn(item.math, item.scroll);
    item.stillTall = item.math.offsetHeight > item.limit;
  }

  function writePreview(item) {
    if (item.skip) return;
    if (item.stillWide || item.stillTall) {
      item.block.classList.add("is-preview");
      item.scroll.style.maxHeight = Math.max(120, Math.min(item.page.height * 0.42, item.limit)) + "px";
    }
  }

  function writeBreak(item) {
    if (item.skip || !item.page || item.page.scrollMode || item.page.vertical) return;
    var target = breakTarget(item.block);
    var rect = target.getBoundingClientRect();
    var fitsOnePage = target.offsetHeight <= item.page.height;
    var startsMidPage = rect.top > item.page.top + 8;
    if (fitsOnePage && startsMidPage && rect.bottom > item.page.bottom - 2) {
      target.classList.add("vox-break-before");
    }
  }

  function unfittedBlocks() {
    return Array.prototype.slice.call(
      document.querySelectorAll(".vox-formula-block:not([data-vox-fit])")
    );
  }

  function takeFitBatch() {
    var blocks = unfittedBlocks();
    if (!blocks.length) return [];
    if (phase !== "refit") return blocks.slice(0, 4);
    var near = [];
    var scanned = Math.min(blocks.length, 12);
    var i;
    for (i = 0; i < scanned; i++) {
      if (inPriorityRange(blocks[i].getBoundingClientRect())) near.push(blocks[i]);
    }
    if (near.length) return near.slice(0, 4);
    var step = Math.max(1, Math.floor(blocks.length / 8));
    for (i = scanned; i < blocks.length; i += step) {
      if (inPriorityRange(blocks[i].getBoundingClientRect())) return [blocks[i]];
    }
    return [blocks[0]];
  }

  function fitBatch(blocks) {
    var page = pageMetrics();
    var items = [];
    var i;
    for (i = 0; i < blocks.length; i++) items.push(clearBlockStyle(blocks[i]));
    for (i = 0; i < items.length; i++) readFit(items[i], page);
    for (i = 0; i < items.length; i++) writeScale(items[i]);
    for (i = 0; i < items.length; i++) readConfirm(items[i]);
    for (i = 0; i < items.length; i++) writePreview(items[i]);
    for (i = 0; i < items.length; i++) {
      writeBreak(items[i]);
      items[i].block.setAttribute("data-vox-fit", "1");
    }
  }

  function advance() {
    if (phase === "plan") {
      if (!planMath) {
        var started = clock();
        assignStableMathIds();
        planMath = allMath();
        planIndex = 0;
        nearMath = [];
        farMath = [];
        planStartedAt = clock();
        report("scan", "count=" + planMath.length + " ms=" + Math.round(planStartedAt - started));
      }
      var slice = clock();
      while (planIndex < planMath.length && clock() - slice < BUDGET_MS) {
        if (inPriorityRange(planMath[planIndex].getBoundingClientRect())) nearMath.push(planMath[planIndex]);
        else farMath.push(planMath[planIndex]);
        planIndex++;
      }
      if (planIndex < planMath.length) return true;
      nearCursor = 0;
      farCursor = 0;
      report("plan", "near=" + nearMath.length + " far=" + farMath.length + " ms=" + Math.round(clock() - planStartedAt));
      wrapStartedAt = clock();
      fitStartedAt = 0;
      phase = "wrapNear";
      return true;
    }
    if (phase === "wrapNear") {
      if (nearCursor < nearMath.length) {
        wrapMath(nearMath[nearCursor++]);
        return true;
      }
      report("wrap", "count=" + wrapCount + " ms=" + Math.round(clock() - wrapStartedAt) + " part=near");
      fitStartedAt = clock();
      phase = "fitNear";
      return true;
    }
    if (phase === "fitNear" || phase === "fitFar" || phase === "refit") {
      var batch = takeFitBatch();
      if (!batch.length) {
        if (phase === "fitNear") {
          formulasMeasured = true;
          notifyFormulaLayout();
          report("fit", "count=" + fitCount + " ms=" + Math.round(clock() - fitStartedAt) + " part=near");
          phase = "wrapFar";
        } else {
          phase = "done";
        }
        return phase !== "done";
      }
      fitBatch(batch);
      fitCount += batch.length;
      holdAnchor(anchorPoint);
      return true;
    }
    if (phase === "wrapFar") {
      if (farCursor < farMath.length) {
        wrapMath(farMath[farCursor++]);
        return true;
      }
      report("wrap", "count=" + wrapCount + " ms=" + Math.round(clock() - wrapStartedAt) + " part=all");
      fitStartedAt = clock();
      phase = "fitFar";
      return true;
    }
    return false;
  }

  function finishEnhance() {
    formulasMeasured = true;
    notifyFormulaLayout();
    repaintHighlight();
    report("enhanceDone", "wrap=" + wrapCount + " fit=" + fitCount + " ms=" + Math.round(clock() - enhanceStartedAt));
  }

  function runSlice() {
    var start = clock();
    var steps = 0;
    while (phase !== "done" && clock() - start < BUDGET_MS && steps < 24) {
      if (!advance()) break;
      steps++;
    }
    pumping = false;
    if (phase !== "done") {
      pump();
      return;
    }
    finishEnhance();
  }

  function pump() {
    if (pumping || phase === "done" || phase === "idle") return;
    pumping = true;
    setTimeout(runSlice, 0);
  }

  function invalidateFits() {
    var blocks = document.querySelectorAll(".vox-formula-block[data-vox-fit]");
    for (var i = 0; i < blocks.length; i++) blocks[i].removeAttribute("data-vox-fit");
    if (phase === "done") phase = "refit";
  }

  function scheduleRemeasure() {
    if (window.__voxSkipFormulaEnhance || restoringAnchor) return;
    if (refitTimer) return;
    refitTimer = setTimeout(function () {
      refitTimer = 0;
      remeasureCount++;
      if (!enhanceStarted) {
        report("remeasure", "count=" + remeasureCount + " deferred=1");
        return;
      }
      anchorPoint = captureAnchorPoint() || anchorPoint;
      invalidateFits();
      report("remeasure", "count=" + remeasureCount);
      if (phase === "done") phase = "refit";
      fitStartedAt = clock();
      pump();
    }, 60);
  }

  function beginEnhance(anchorId) {
    if (window.__voxSkipFormulaEnhance) {
      report("enhance", "skipped=1 math=" + allMath().length);
      return;
    }
    if (enhanceStarted) return;
    if (!document.body) return;
    enhanceStarted = true;
    enhanceStartedAt = clock();
    if (anchorId) pendingAnchor = anchorId;
    anchorPoint = captureAnchorPoint();
    phase = "plan";
    report("enhance", "start=1");
    pump();
  }

  function startLayout() {
    wrapScroll();
    report("bridge", "skip=" + (window.__voxSkipFormulaEnhance ? "1" : "0"));
  }

  window.voxBeginEnhance = beginEnhance;

  window.voxReadClearHighlight = clearHighlight;
  window.voxReadHighlightMath = highlight;
  window.voxReadApplyHighlight = applyHighlight;
  window.voxReadSetPageHref = setPageHref;
  wrapScroll();
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", startLayout);
  } else {
    startLayout();
  }
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(function () { scheduleRemeasure(); });
  }
  if (document.fonts && document.fonts.addEventListener) {
    document.fonts.addEventListener("loadingdone", function () { scheduleRemeasure(); });
  }
  window.addEventListener("resize", function () {
    scheduleRemeasure();
    repaintHighlight();
  });
  function noteUserScroll() {
    userScrolled = true;
  }
  window.addEventListener("touchstart", noteUserScroll, true);
  window.addEventListener("pointerdown", noteUserScroll, true);
  window.addEventListener("scroll", function () {
    restoreReady = true;
    rememberAnchor();
    repaintHighlight();
  }, true);
})();
