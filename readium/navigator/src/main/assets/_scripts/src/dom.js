//
//  Copyright 2022 Readium Foundation. All rights reserved.
//  Use of this source code is governed by the BSD-style license
//  available in the top-level LICENSE file of the project.
//

import {
  getColumnCountPerScreen,
  isRTL,
  isScrollModeEnabled,
  isVerticalWritingMode,
} from "./utils";
import { getCssSelector } from "css-selector-generator";

// See. https://github.com/JayPanoz/architecture/tree/touch-handling/misc/touch-handling
export function nearestInteractiveElement(element) {
  if (element == null) {
    return null;
  }
  var interactiveTags = [
    "a",
    "audio",
    "button",
    "canvas",
    "details",
    "input",
    "label",
    "option",
    "select",
    "submit",
    "textarea",
    "video",
  ];
  if (interactiveTags.indexOf(element.nodeName.toLowerCase()) != -1) {
    return element.outerHTML;
  }

  // Checks whether the element is editable by the user.
  if (
    element.hasAttribute("contenteditable") &&
    element.getAttribute("contenteditable").toLowerCase() != "false"
  ) {
    return element.outerHTML;
  }

  // Checks parents recursively because the touch might be for example on an <em> inside a <a>.
  if (element.parentElement) {
    return nearestInteractiveElement(element.parentElement);
  }

  return null;
}

export function findFirstVisibleLocator() {
  // Column pagination reports the chapter's first block as visible, because
  // getBoundingClientRect does not follow the scrolled column. Hit-test the
  // viewport so reading starts at the text on this page.
  const hit = elementOnScreen();
  const element = (hit && hit.element) || findElement(document.body);
  const snippet = ((hit && hit.snippet) || elementText(element)).slice(0, 80);
  let cssSelector;
  try {
    cssSelector = getCssSelector(element);
  } catch (error) {
    cssSelector = undefined;
  }
  const locations = { progression: currentProgression() };
  if (cssSelector) {
    locations.cssSelector = cssSelector;
  }
  const locator = {
    href: "#",
    type: "application/xhtml+xml",
    locations: locations,
  };
  if (snippet) {
    locator.text = { highlight: snippet };
  }
  return locator;
}

function elementOnScreen() {
  const width = window.innerWidth || document.documentElement.clientWidth || 0;
  const height = window.innerHeight || document.documentElement.clientHeight || 0;
  if (width < 2 || height < 2) {
    return null;
  }
  const columns = isScrollModeEnabled() ? 1 : Math.max(getColumnCountPerScreen() || 1, 1);
  const columnWidth = Math.max(width / columns, 1);
  const xs = isRTL()
    ? [width - 12, width - Math.min(columnWidth * 0.45, 80)]
    : [12, Math.min(columnWidth * 0.45, 80)];
  const step = Math.max(10, Math.round(height / 18));
  for (let i = 0; i < xs.length; i++) {
    for (let y = 6; y < height - 4; y += step) {
      const node = document.elementFromPoint(xs[i], y);
      const element = readingBlock(node);
      if (!element) {
        continue;
      }
      return {
        element: element,
        snippet: snippetAt(xs[i], y) || elementText(element),
      };
    }
  }
  return null;
}

function readingBlock(node) {
  let element = node && node.nodeType === 1 ? node : node && node.parentElement;
  if (!element || (element.closest && element.closest("#readium-virtual-page"))) {
    return null;
  }
  while (element && element !== document.body && element !== document.documentElement) {
    if (!shouldIgnoreElement(element) && elementText(element)) {
      return narrowToHit(element, node);
    }
    element = element.parentElement;
  }
  return null;
}

function narrowToHit(element, hit) {
  const children = element.children;
  for (let i = 0; i < children.length; i++) {
    const child = children[i];
    if ((child === hit || child.contains(hit)) && !shouldIgnoreElement(child) && elementText(child)) {
      return narrowToHit(child, hit);
    }
  }
  return element;
}

function snippetAt(x, y) {
  let range = null;
  if (document.caretRangeFromPoint) {
    range = document.caretRangeFromPoint(x, y);
  } else if (document.caretPositionFromPoint) {
    const position = document.caretPositionFromPoint(x, y);
    if (position && position.offsetNode) {
      range = document.createRange();
      range.setStart(position.offsetNode, position.offset);
    }
  }
  const node = range && range.startContainer;
  if (!node || node.nodeType !== 3) {
    return "";
  }
  const text = node.textContent || "";
  const offset = Math.min(range.startOffset || 0, text.length);
  return text.slice(offset).replace(/\s+/g, " ").trim();
}

function elementText(element) {
  if (!element) {
    return "";
  }
  return (element.innerText || element.textContent || "").replace(/\s+/g, " ").trim();
}

function currentProgression() {
  const scrolling = document.scrollingElement;
  if (!scrolling) {
    return 0;
  }
  if (isScrollModeEnabled() && !isVerticalWritingMode()) {
    const height = scrolling.scrollHeight;
    return height > 0 ? clampUnit(scrolling.scrollTop / height) : 0;
  }
  const width = scrolling.scrollWidth;
  if (width <= 0) {
    return 0;
  }
  let progression = scrolling.scrollLeft / width;
  if (isRTL()) {
    progression = 1 - progression;
  }
  return clampUnit(progression);
}

function clampUnit(value) {
  if (!Number.isFinite(value)) {
    return 0;
  }
  return Math.min(1, Math.max(0, value));
}

function findElement(rootElement) {
  for (var i = 0; i < rootElement.children.length; i++) {
    const child = rootElement.children[i];
    if (!shouldIgnoreElement(child) && isElementVisible(child)) {
      return findElement(child);
    }
  }
  return rootElement;
}

function isElementVisible(element) {
  if (readium.isFixedLayout) return true;

  if (element === document.body || element === document.documentElement) {
    return true;
  }
  if (!document || !document.documentElement || !document.body) {
    return false;
  }

  const rect = element.getBoundingClientRect();
  if (isScrollModeEnabled()) {
    return rect.bottom > 0 && rect.top < window.innerHeight;
  } else {
    return rect.right > 0 && rect.left < window.innerWidth;
  }
}

function shouldIgnoreElement(element) {
  if (!element || element.nodeType !== 1 || element.id === "readium-virtual-page") {
    return true;
  }
  const elStyle = getComputedStyle(element);
  if (elStyle) {
    const display = elStyle.getPropertyValue("display");
    if (
      display === "none" ||
      display === "inline" ||
      display === "inline-block" ||
      display === "contents"
    ) {
      return true;
    }
    // Cannot be relied upon, because web browser engine reports invisible when out of view in
    // scrolled columns!
    // const visibility = elStyle.getPropertyValue("visibility");
    // if (visibility === "hidden") {
    //     return false;
    // }
    const opacity = elStyle.getPropertyValue("opacity");
    if (opacity === "0") {
      return true;
    }
  }

  return false;
}
