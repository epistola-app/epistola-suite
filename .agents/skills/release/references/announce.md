# Announcing a release on the community forum

Post every release, pre-releases included, on [community.epistola.app](https://community.epistola.app)
through the Discourse MCP (`.agents/mcp/discourse/`). It posts as the account signed in to it, so
the post carries a person's name. Draft the post, show it to the user and post only after they
approve it: once posted it is public, and everyone following Aankondigingen gets it by email.

## Before writing

- Read the previous announcement (`discourse_search` for `Epistola <previous version>`) and match
  its tone and layout.
- Read the GitHub release, not just the CHANGELOG: the release body is what readers can follow up.
- Check whether the Valtimo/GZAC plugin released alongside it (`gh release list -R
  epistola-app/valtimo-epistola-plugin -L 3`). A plugin release that needs this Suite release goes
  in the same post: one topic per moment, not two topics that each half explain the upgrade.
- Check the Community Call category for the next call. If the release will be walked through
  there, link that topic (`/t/<id>`) — ask the user first, it is a commitment.
- Verify what you write about compatibility (contract versions, roles a key needs) in the code or
  the contract, not just from the release notes: notes written before the other side released
  can be stale ("no Suite image is published yet").

## The post

- **Category:** Aankondigingen (id 5), a new topic.
- **Title:** `Epistola X.Y.Z: <Dutch headline>`. With the plugin in the same post:
  `Epistola X.Y.Z en GZAC-plugin A.B.C: <headline>`.
- **Body:** Dutch first, in the u-form, then the same text in English inside
  `[details="English"]…[/details]`. Write it for administrators and template authors, not as a
  copy of the changelog:
  - Open with `⚠️ **Controleer dit vóór de upgrade:**` when there is an action on upgrade or a
    breaking change; leave it out otherwise.
  - `**Nieuw in deze versie:**`, a short bullet list of the headline `[user]` changes, each bullet
    with a bold lead-in, and one closing "Verder: …" bullet for the smaller fixes. Leave out
    `[dev]` internals.
  - Name the Helm chart version that installs this release, if there is one.
  - End with `[Volledige release notes op GitHub](…/releases/tag/vX.Y.Z)`.

## A pre-release

An alpha, beta or RC is announced too, so people can try it, but say plainly what it is for:

- Open with `⚠️ **Dit is een bèta.**` (or alpha/RC): what to try (the upgrade, building clients)
  and that it is not for production.
- The first pre-release of a major is the first time readers hear of it: give the headlines of
  the whole line so far (from the earlier pre-releases' notes too), not only the delta since the
  last pre-release.
- Name the action for anyone who ran the previous pre-release (a repair script, a rebuild).
- Link the release notes of every pre-release the post summarises.

## Verify

Read the topic back (`discourse_read_topic`): category Aankondigingen, posted by the expected
account, the English `[details]` block rendered, and every link pointing at an existing release.
