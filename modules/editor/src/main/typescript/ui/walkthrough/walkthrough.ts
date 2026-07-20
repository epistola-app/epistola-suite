/**
 * Guided editor walkthrough (driver.js).
 *
 * This whole module is behind a gated dynamic import in {@link EpistolaEditor}:
 * it is only ever downloaded when the `editorWalkthrough` feature flag is on, so
 * driver.js and its CSS cost nothing when the feature is off.
 *
 * driver.css is imported with Vite's `?inline` suffix and injected as a single
 * <style> element rather than emitted as a separate stylesheet — this keeps the
 * CSS inside this lazy chunk (loaded only with the flag) and avoids lib-mode
 * async-CSS link-injection quirks. `style-src` permits inline styles (ADR 0010).
 *
 * We start small: one step. The `steps` array is the extension point — add more
 * entries here as the tour grows.
 */
import driverCss from 'driver.js/dist/driver.css?inline';

/** Bumped when the tour changes materially, so returning users see it again. */
const SEEN_KEY = 'ep:editor-walkthrough:v1';
const STYLE_ID = 'ep-driver-css';

function hasSeenWalkthrough(): boolean {
  try {
    return localStorage.getItem(SEEN_KEY) === 'true';
  } catch {
    // localStorage may be unavailable (private mode, etc.) — treat as unseen.
    return false;
  }
}

function markWalkthroughSeen(): void {
  try {
    localStorage.setItem(SEEN_KEY, 'true');
  } catch {
    // Non-fatal: the tour simply shows again next time.
  }
}

function ensureDriverStyles(): void {
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = driverCss;
  document.head.appendChild(style);
}

/**
 * Start the walkthrough for a first-time visitor of this editor instance.
 *
 * No-ops if the user has already seen the current tour, or if the target chrome
 * is not present yet. `host` is the `<epistola-editor>` root; the tour is scoped
 * to elements within it.
 */
export async function maybeStartEditorWalkthrough(host: HTMLElement): Promise<void> {
  if (hasSeenWalkthrough()) return;

  const toolbar = host.querySelector('epistola-toolbar');
  if (!toolbar) return;

  const { driver } = await import('driver.js');
  ensureDriverStyles();

  const tour = driver({
    allowClose: true,
    showProgress: false,
    // Fires on both completion and dismissal — either way, don't nag again.
    onDestroyed: () => markWalkthroughSeen(),
    steps: [
      {
        element: toolbar,
        popover: {
          title: 'Welcome to the editor',
          description:
            'This is the toolbar — save your work, open the live preview, and ' +
            'switch modes from here. More of the editor will be covered as this ' +
            'tour grows.',
        },
      },
    ],
  });

  tour.drive();
}
