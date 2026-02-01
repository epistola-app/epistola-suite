/**
 * DOM helper utilities for the vanilla example
 */

/**
 * Creates a DOM element with attributes, event listeners, and children
 *
 * @param {string} tag - HTML tag name
 * @param {Object} [attrs={}] - Attributes and event listeners
 *   - For attributes: any string value
 *   - For event listeners: use 'on' prefix (e.g., onClick, onInput)
 *   - Special attrs: className, textContent, style
 * @param {Array<Element|string>} [children=[]] - Child elements or text nodes
 * @returns {Element} The created DOM element
 * @example
 * const button = createElement('button', {
 *   className: 'btn btn-primary',
 *   onClick: () => console.log('clicked'),
 *   textContent: 'Click me'
 * });
 */
export function createElement(tag, attrs = {}, children = []) {
  const el = document.createElement(tag);

  for (const [key, value] of Object.entries(attrs)) {
    if (key === 'className') {
      el.className = value;
    } else if (key === 'textContent') {
      el.textContent = value;
    } else if (key === 'style') {
      el.style.cssText = value;
    } else if (key.startsWith('on')) {
      const eventName = key.slice(2).toLowerCase();
      el.addEventListener(eventName, value);
    } else {
      el.setAttribute(key, value);
    }
  }

  for (const child of children) {
    if (typeof child === 'string') {
      el.appendChild(document.createTextNode(child));
    } else if (child) {
      el.appendChild(child);
    }
  }

  return el;
}

/**
 * Logs a callback message to the debug log panel
 *
 * @param {string} msg - The message to log
 * @param {string} [logId='callbacks-log'] - ID of the log container element
 * @param {number} [maxEntries=20] - Maximum number of entries to keep
 * @example
 * logCallback('Template changed');
 * logCallback('Block added', 'custom-log');
 */
export function logCallback(msg, logId = 'callbacks-log', maxEntries = 20) {
  const log = document.getElementById(logId);
  if (!log) return;

  const entry = createElement('div', {
    textContent: `${new Date().toLocaleTimeString()}: ${msg}`
  });

  log.insertBefore(entry, log.firstChild);

  while (log.children.length > maxEntries) {
    log.removeChild(log.lastChild);
  }
}

/**
 * Gets the badge color class based on block type
 *
 * @param {string} type - Block type
 * @returns {string} Bootstrap badge class
 */
export function getBadgeClass(type) {
  switch (type) {
    case 'container':
      return 'bg-primary';
    case 'columns':
    case 'column':
      return 'bg-purple';
    default:
      return 'bg-secondary';
  }
}

/**
 * Gets the badge style for column-related blocks
 *
 * @param {string} type - Block type
 * @returns {string} CSS style string or empty
 */
export function getBadgeStyle(type) {
  if (type === 'columns' || type === 'column') {
    return 'background-color: #6f42c1;';
  }
  return '';
}
