# ADR 0046: Quality profiles and the rules they record

- **Status:** Proposed
- **Date:** 2026-10-31
- **Deciders:** Epistola team
- **Tags:** quality, catalog, profiles, rules, llm, release
- **Related:** [ADR 0044](0044-quality-results-keyed-by-content.md), [ADR 0045](0045-locations-in-a-template.md),
  [ADR 0047](0047-quality-providers.md)

## Context

Organisations write to a standard: a reading level (often CEFR B1), a tone, active and direct
language, sentences of a certain length, words to avoid and words to use instead, and their own
rules and exceptions. They want to record that standard once, apply it to every template, share it,
and keep it independent of any one checking technology.

The same rule is checked very differently by different systems. "Sentences of at most 20 words" is a
count; "use 'u', never 'je'" is a word list; "write actively" or "explain a decision before its
consequences" needs a language model. A rule meant for a model reads like an instruction; a rule for
a regex is a pattern. And the systems an installation has differ: one runs LanguageTool, another an
LLM, a third neither. A profile must still be writable, and readable, everywhere.

## Considered options

**Where profiles live**

- **A tenant setting.** One place to manage, but a profile would not travel with a catalog, be
  released with it, or be shared through Exchange.
- **A catalog resource (chosen).** Versioned and released with its catalog, exported, shared.

**How a rule relates to the systems that check it**

- **Intent only.** Every system interprets the plain-language rule as it can. Simple, but a crude
  check can no longer be configured precisely.
- **A section per system.** Built-in parameters, LanguageTool settings and an LLM prompt kept apart.
  The same intent is written several times and drifts.
- **One rule with extras per provider kind (chosen).** A generic core every system and every author
  reads, plus optional extras for the kinds of system that need more.

**How several profiles combine**

- **One profile per level, composed by `extends`.** The composition lives in the profiles; a template
  that needs one exception needs a profile of its own.
- **Stacked by level, the more specific overriding by rule id (chosen).** House style, catalog rules
  and a template's exceptions apply together without anyone writing a combined profile.

## Decision

### 1. A profile is a catalog resource

`qualityProfile` is a catalog resource type ([ADR 0039](0039-catalog-knows-no-content-type.md)) in
catalog wire v8: versioned in drafts, frozen in releases, exported in archives, shared through
Exchange. It is added to v8 rather than a new wire version because 2.0, whose format v8 is, has only
had alpha prereleases; an older alpha build refuses an archive holding one.

**Several profiles apply at once, stacked by level.** A catalog version names its default profiles; a
template adds its own. Every rule of every applicable profile applies. A more specific level may
**override** a rule of a broader one by its profile and id: change its severity, or switch it off for
that template (legal letters may allow long sentences). The panel and the report say which profile a
rule came from. A profile from another catalog is used through a declared dependency, as a theme is,
so an organisation keeps its house style in one shared catalog and each catalog adds its own rules.

The system catalog ships a default **clear language** profile, so checks work before anyone writes
one.

Profiles are edited on a page per profile in its catalog, each rule in a dialog: its core, and a form
for the built-in check type and its parameters; other kinds' extras as JSON until their providers
exist.

### 2. A rule: a generic core and extras per provider kind

| Part       | Meaning                                                                                                                                                 |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `id`       | stable within the profile; findings and decisions refer to it                                                                                           |
| `title`    | short name                                                                                                                                              |
| `intent`   | the rule in plain language: what and why. Shown to authors as the explanation of every finding of the rule, and what a language model is asked to apply |
| `severity` | info, warning, error                                                                                                                                    |
| `blocking` | an open finding refuses a ready mark and a release (5.)                                                                                                 |
| `scope`    | what it reads: field roles (`prose`, ADR 0045), components, headings only, languages                                                                    |
| `examples` | text that meets it and text that does not                                                                                                               |
| `extras`   | per provider kind (`builtin`, `languagetool`, `llm`, `vale`, …): what that kind needs beyond the core                                                   |

Examples of extras: `builtin: {type: max-sentence-words, limit: 20}`, `builtin: {type: word-list,
entries: {middels: [met, via]}}`, `languagetool: {categories: [TYPOS]}`, `llm: {instruction: "…"}`.
Extras are keyed by **kind**, not by a configured provider, so a profile written on one installation
works on another with a different LLM.

A profile may name extras for kinds this installation does not have: they are kept, edited and
exported like any other part, and shown as not used here. A profile also holds the organisation's
**accepted words** (names, product terms), which every provider that knows spelling applies.

### 3. Who enforces a rule

When a variant is checked, each provider enabled for the tenant (ADR 0047) is given the rules it can
enforce:

- the built-in engine, the rules with a `builtin` extra of a type it implements;
- LanguageTool, the rules with a `languagetool` extra;
- a language model, every rule in scope, from its intent, examples and `llm` extra.

A rule no enabled provider can enforce is reported as **not checked**, in the panel and the report:
an author sees which parts of the standard were checked. Every finding names the rule it enforces;
a provider reports only for the rules it was given, so a LanguageTool spelling finding belongs to the
profile rule that asked for spelling. Two providers flagging the same rule at the same location are
one finding with both suggestions.

### 4. Decisions on findings

An author may **ignore** a finding, with a reason, as today. Changing the standard (accepting a word,
relaxing a rule) is an edit of the profile by whoever may edit its catalog, like any other content.

### 5. Blocking rules

An open finding of a blocking rule refuses marking the variant ready and releasing its catalog
version. For a blocking rule, an outdated, failed or unchecked result counts as not passed. Someone
with the permission to override records an override on the finding, with a reason; it is a decision
(ADR 0044), kept and shown.

Core does the refusing but must not know quality: core defines a **gate** port that ready marks and
releases consult, and the quality module implements it. With the module absent, nothing is refused.

### 6. Editorial results: rewrites and assessments

Two result shapes are fixed now and built with the first language-model provider:

- a **rewrite** is a suggestion: replacement text for a location (ADR 0045), under a rule;
- an **assessment** is, per rule, met, not met or not checked, with a note, for one check of a
  variant. "How closely does this match the profile" is shown from it.

### 7. Caching

A provider's results are keyed by the hash of the rules it was given (ADR 0044), so editing a rule
re-checks only with the providers that enforce it, and a cached result is never read for other rules.

## Consequences

- An organisation's standard is recorded once, in its catalog, and travels with it.
- The same rule is applied crudely where that is all an installation has, and fully where a model is
  available, and the author can see which.
- Every finding explains itself through its rule's intent, whichever system found it.
- Release gating is possible without core depending on quality.
- A new resource type, its editor and its export are real work; until then the built-in engine runs a
  built-in default rule set with the same rule ids the default profile will have.

## Open questions

- The override permission: a new `QUALITY_OVERRIDE`, or an existing release permission.
- Whether the target level and tone are rules or profile settings that rules refer to.

## References

- [ADR 0039](0039-catalog-knows-no-content-type.md): resource types.
- [ADR 0044](0044-quality-results-keyed-by-content.md): results, decisions, the findings view.
- [ADR 0047](0047-quality-providers.md): providers and what they are given.
