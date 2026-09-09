// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.api.security

/**
 * Neutralises a request-derived value before it goes into a log line.
 *
 * A request path, header or method reaches the log exactly as the caller wrote it. Left alone, a
 * carriage return or newline in one lets a caller append lines of their own — a forged
 * "authentication succeeded" entry, a fabricated stack trace, a plausible-looking error attributed
 * to someone else. Log files are read by people and by alerting rules, and both take a line at face
 * value.
 *
 * Every control character becomes `_`: that covers CR and LF, and also the terminal escapes that can
 * rewrite what a human sees when they `cat` the file. The value is still recognisable, which is the
 * point of logging it. Note that Java's `\p{Cntrl}` is ASCII-only, so this does not touch the
 * Unicode line separators (U+2028/U+2029) — no plain-text log reader treats those as line breaks.
 *
 * Prefer this over dropping the value: knowing which path a suspicious request hit is usually the
 * reason the log line exists.
 */
fun sanitizeForLog(value: String): String = value.replace(CONTROL_CHARACTERS, "_")

private val CONTROL_CHARACTERS = Regex("\\p{Cntrl}")
