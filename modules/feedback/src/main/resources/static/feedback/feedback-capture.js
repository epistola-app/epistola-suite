/**
 * Console log capture for feedback submissions.
 *
 * Monkey-patches console.log/warn/error/info to buffer the last 100 entries.
 * The buffer is available via window.__epistola_console_buffer for the feedback form.
 */
(function () {
  const MAX_ENTRIES = 100;
  const buffer = [];

  window.__epistola_console_buffer = buffer;

  const methods = ["log", "warn", "error", "info"];
  const originals = {};

  methods.forEach((method) => {
    originals[method] = console[method];
    console[method] = function (...args) {
      buffer.push({
        level: method,
        timestamp: new Date().toISOString(),
        message: args
          .map((arg) => {
            try {
              return typeof arg === "object" ? JSON.stringify(arg) : String(arg);
            } catch {
              return String(arg);
            }
          })
          .join(" "),
      });

      // Keep only the last MAX_ENTRIES
      if (buffer.length > MAX_ENTRIES) {
        buffer.shift();
      }

      // Call the original method
      originals[method].apply(console, args);
    };
  });
})();
