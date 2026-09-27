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

  function findMath(request) {
    if (!request) return null;
    if (request.mathId) {
      var byId = document.getElementById(request.mathId);
      if (byId) return byId;
    }
    return query(request.mathSelector);
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

  function clearHighlight() {
    var nodes = document.querySelectorAll(".voxread-force-highlight");
    for (var i = 0; i < nodes.length; i++) {
      nodes[i].classList.remove("voxread-force-highlight");
    }
  }

  function highlight(request) {
    clearHighlight();
    if (!request) return false;

    if (request.inline) {
      var anchor = query(request.cssSelector) || findMath(request);
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

    var math = findMath(request) || query(request.cssSelector);
    if (!math) {
      warn("voxread: formula element not found", request);
      return false;
    }
    math.classList.add("voxread-force-highlight");
    return true;
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

  function createEl(name) {
    var ns = document.documentElement && document.documentElement.namespaceURI;
    if (ns && ns.indexOf("xhtml") !== -1) {
      return document.createElementNS(ns, name);
    }
    return document.createElement(name);
  }

  function allMath() {
    var found = [];
    var nodes = document.getElementsByTagName("*");
    for (var i = 0; i < nodes.length; i++) {
      if (localName(nodes[i]) === "math") found.push(nodes[i]);
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
      copy.setAttribute("data-vox-break", "row");
      copies.push(copy);
    }
    for (var c = 0; c < copies.length; c++) parent.insertBefore(copies[c], math);
    parent.removeChild(math);
  }

  function holdPage(hold) {
    if (window.Android && Android.setEquationDragHold) Android.setEquationDragHold(hold);
  }

  function bindScroll(scroll) {
    var startX = 0;
    var startLeft = 0;
    scroll.addEventListener("touchstart", function (event) {
      if (!event.touches || !event.touches.length) return;
      startX = event.touches[0].clientX;
      startLeft = scroll.scrollLeft;
      if (scroll.scrollWidth - scroll.clientWidth > 1) holdPage(true);
    }, { passive: true });

    scroll.addEventListener("touchmove", function (event) {
      if (!event.touches || !event.touches.length) return;
      var max = scroll.scrollWidth - scroll.clientWidth;
      if (max <= 1) return;
      var dx = startX - event.touches[0].clientX;
      var next = startLeft + dx;
      if ((next <= 0 && dx < 0) || (next >= max && dx > 0)) {
        holdPage(false);
        return;
      }
      scroll.scrollLeft = Math.max(0, Math.min(max, next));
      holdPage(true);
      event.preventDefault();
      event.stopPropagation();
    }, { passive: false });

    scroll.addEventListener("touchend", function () {
      holdPage(false);
    });
    scroll.addEventListener("touchcancel", function () {
      holdPage(false);
    });
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

  function setMathFontScale(scroll, scale) {
    var math = formulaMath(scroll);
    if (!math) return;
    if (!scale || scale === 1) math.style.fontSize = "";
    else math.style.fontSize = scale + "em";
  }

  function openZoom(equation, scroll) {
    if (equation.getAttribute("data-vox-zoomed") === "true") return;
    var anchor = captureAnchor();
    var savedMaxHeight = scroll.style.maxHeight;
    var placeholder = createEl("div");
    placeholder.setAttribute("class", "vox-formula-placeholder");
    placeholder.style.height = equation.offsetHeight + "px";
    var overlay = createEl("div");
    overlay.setAttribute("class", "vox-formula-overlay");
    var frame = createEl("div");
    frame.setAttribute("class", "vox-formula-overlay-scroll");
    var controls = createEl("div");
    var scale = 1;
    function control(label, onClick) {
      var button = createEl("button");
      button.setAttribute("type", "button");
      button.setAttribute("class", "vox-formula-open");
      button.style.display = "inline-block";
      button.style.marginRight = "0.4em";
      button.appendChild(document.createTextNode(label));
      button.addEventListener("click", function (event) {
        event.preventDefault();
        event.stopPropagation();
        onClick();
      });
      controls.appendChild(button);
    }
    control("缩小", function () {
      scale = Math.max(0.8, Math.round((scale - 0.25) * 100) / 100);
      setMathFontScale(scroll, scale);
    });
    control("放大", function () {
      scale = Math.min(3, Math.round((scale + 0.25) * 100) / 100);
      setMathFontScale(scroll, scale);
    });
    control("关闭", closeZoom);
    scroll.style.maxHeight = "none";
    equation.insertBefore(placeholder, scroll);
    frame.appendChild(controls);
    frame.appendChild(scroll);
    overlay.appendChild(frame);
    document.body.appendChild(overlay);
    equation.setAttribute("data-vox-zoomed", "true");

    function closeZoom() {
      setMathFontScale(scroll, 1);
      scroll.style.maxHeight = savedMaxHeight;
      equation.insertBefore(scroll, placeholder);
      if (placeholder.parentNode) placeholder.parentNode.removeChild(placeholder);
      if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
      equation.removeAttribute("data-vox-zoomed");
      pendingAnchor = anchor || pendingAnchor;
      scheduleRemeasure();
    }

    overlay.addEventListener("click", function (event) {
      if (event.target === overlay) closeZoom();
    });
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
    button.setAttribute("class", "vox-formula-open");
    button.appendChild(document.createTextNode("查看"));
    equation.appendChild(scroll);
    equation.appendChild(button);
    button.addEventListener("click", function (event) {
      event.preventDefault();
      event.stopPropagation();
      openZoom(equation, scroll);
    });
    bindScroll(scroll);
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
    paragraph.parentNode.insertBefore(built.equation, child);
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

  function classifyFormulas() {
    var page = pageMetrics();
    var blocks = Array.prototype.slice.call(document.querySelectorAll(".vox-formula-block"));
    var i;
    for (i = 0; i < blocks.length; i++) {
      if (blocks[i].getAttribute("data-vox-zoomed") === "true") continue;
      clearBreak(blocks[i]);
      blocks[i].classList.remove("is-wide", "is-tall");
      var scroll = blocks[i].querySelector(".vox-formula-scroll");
      if (scroll) scroll.style.maxHeight = "";
    }
    if (blocks.length) blocks[0].offsetHeight;

    for (i = 0; i < blocks.length; i++) {
      var block = blocks[i];
      if (block.getAttribute("data-vox-zoomed") === "true") continue;
      var scroller = block.querySelector(".vox-formula-scroll");
      if (!scroller) continue;
      var naturalHeight = scroller.scrollHeight;
      var wide = scroller.scrollWidth > scroller.clientWidth + 2;
      var tallerThanPage = naturalHeight > page.height - 32;
      if (wide) block.classList.add("is-wide");
      if (tallerThanPage) block.classList.add("is-tall");
      if (tallerThanPage && !page.scrollMode) {
        scroller.style.maxHeight = Math.max(page.height - 96, 80) + "px";
      }
      if (page.scrollMode || page.vertical) continue;
      var target = breakTarget(block);
      var rect = target.getBoundingClientRect();
      var fitsOnePage = target.offsetHeight <= page.height;
      var startsMidPage = rect.top > page.top + 8;
      if (fitsOnePage && startsMidPage && rect.bottom > page.bottom - 2) {
        target.classList.add("vox-break-before");
      }
    }
  }

  function scheduleRemeasure() {
    if (restoringAnchor) return;
    clearTimeout(remeasureTimer);
    remeasureTimer = setTimeout(function () {
      var id = pendingAnchor || captureAnchor();
      classifyFormulas();
      if (!id) return;
      restoringAnchor = true;
      requestAnimationFrame(function () {
        var element = document.getElementById(id);
        var rect = element && element.getBoundingClientRect();
        var visible = rect &&
          rect.top >= 0 &&
          rect.top < window.innerHeight * 0.5 &&
          rect.left >= 0 &&
          rect.left < window.innerWidth;
        if (!visible && window.readium && readium.scrollToId) {
          readium.scrollToId(id);
        }
        restoringAnchor = false;
        pendingAnchor = id;
      });
    }, 60);
  }

  function layoutEquations() {
    if (!document.body) return;
    assignStableMathIds();
    wrapDisplayMath();
    requestAnimationFrame(function () {
      classifyFormulas();
      rememberAnchor();
    });
  }

  window.voxReadClearHighlight = clearHighlight;
  window.voxReadHighlightMath = highlight;
  wrapScroll();
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", function () {
      wrapScroll();
      layoutEquations();
    });
  } else {
    layoutEquations();
  }
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(function () { scheduleRemeasure(); });
  }
  if (document.fonts && document.fonts.addEventListener) {
    document.fonts.addEventListener("loadingdone", function () { scheduleRemeasure(); });
  }
  window.addEventListener("resize", function () { scheduleRemeasure(); });
  window.addEventListener("scroll", rememberAnchor, true);
})();
