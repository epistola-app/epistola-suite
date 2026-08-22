# Published Vulnerabilities

This file records security vulnerabilities in Epistola Suite and the releases they affect. It is
the repository's human-readable vulnerability history, not a substitute for a published security
advisory. When a vulnerability receives a GHSA or CVE identifier, that advisory is canonical and
will be linked here.

Version ranges refer to Epistola Suite application tags. `Unreleased` means the fix is present in
the repository but no tagged release contains it yet.

## EPIS-2026-001: Stored HTML injection in editor resource pickers

| Field                        | Value                                                                                                                 |
| ---------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| Status                       | Fixed in source; patched release pending                                                                              |
| Severity                     | High                                                                                                                  |
| Weakness                     | [CWE-79: Improper Neutralization of Input During Web Page Generation](https://cwe.mitre.org/data/definitions/79.html) |
| Report                       | [GitHub issue #644](https://github.com/epistola-app/epistola-suite/issues/644)                                        |
| Asset picker affected code   | `v0.0.0` through `v1.0.1`                                                                                             |
| Stencil picker affected code | `v0.12.0` through `v1.0.1`                                                                                            |
| Demonstrated XSS exposure    | `v0.0.0` through `v0.26.0`, and `v1.0.0-RC1`                                                                          |
| Patched release              | Unreleased                                                                                                            |

Asset names derived from uploaded filenames were interpolated into the asset picker's
`innerHTML`. Stencil names, descriptions, and tags were later found to use the same unsafe
rendering pattern. An authenticated user able to create one of these resources could store HTML
that was rendered when another user opened the corresponding picker.

Through `v0.26.0`, and in `v1.0.0-RC1`, the application's Content Security Policy allowed inline
scripts and event handlers. A payload using an injected element with an event handler could
therefore execute in a victim's authenticated editor session. This required the victim to open the
affected picker.

`v1.0.0-RC2` removed `unsafe-inline` from `script-src`; every GA release has shipped with that
strict policy. It blocks the demonstrated event-handler payload and materially mitigates script
execution. Those releases are still listed in the affected-code ranges because they continued to
create arbitrary HTML from stored values; the underlying sinks were not removed until the source
fixes below.

The source fix constructs asset cards with DOM APIs and assigns author-controlled values through
safe properties and `textContent`. It escapes author-controlled stencil text before the remaining
HTML template interpolation. Command-boundary validation also rejects markup delimiters and
control characters in asset names, stencil names, and stencil tags as defense in depth.

Fix commits:

- [`4f7ae0692`](https://github.com/epistola-app/epistola-suite/commit/4f7ae06920c82ebff2124b651a7d2a48d159aee7)
  fixes the asset picker.
- [`2d94d6ce2`](https://github.com/epistola-app/epistola-suite/commit/2d94d6ce22df0e021f6bf958f17ab2016e82dfaa)
  fixes the stencil picker.

Until a patched release is available, operators should retain the shipped strict Content Security
Policy and avoid weakening `script-src`. Once the fix is released, this entry must be updated with
the first patched version and its published GHSA/CVE identifier, if assigned.
