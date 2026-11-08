// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

/**
 * Feedback FAB with page issues popover.
 *
 * Self-contained module that creates a floating action button (bottom-right)
 * showing open feedback items for the current page. All data loading is
 * declarative HTMX — JS only handles DOM creation and visibility toggling.
 *
 * Usage (from feedback.js, driven by the footer fragment's
 * [data-support-request-fab][data-tenant-id] marker element):
 *   import { initSupportRequestFab } from './support-request-fab.js';
 *   initSupportRequestFab('acme');
 */

// Lucide message-square-plus icon
const fabIcon = `<svg xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/><line x1="12" y1="7" x2="12" y2="13"/><line x1="9" y1="10" x2="15" y2="10"/></svg>`;

function injectStyles() {
  if (document.getElementById('support-request-fab-styles')) return;
  const style = document.createElement('style');
  style.id = 'support-request-fab-styles';
  style.textContent = `
.support-request-fab {
    position: fixed;
    bottom: calc(var(--app-footer-height, 0px) + var(--ep-space-5, 1.25rem));
    right: var(--ep-space-5, 1.25rem);
    z-index: 1000;
    width: 48px;
    height: 48px;
    border-radius: 50%;
    border: 1px solid var(--ep-border, #e2e8f0);
    background: var(--ep-background, #fff);
    color: var(--ep-foreground, #0f172a);
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    box-shadow: 0 2px 8px rgba(0,0,0,.12);
    transition: box-shadow .15s, transform .15s;
    padding: 0;
}
/* Room under the page's content for the button, so at the end of a page it never sits on the
   last row's actions. Injected with the button, so a page without it keeps its own spacing. */
main.main-content {
    padding-bottom: calc(48px + var(--ep-space-5, 1.25rem));
}
.support-request-fab:hover {
    box-shadow: 0 4px 16px rgba(0,0,0,.18);
    transform: translateY(-1px);
}
.support-request-fab svg {
    width: 22px;
    height: 22px;
}
.support-request-fab-badge {
    position: absolute;
    top: -4px;
    right: -4px;
    min-width: 20px;
    height: 20px;
    border-radius: 10px;
    background: var(--ep-destructive, #ef4444);
    color: #fff;
    font-size: 11px;
    font-weight: 600;
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 0 5px;
    line-height: 1;
    pointer-events: none;
}
.support-request-fab-badge--hidden {
    display: none;
}
.support-request-popover {
    position: fixed;
    bottom: calc(var(--app-footer-height, 0px) + var(--ep-space-5, 1.25rem) + 56px);
    right: var(--ep-space-5, 1.25rem);
    z-index: 999;
    width: 340px;
    max-height: 60vh;
    background: var(--ep-background, #fff);
    border: 1px solid var(--ep-border, #e2e8f0);
    border-radius: var(--ep-radius-lg, 0.5rem);
    box-shadow: 0 8px 30px rgba(0,0,0,.14);
    overflow-y: auto;
    display: none;
}
.support-request-popover--open {
    display: block;
}
.support-request-popover-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--ep-space-3, 0.75rem) var(--ep-space-4, 1rem);
    border-bottom: 1px solid var(--ep-border, #e2e8f0);
    position: sticky;
    top: 0;
    background: var(--ep-background, #fff);
}
.support-request-popover-title {
    font-weight: 600;
    font-size: var(--ep-text-sm, 0.875rem);
}
.support-request-popover-empty {
    padding: var(--ep-space-6, 1.5rem) var(--ep-space-4, 1rem);
    text-align: center;
    color: var(--ep-muted-foreground, #64748b);
    font-size: var(--ep-text-sm, 0.875rem);
}
.support-request-popover-list {
    list-style: none;
    margin: 0;
    padding: 0;
}
.support-request-popover-item {
    padding: var(--ep-space-3, 0.75rem) var(--ep-space-4, 1rem);
    border-bottom: 1px solid var(--ep-border, #e2e8f0);
}
.support-request-popover-item:last-child {
    border-bottom: none;
}
.support-request-popover-item-title {
    font-weight: 500;
    font-size: var(--ep-text-sm, 0.875rem);
    color: var(--ep-foreground, #0f172a);
    text-decoration: none;
    display: block;
    margin-bottom: var(--ep-space-1, 0.25rem);
}
.support-request-popover-item-title:hover {
    text-decoration: underline;
}
.support-request-popover-item-meta {
    display: flex;
    gap: var(--ep-space-2, 0.5rem);
}
`;
  document.head.appendChild(style);
}

