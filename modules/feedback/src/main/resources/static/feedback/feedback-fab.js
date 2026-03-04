/**
 * Feedback FAB with page issues popover.
 *
 * Self-contained module that creates a floating action button (bottom-right)
 * showing open feedback items for the current page. All data loading is
 * declarative HTMX — JS only handles DOM creation and visibility toggling.
 *
 * Usage: <script type="module" src="/feedback/feedback-fab.js" data-tenant-id="acme"></script>
 */
// document.currentScript is null in ES modules — find by src attribute instead
const script = document.querySelector('script[src*="feedback-fab.js"]');
const tenantId = script?.getAttribute('data-tenant-id');
if (!tenantId) throw new Error('feedback-fab.js: missing data-tenant-id');

const pathname = window.location.pathname;
const searchUrl = `/tenants/${tenantId}/feedback/search?url=${encodeURIComponent(pathname)}`;
const submitFormUrl = `/tenants/${tenantId}/feedback/submit-form`;

// -- CSS ------------------------------------------------------------------

const style = document.createElement('style');
style.textContent = `
.feedback-fab {
    position: fixed;
    bottom: var(--ep-space-5, 1.25rem);
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
.feedback-fab:hover {
    box-shadow: 0 4px 16px rgba(0,0,0,.18);
    transform: translateY(-1px);
}
.feedback-fab svg {
    width: 22px;
    height: 22px;
}

/* Badge */
.feedback-fab-badge {
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
.feedback-fab-badge--hidden {
    display: none;
}

/* Popover */
.feedback-popover {
    position: fixed;
    bottom: calc(var(--ep-space-5, 1.25rem) + 56px);
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
.feedback-popover--open {
    display: block;
}

/* Popover content (rendered by server) */
.feedback-popover-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--ep-space-3, 0.75rem) var(--ep-space-4, 1rem);
    border-bottom: 1px solid var(--ep-border, #e2e8f0);
    position: sticky;
    top: 0;
    background: var(--ep-background, #fff);
}
.feedback-popover-title {
    font-weight: 600;
    font-size: var(--ep-text-sm, 0.875rem);
}
.feedback-popover-empty {
    padding: var(--ep-space-6, 1.5rem) var(--ep-space-4, 1rem);
    text-align: center;
    color: var(--ep-muted-foreground, #64748b);
    font-size: var(--ep-text-sm, 0.875rem);
}
.feedback-popover-list {
    list-style: none;
    margin: 0;
    padding: 0;
}
.feedback-popover-item {
    padding: var(--ep-space-3, 0.75rem) var(--ep-space-4, 1rem);
    border-bottom: 1px solid var(--ep-border, #e2e8f0);
}
.feedback-popover-item:last-child {
    border-bottom: none;
}
.feedback-popover-item-title {
    font-weight: 500;
    font-size: var(--ep-text-sm, 0.875rem);
    color: var(--ep-foreground, #0f172a);
    text-decoration: none;
    display: block;
    margin-bottom: var(--ep-space-1, 0.25rem);
}
.feedback-popover-item-title:hover {
    text-decoration: underline;
}
.feedback-popover-item-meta {
    display: flex;
    gap: var(--ep-space-2, 0.5rem);
}
`;
document.head.appendChild(style);

// -- DOM ------------------------------------------------------------------

const container = document.createElement('div');
container.id = 'feedback-fab-container';

// Lucide message-square-plus icon (inline SVG)
const fabIcon = `<svg xmlns="http://www.w3.org/2000/svg" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/><line x1="12" y1="7" x2="12" y2="13"/><line x1="9" y1="10" x2="15" y2="10"/></svg>`;

container.innerHTML = `
<button id="feedback-fab" class="feedback-fab" type="button" title="Page Feedback">
    ${fabIcon}
    <span id="feedback-fab-badge" class="feedback-fab-badge feedback-fab-badge--hidden"></span>
</button>

<div id="feedback-popover" class="feedback-popover">
    <div id="feedback-popover-content"
         hx-get="${searchUrl}"
         hx-trigger="load"
         hx-swap="innerHTML">
    </div>
</div>

<dialog id="feedback-fab-dialog" class="ep-dialog ep-dialog-wide">
    <div id="feedback-fab-dialog-content" class="feedback-dialog-content"></div>
</dialog>
`;

document.body.appendChild(container);

// Let HTMX discover the hx-* attributes and fire the "load" trigger
if (window.htmx) {
    htmx.process(container);
}

// -- Interactions ---------------------------------------------------------

const fab = document.getElementById('feedback-fab');
const popover = document.getElementById('feedback-popover');
const dialog = document.getElementById('feedback-fab-dialog');

fab.addEventListener('click', (e) => {
    e.stopPropagation();
    popover.classList.toggle('feedback-popover--open');
});

document.addEventListener('click', (e) => {
    if (!popover.contains(e.target) && e.target !== fab && !fab.contains(e.target)) {
        popover.classList.remove('feedback-popover--open');
    }
});

dialog?.addEventListener('click', (e) => {
    if (e.target === dialog) dialog.close();
});

// After successful feedback submission, refresh the popover content
document.body.addEventListener('htmx:afterSwap', (e) => {
    const target = e.detail?.target;
    if (target?.id === 'feedback-fab-dialog-content' && target.querySelector('.ep-dialog-body')?.textContent?.includes('Thank you')) {
        const content = document.getElementById('feedback-popover-content');
        if (content && window.htmx) {
            htmx.trigger(content, 'load');
        }
    }
});
