/**
 * Editor Logger - Lightweight logging utility for debugging editor behavior
 *
 * Logs are stored in memory and can be viewed via browser console.
 * Usage:
 *   import { log } from '/editor/utils/editor-logger.js';
 *   log.info('renderer', 'morphdom update', { blockId: '123' });
 *   log.warn('text-block', 'empty content on save');
 *
 * View logs:
 *   window.__editorLogs.dump()     - print all logs
 *   window.__editorLogs.dump(20)   - print last 20 logs
 *   window.__editorLogs.filter('renderer') - filter by source
 *   window.__editorLogs.clear()    - clear logs
 */

const MAX_ENTRIES = 200;

const entries = [];

function addEntry(level, source, message, data) {
  const entry = {
    time: new Date().toISOString().slice(11, 23),
    level,
    source,
    message,
    data: data !== undefined ? data : null,
  };

  entries.push(entry);

  if (entries.length > MAX_ENTRIES) {
    entries.shift();
  }
}

function formatEntry(entry) {
  const prefix = `[${entry.time}] [${entry.level.toUpperCase()}] [${entry.source}]`;
  if (entry.data !== null) {
    return `${prefix} ${entry.message} ${JSON.stringify(entry.data)}`;
  }
  return `${prefix} ${entry.message}`;
}

export const log = {
  info(source, message, data) {
    addEntry("info", source, message, data);
  },

  warn(source, message, data) {
    addEntry("warn", source, message, data);
    console.warn(`[editor] [${source}] ${message}`, data !== undefined ? data : "");
  },

  error(source, message, data) {
    addEntry("error", source, message, data);
    console.error(`[editor] [${source}] ${message}`, data !== undefined ? data : "");
  },

  debug(source, message, data) {
    addEntry("debug", source, message, data);
  },
};

// Expose on window for console access
window.__editorLogs = {
  dump(count) {
    const slice = count ? entries.slice(-count) : entries;
    console.log(slice.map(formatEntry).join("\n"));
    return slice.length + " entries";
  },

  filter(source, count) {
    const filtered = entries.filter((e) => e.source.includes(source));
    const slice = count ? filtered.slice(-count) : filtered;
    console.log(slice.map(formatEntry).join("\n"));
    return slice.length + " entries";
  },

  clear() {
    entries.length = 0;
    console.log("[editor] logs cleared");
  },

  get entries() {
    return [...entries];
  },
};
