/**
 * DOM helper utilities for vanilla editor
 */

/**
 * Element attributes including event listeners
 */
export interface ElementAttrs {
  className?: string;
  textContent?: string;
  style?: string;
  [key: string]: unknown;
}

/**
 * Creates a DOM element with attributes, event listeners, and children
 *
 * @param tag - HTML tag name
 * @param attrs - Attributes and event listeners
 *   - For attributes: any string value
 *   - For event listeners: use 'on' prefix (e.g., onClick, onInput)
 *   - Special attrs: className, textContent, style
 * @param children - Child elements or text nodes
 * @returns The created DOM element
 *
 * @example
 * const button = createElement('button', {
 *   className: 'btn btn-primary',
 *   onClick: () => console.log('clicked'),
 *   textContent: 'Click me'
 * });
 */
export function createElement(
  tag: string,
  attrs: ElementAttrs = {},
  children: (Element | string | null | undefined)[] = []
): HTMLElement {
  const el = document.createElement(tag);

  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null) continue;

    if (key === 'className') {
      el.className = value as string;
    } else if (key === 'textContent') {
      el.textContent = value as string;
    } else if (key === 'style') {
      el.style.cssText = value as string;
    } else if (key.startsWith('on') && typeof value === 'function') {
      const eventName = key.slice(2).toLowerCase();
      el.addEventListener(eventName, value as EventListener);
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
 * Log entry type for debug logging
 */
export interface LogEntry {
  timestamp: string;
  message: string;
}

/**
 * Logger for debug/callback messages
 */
export class DebugLogger {
  private logElement: HTMLElement | null = null;
  private maxEntries: number;

  constructor(logId: string, maxEntries = 20) {
    this.logElement = document.getElementById(logId);
    this.maxEntries = maxEntries;
  }

  /**
   * Logs a message to the debug panel
   */
  log(msg: string): void {
    if (!this.logElement) return;

    const entry = createElement('div', {
      textContent: `${new Date().toLocaleTimeString()}: ${msg}`,
    });

    this.logElement.insertBefore(entry, this.logElement.firstChild);

    while (this.logElement.children.length > this.maxEntries) {
      this.logElement.removeChild(this.logElement.lastChild!);
    }
  }
}

/**
 * Global log callback function (for compatibility with PoC)
 */
export function logCallback(msg: string, logId = 'callbacks-log', maxEntries = 20): void {
  const log = document.getElementById(logId);
  if (!log) return;

  const entry = createElement('div', {
    textContent: `${new Date().toLocaleTimeString()}: ${msg}`,
  });

  log.insertBefore(entry, log.firstChild);

  while (log.children.length > maxEntries) {
    log.removeChild(log.lastChild!);
  }
}

/**
 * Block type to Bootstrap badge color mapping
 */
const BADGE_COLORS: Record<string, string> = {
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

/**
 * Gets the badge color class based on block type
 */
export function getBadgeClass(type: string): string {
  return BADGE_COLORS[type] || 'bg-secondary';
}

/**
 * Gets custom badge style for certain block types
 */
export function getBadgeStyle(type: string): string {
  if (type === 'columns') {
    return 'background-color: #6f42c1 !important;';
  }
  return '';
}

/**
 * Block type to Bootstrap Icons mapping
 */
const BLOCK_ICONS: Record<string, string> = {
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

/**
 * Gets the Bootstrap Icon class for a block type
 */
export function getBlockIcon(type: string): string {
  return BLOCK_ICONS[type] || 'bi-square';
}

/**
 * Clears all children from an element
 */
export function clearElement(el: HTMLElement): void {
  while (el.firstChild) {
    el.removeChild(el.firstChild);
  }
}
