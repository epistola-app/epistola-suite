# ADR 0047: Quality providers: the systems that check, and what they may see

- **Status:** Proposed
- **Date:** 2026-10-31
- **Deciders:** Epistola team
- **Tags:** quality, providers, llm, privacy, egress, saas, configuration
- **Related:** [ADR 0044](0044-quality-results-keyed-by-content.md), [ADR 0046](0046-quality-profiles-and-rules.md)

## Context

Quality rules (ADR 0046) are enforced by systems: a built-in engine, a LanguageTool server, Vale, a
large language model. Which of these exist differs per installation and, in SaaS, per tenant: two
tenants of one installation may use different models with different contracts. Some run inside the
application, some on the organisation's own servers, some outside it, and template text must never go
outside without someone having allowed it. They also differ in cost and speed: a regex takes
microseconds, a model call seconds and money.

A model checks better with context: the rest of the template, what a placeholder stands for, how
often the template is used. Sending all of it by default is wasteful and widens what leaves the
installation; the model should ask for what it needs.

## Considered options

- **Installation only.** The operator configures every provider, for every tenant. Cannot serve SaaS
  tenants with their own model contracts.
- **Tenant only.** Every provider belongs to a tenant. Shared infrastructure is configured N times,
  and the operator loses control over egress.
- **Two levels under an operator policy (chosen).**

For the language model's conversation, a separate agent service was considered: it decouples model
code, but adds an inbound API and a second place that sees template text. The loop runs in the suite.

## Decision

### 1. Provider kinds and providers

A **provider kind** is code: an adapter for one kind of system (`builtin`, `languagetool`, `llm`,
`vale`), in its own module where it has dependencies, present only when included, like the backend
plugins ([`docs/plugins.md`](../plugins.md)). A kind declares its unit (a text block or a subject,
ADR 0044), the rule extras it reads (ADR 0046), the triggers it supports and the settings it needs.

A **provider** is a configured instance of a kind: an address, credentials, settings, triggers and
where it sends text.

### 2. Two levels

- **Installation providers** are configured by the operator (application configuration, Helm). Each
  tenant switches each one on; off by default.
- **Tenant providers** are registered by a tenant administrator in the tenant's quality settings
  (stored per tenant; credentials encrypted with the installation's credential cipher, as code-list
  source credentials are).
- **The operator's policy** says whether tenants may register providers, of which kinds, and with
  which egress (below). A tenant cannot register what the policy does not allow.

### 3. Where text goes

Every provider declares one of:

| Egress         | Meaning                                             | Allowed when                                                                                   |
| -------------- | --------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `in-process`   | inside this application                             | always                                                                                         |
| `installation` | a service the organisation runs                     | configured by the operator, or allowed by policy                                               |
| `external`     | a service outside the organisation (a hosted model) | the policy allows it **and** the tenant has given explicit consent, recorded with who and when |

The suite's call is the only path text takes to a provider, so this is enforced in one place, and the
outbound-traffic documentation lists every provider an installation configures.

### 4. Triggers belong to the provider

A provider's configuration says when it runs: on save, on "Check now", on a ready mark, before a
release, on a schedule. Each kind has defaults: the built-in engine on everything, LanguageTool on
save, "Check now" and ready, a model on "Check now" and ready only. Profiles say what to check, never
when (ADR 0046).

### 5. Language models

- **The conversation runs in the suite.** The `llm` adapter calls an OpenAI- or Anthropic-compatible
  endpoint, answers the model's tool calls in-process, and asks for structured output: findings that
  name a unit, quote the text and give the rule.
- **The first message holds the unit and its rules**, not the template. More comes through
  **tools**, and the provider's configuration chooses which it offers: the variant's structure and
  its other blocks; the data model's field names and descriptions (what a placeholder stands for);
  the profile; the stencils it uses; how often the template is used. **Never example values, data or
  generated documents**: no tool returns them. Every tool is bound to the one check, its subject and
  catalog version.
- **Answers are checked.** A finding whose quote is not in the unit, whose rule was not given, or
  whose location is outside the subject is dropped and counted.
- **What it read is recorded.** Each tool call is recorded by its name, its arguments and the hash of
  what it returned; the result is current while every one would return the same (ADR 0044, 2.).
- **Cost is bounded per provider:** calls and tokens per check, concurrency, a timeout. A provider can
  require a node capability, so model work runs on nodes meant for it.

### 6. In the pilot

LanguageTool is an installation provider configured by properties
(`epistola.quality.language-tool.*`), with its triggers in the same place. Until tenant quality
settings exist, a tenant switches it on with the feature toggle `quality-languagetool` (off by
default); the settings page replaces the toggle, and its rows, when providers get one. The built-in
engine is `in-process` and always on for tenants with quality.

## Consequences

- SaaS tenants can use their own model under the operator's rules; an on-premises installation can
  forbid anything external.
- Text never leaves without the operator allowing the kind of destination and, for external ones,
  the tenant consenting.
- A language model sees only what its tools return, and its answer is reused only while that holds.
- A new system is a provider kind: an adapter, its settings and its extras, not a change to profiles.

## Open questions

- Whether a tenant may override the triggers of an installation provider.
- How usage counts are exposed to a model without exposing who generated what.

## References

- [ADR 0044](0044-quality-results-keyed-by-content.md): units, results and recorded reads.
- [ADR 0046](0046-quality-profiles-and-rules.md): rules and their extras per provider kind.
- [`docs/outbound-traffic.md`](../outbound-traffic.md): what leaves an installation.