export function initSupportRequestFab(tenantId) {
  if (!tenantId) return;

  // Clean up previous instance (boosted navigation re-init)
  document.getElementById('support-request-fab-container')?.remove();

  injectStyles();

  const pathname = window.location.pathname;
  const searchUrl = `/tenants/${tenantId}/support/requests/search?url=${encodeURIComponent(pathname)}`;

  // -- DOM --------------------------------------------------------------

  const container = document.createElement('div');
  container.id = 'support-request-fab-container';

  container.innerHTML = `
<button id="support-request-fab" class="support-request-fab" type="button" title="Support requests for this page" aria-label="Support requests for this page">
    ${fabIcon}
    <span id="support-request-fab-badge" class="support-request-fab-badge support-request-fab-badge--hidden"></span>
</button>

<div id="support-request-popover" class="support-request-popover">
    <div id="support-request-popover-content"
         hx-get="${searchUrl}"
         hx-trigger="load"
         hx-swap="innerHTML">
    </div>
</div>

<dialog id="support-request-fab-dialog" class="ep-dialog ep-dialog-wide">
    <div id="support-request-fab-dialog-content" class="support-request-dialog-content"></div>
</dialog>
`;

  document.body.appendChild(container);

  // Let HTMX discover the hx-* attributes and fire the "load" trigger
  if (window.htmx) {
    htmx.process(container);
  }

  // -- Interactions -----------------------------------------------------

  const fab = document.getElementById('support-request-fab');
  const popover = document.getElementById('support-request-popover');
  const dialog = document.getElementById('support-request-fab-dialog');

  fab.addEventListener('click', (e) => {
    e.stopPropagation();
    popover.classList.toggle('support-request-popover--open');
  });

  document.addEventListener('click', (e) => {
    if (!popover.contains(e.target) && e.target !== fab && !fab.contains(e.target)) {
      popover.classList.remove('support-request-popover--open');
    }
  });

  dialog?.addEventListener('click', (e) => {
    if (e.target === dialog) dialog.close();
  });

  // Update badge count when popover content is swapped in
  const badge = document.getElementById('support-request-fab-badge');

  function updateBadge() {
    const header = document.querySelector(
      '#support-request-popover-content .support-request-popover-header',
    );
    const count = parseInt(header?.dataset?.supportRequestCount ?? '0', 10);
    badge.textContent = count;
    badge.classList.toggle('support-request-fab-badge--hidden', count === 0);
  }

  document.body.addEventListener('htmx:afterSwap', (e) => {
    const target = e.detail?.target;

    // Badge update after page-issues content loads
    if (target?.id === 'support-request-popover-content') {
      updateBadge();
    }

    // When submit form loads into the FAB dialog, populate the sourceUrl field
    if (target?.id === 'support-request-fab-dialog-content') {
      const sourceUrlField = target.querySelector('#fb-sourceUrl');
      if (sourceUrlField) sourceUrlField.value = window.location.href;

      // After successful feedback submission, refresh the popover content
      if (target.querySelector('.ep-dialog-body')?.textContent?.includes('Thank you')) {
        const content = document.getElementById('support-request-popover-content');
        if (content && window.htmx) {
          htmx.trigger(content, 'load');
        }
      }
    }
  });
}
