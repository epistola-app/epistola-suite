import type {EvaluationContext, EvaluationResult, ExpressionEvaluator} from "./types";

/**
 * Sandboxed evaluator using an iframe
 *
 * Runs expressions in a sandboxed iframe with no access to:
 * - Parent window/document
 * - Cookies/localStorage
 * - Network requests with parent credentials
 * - DOM manipulation
 *
 * Trade-offs:
 * - Slower than DirectEvaluator (async postMessage)
 * - No synchronous evaluation
 * - More secure for untrusted expressions
 */
export class IframeEvaluator implements ExpressionEvaluator {
  readonly type = "iframe";
  readonly name = "Iframe Sandbox (Slower, Secure)";
  readonly isSandboxed = true;

  private iframe: HTMLIFrameElement | null = null;
  private pendingRequests = new Map<
    string,
    {
      resolve: (result: EvaluationResult) => void;
      timeout: ReturnType<typeof setTimeout>;
    }
  >();
  private messageHandler: ((event: MessageEvent) => void) | null = null;
  private initialized = false;
  private initPromise: Promise<void> | null = null;

  async initialize(): Promise<void> {
    if (this.initialized) return;
    if (this.initPromise) return this.initPromise;

    this.initPromise = this.doInitialize();
    await this.initPromise;
    this.initialized = true;
  }

  private async doInitialize(): Promise<void> {
    // Create the sandbox HTML with inline script (using srcdoc to avoid blob URL issues)
    const html = `<!DOCTYPE html><html><head></head><body><script>
            'use strict';

            // Send response to parent window
            function sendResponse(data) {
              window.parent.postMessage(data, '*');
            }

            // Listen for evaluation requests from parent
            window.addEventListener('message', function(event) {
              var data = event.data;
              if (!data || !data.id || typeof data.expression !== 'string') {
                return;
              }

              try {
                // Create function with context variables as parameters
                var keys = Object.keys(data.context || {});
                var values = keys.map(function(k) { return data.context[k]; });
                var fn = new Function(keys.join(','), 'return ' + data.expression);
                var result = fn.apply(null, values);

                // Send success response
                sendResponse({ id: data.id, success: true, value: serializeValue(result) });
              } catch (e) {
                // Send error response
                sendResponse({
                  id: data.id,
                  success: false,
                  error: e && e.message ? e.message : 'Unknown error'
                });
              }
            });

            // Serialize values for postMessage (handles circular refs, functions, etc.)
            function serializeValue(value) {
              if (value === null || value === undefined) {
                return value;
              }
              if (typeof value === 'function') {
                return '[Function]';
              }
              if (typeof value === 'symbol') {
                return String(value);
              }
              if (Array.isArray(value)) {
                return value.map(serializeValue);
              }
              if (typeof value === 'object') {
                try {
                  return JSON.parse(JSON.stringify(value));
                } catch (e) {
                  return '[Circular]';
                }
              }
              return value;
            }

            // Signal ready to parent
            sendResponse({ type: 'ready' });
          <\/script></body></html>`;

    // Create sandboxed iframe using srcdoc (avoids blob URL security issues)
    this.iframe = document.createElement("iframe");
    // allow-scripts: enables script execution
    // allow-same-origin: needed for postMessage to work reliably
    // Still sandboxed: no forms, no popups, no top navigation, no pointer-lock, etc.
    this.iframe.sandbox.add("allow-scripts", "allow-same-origin");
    this.iframe.style.display = "none";
    this.iframe.srcdoc = html;

    // Setup message handler
    this.messageHandler = (event: MessageEvent) => {
      // For srcdoc iframes, we can't reliably check event.source
      // Instead, we validate by checking the message structure
      if (!event.data || typeof event.data !== "object") {
        return;
      }

      const { id, type, success, value, error } = event.data;

      // Handle ready signal
      if (type === "ready") {
        return;
      }

      // Handle evaluation response
      if (id && this.pendingRequests.has(id)) {
        const pending = this.pendingRequests.get(id)!;
        clearTimeout(pending.timeout);
        this.pendingRequests.delete(id);

        if (success) {
          pending.resolve({ success: true, value });
        } else {
          pending.resolve({ success: false, error });
        }
      }
    };

    window.addEventListener("message", this.messageHandler);

    // Wait for iframe to be ready
    return new Promise<void>((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(new Error("Iframe sandbox initialization timeout"));
      }, 5000);

      const readyHandler = (event: MessageEvent) => {
        // For srcdoc iframes, event.source might be the contentWindow
        if (event.data.type === "ready") {
          clearTimeout(timeout);
          window.removeEventListener("message", readyHandler);
          resolve();
        }
      };

      window.addEventListener("message", readyHandler);

      // Append iframe and wait for load
      this.iframe!.onload = () => {
        // The script inside should run and send 'ready' message
        // If it doesn't happen within timeout, we fail
      };

      document.body.appendChild(this.iframe!);
    });
  }

  async evaluate(expression: string, context: EvaluationContext): Promise<EvaluationResult> {
    if (!this.initialized) {
      await this.initialize();
    }

    const trimmed = expression.trim();
    if (!trimmed) {
      return { success: false, error: "Expression cannot be empty" };
    }

    if (!this.iframe?.contentWindow) {
      return { success: false, error: "Sandbox not available" };
    }

    const id = crypto.randomUUID();

    return new Promise<EvaluationResult>((resolve) => {
      // Set timeout for evaluation
      const timeout = setTimeout(() => {
        this.pendingRequests.delete(id);
        resolve({ success: false, error: "Evaluation timeout (possible infinite loop)" });
      }, 1000); // 1 second timeout

      this.pendingRequests.set(id, { resolve, timeout });

      // Send evaluation request
      this.iframe!.contentWindow!.postMessage(
        { id, expression: trimmed, context: this.serializeContext(context) },
        "*",
      );
    });
  }

  private serializeContext(context: EvaluationContext): EvaluationContext {
    // postMessage handles structured cloning, but functions won't transfer
    // We need to filter out non-serializable values
    const result: EvaluationContext = {};

    for (const [key, value] of Object.entries(context)) {
      if (typeof value === "function" || typeof value === "symbol") {
        continue; // Skip non-serializable
      }
      try {
        // Test if value can be cloned
        JSON.stringify(value);
        result[key] = value;
      } catch {
        // Skip non-serializable values
      }
    }

    return result;
  }

  dispose(): void {
    // Clear pending requests
    for (const [, pending] of this.pendingRequests) {
      clearTimeout(pending.timeout);
      pending.resolve({ success: false, error: "Evaluator disposed" });
    }
    this.pendingRequests.clear();

    // Remove message handler
    if (this.messageHandler) {
      window.removeEventListener("message", this.messageHandler);
      this.messageHandler = null;
    }

    // Remove iframe
    if (this.iframe) {
      this.iframe.remove();
      this.iframe = null;
    }

    this.initialized = false;
    this.initPromise = null;
  }
}
