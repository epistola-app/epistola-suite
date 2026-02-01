/**
 * DOM helper utilities for the vanilla editor adapters
 */

/**
 * Creates a DOM element with attributes, event listeners, and children.
 *
 * @param {string} tag - HTML tag name
 * @param {object} attrs - Attributes and event listeners (use 'on' prefix for events)
 * @param {Array} children - Child elements or text nodes
 * @returns {HTMLElement}
 */
export function createElement(tag, attrs = {}, children = []) {
  const el = document.createElement(tag);

  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null) continue;

    if (key === 'className') {
      el.className = value;
    } else if (key === 'textContent') {
      el.textContent = value;
    } else if (key === 'style') {
      el.style.cssText = value;
    } else if (key.startsWith('on') && typeof value === 'function') {
      const eventName = key.slice(2).toLowerCase();
      el.addEventListener(eventName, value);
    } else {
      el.setAttribute(key, String(value));
    }
  }

  for (const child of children) {
    if (child === null || child === undefined) continue;
    if (typeof child === 'string') {
      el.appendChild(document.createTextNode(child));
    } else {
      el.appendChild(child);
    }
  }

  return el;
}

/**
 * Logs a message to a debug panel element.
 *
 * @param {string} msg
 * @param {string} logId
 * @param {number} maxEntries
 */
export function logCallback(msg, logId = 'callbacks-log', maxEntries = 20) {
  const log = document.getElementById(logId);
  if (!log) return;

  const entry = createElement('div', {
    textContent: `${new Date().toLocaleTimeString()}: ${msg}`,
  });

  log.insertBefore(entry, log.firstChild);

  while (log.children.length > maxEntries) {
    log.removeChild(log.lastChild);
  }
}

const BADGE_COLORS = {
  text: 'bg-secondary',
  container: 'bg-primary',
  conditional: 'bg-warning',
  loop: 'bg-info',
  columns: 'bg-purple',
  table: 'bg-success',
  pagebreak: 'bg-dark',
  pageheader: 'bg-danger',
  pagefooter: 'bg-danger',
};

/** @param {string} type */
export function getBadgeClass(type) {
  return BADGE_COLORS[type] || 'bg-secondary';
}

/** @param {string} type */
export function getBadgeStyle(type) {
  if (type === 'columns') {
    return 'background-color: #6f42c1 !important;';
  }
  return '';
}

const BLOCK_ICONS = {
  text: 'bi-type',
  container: 'bi-box',
  conditional: 'bi-question-circle',
  loop: 'bi-arrow-repeat',
  columns: 'bi-layout-three-columns',
  table: 'bi-table',
  pagebreak: 'bi-dash-lg',
  pageheader: 'bi-arrow-up-square',
  pagefooter: 'bi-arrow-down-square',
};

/** @param {string} type */
export function getBlockIcon(type) {
  return BLOCK_ICONS[type] || 'bi-square';
}

/** @param {HTMLElement} el */
export function clearElement(el) {
  while (el.firstChild) {
    el.removeChild(el.firstChild);
  }
}
