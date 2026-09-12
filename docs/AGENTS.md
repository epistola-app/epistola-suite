# docs

Technical documentation: how the suite is built, deployed and extended. User-facing guides live at
<https://epistola.app/en/learn>, not here.

## Rules for a page

- **Index it.** Every page is listed in [`README.md`](README.md); an ADR is listed in
  [`adr/README.md`](adr/README.md). A doc that is in no index is a doc nobody finds — and agents
  routing by the index will never read it.
- **Say what it is.** A page that does not describe current shipped behaviour opens with a
  `> **Status:** …` blockquote directly under its title — design, alpha/beta, or a dated record.
  That banner is authoritative; keep the index's Status column consistent with it.
- **One `# H1`**, kebab-case filename, relative links so they resolve on GitHub and in an editor.
- **Keep one canonical page per subject.** Several subjects are currently spread across five or more
  pages (cluster runtime, eventing, HTMX/UI, testing, catalogs). When you touch one of those, prefer
  folding into the canonical page and leaving a "superseded by" line over adding a sixth `(unenforced)`.
- A **Record** page is a point-in-time artefact: correct when written, not maintained. Do not
  retro-edit one to match later reality — write a new one.
- Run `pnpm format` — Markdown is formatted too, and CI checks it.

## Known gaps

Being fixed as they are touched, listed so they are not rediscovered: two ADRs share the number
`0011`; a few index Status values disagree with the page banner; two intra-doc anchors do not
resolve; `auth.md` and `plans/tenant-scoped.md` still mention `CoreIntegrationTestBase`, removed in
April.
