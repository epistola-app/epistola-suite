// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function messageWithCode(message: string, code: unknown): string {
  return typeof code === 'string' && code.length > 0 ? `${message} [${code}]` : message;
}

/**
 * Turn a failed preview response into readable UI text.
 *
 * Validation envelopes may contain multiple errors. Only their human-readable
 * messages and optional machine codes are shown; field paths remain available
 * in the response for diagnostics but are not dumped into the editor UI.
 */
export function formatPreviewErrorResponse(
  responseText: string,
  fallback: string = 'Preview failed',
): string {
  if (!responseText.trim()) return fallback;

  let body: unknown;
  try {
    body = JSON.parse(responseText);
  } catch {
    // A web page instead of an error (a sign-in page once the session ended, a proxy's error
    // page) is said in a sentence, never shown as markup.
    if (/^\s*</.test(responseText) || /<html|<body|<!doctype/i.test(responseText)) {
      return 'The preview could not be made, perhaps because you were signed out. Sign in again in another tab, then try again.';
    }
    return responseText;
  }
  if (!isRecord(body)) return fallback;

  if (Array.isArray(body.errors)) {
    const messages = body.errors.flatMap((error) => {
      if (!isRecord(error) || typeof error.message !== 'string' || !error.message.trim()) {
        return [];
      }
      return [messageWithCode(error.message, error.code)];
    });
    if (messages.length > 0) return messages.join('\n');
  }

  for (const key of ['detail', 'message', 'error']) {
    const message = body[key];
    if (typeof message === 'string' && message.trim()) {
      return messageWithCode(message, body.code);
    }
  }

  return fallback;
}
