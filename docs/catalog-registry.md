# Catalog registries

> **Status:** Alpha. Off until an operator configures a registry. It may change in a minor release;
> the design is [ADR 0032](adr/0032-catalog-sources-releases-and-merging.md), section 7.

A container registry (OCI distribution: Harbor, GitLab, Azure Container Registry, Amazon ECR, zot,
CNCF distribution, GitHub Container Registry) can carry released catalog versions between your own
installations, for example from acceptance to production. Production usually pulls its images from
such a registry already, so no new network path is needed, and the suite never contacts another
installation directly.

- **Publish**: on a release's page, _Publish to registry…_ (or the MCP tool
  `publish_release_to_registry`) pushes that release, exactly as it was released, as the artifact
  `<registry>/<prefix>/<namespace>/<catalogKey>:<version>`.
- **Get**: on the catalogs page, _From a registry_ (also linked from _Import ZIP_), or the MCP tools
  `list_registry_releases` and `install_release_from_registry`: name the catalog's namespace and ID,
  pick a version, and it is installed exactly as importing its archive would.

There is no REST endpoint yet: one needs a change to the external contract and is a follow-up.

## Configuring a registry

Registries are configured per installation. With none configured (the default) nothing of this
feature appears anywhere.

```yaml
epistola:
  catalog:
    registries:
      - name: acme # what people pick, and what an installed catalog records
        url: https://registry.acme.example # scheme and host (and port), no path
        repository-prefix: epistola/catalogs # optional; repositories go under it
        username: ${CATALOG_REGISTRY_USERNAME} # optional; Basic, or for the token service
        password: ${CATALOG_REGISTRY_PASSWORD} # from the environment or a secret
```

| Property                                           | Default | Effect                                                                                      |
| -------------------------------------------------- | ------- | ------------------------------------------------------------------------------------------- |
| `epistola.catalog.registries[n].name`              | —       | Lowercase slug, unique. A catalog installed from it records `oci:<name>/<namespace>/<key>`. |
| `epistola.catalog.registries[n].url`               | —       | `https://host[:port]`. Plaintext `http://` only with `epistola.catalog.allow-http=true`.    |
| `epistola.catalog.registries[n].repository-prefix` | empty   | Path components (`a/b`) the repositories go under.                                          |
| `epistola.catalog.registries[n].username`          | empty   | Credentials for Basic authentication, or to ask a Bearer token service. Empty: anonymous.   |
| `epistola.catalog.registries[n].password`          | empty   | Never in a committed file (`NoHardcodedSecretsTest`).                                       |

As environment variables: `EPISTOLA_CATALOG_REGISTRIES_0_NAME`, `EPISTOLA_CATALOG_REGISTRIES_0_URL`,
`EPISTOLA_CATALOG_REGISTRIES_0_REPOSITORY_PREFIX`, `EPISTOLA_CATALOG_REGISTRIES_0_USERNAME`,
`EPISTOLA_CATALOG_REGISTRIES_0_PASSWORD`, and `_1_` for a second one. The Helm chart has no
dedicated values for this yet; `config.env` sets the non-secret ones, and the password should come
from a Kubernetes Secret you mount yourself rather than from `config.env`, which is stored in the
Deployment in plain text.

An invalid entry (a bad name, a URL with a path, a plaintext URL without `allow-http`, a password
without a username, a name used twice) stops the application at start-up with a message naming it.

A registry is the operator's choice, so its address is not held to the private-network rules of
`epistola.catalog.outbound` that apply to catalog URLs people type: an organisation's own registry
usually is on its own network. Redirects (registries send blob downloads to object storage) are
followed at most `epistola.catalog.outbound.max-redirects` times, never to plaintext `http://`
unless `allow-http` is on, and the registry's credentials are never sent to another host.

## Permissions

| Action             | Permission                                     | Why                                                                                                             |
| ------------------ | ---------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| See the registries | `CATALOG_VIEW`                                 | Names and hosts only; credentials never leave the configuration.                                                |
| List versions      | `CATALOG_VIEW`                                 | Reading.                                                                                                        |
| Publish            | `CATALOG_PUBLISH` (+ `CATALOG_VIEW`)           | As sending a release to Epistola Exchange: the release leaves the installation, which releasing does not cover. |
| Get a version      | `CATALOG_MANAGE` (+ `TEMPLATE_EDIT` in the UI) | As importing an archive; the UI asks for both, as installing from Exchange does.                                |

## What is checked

**Publishing** first looks the version up in the registry. Already there with the same fingerprint
label: nothing is pushed and the result says so. There with anything else: refused, because one
number names one release; release the changes under a new number. Looking first also matters on
registries that refuse to overwrite a tag at all. Between the look and the push another
installation could still publish the same number; only an immutable-tag policy on the registry
closes that.

**Getting a version**, in order, before anything is installed:

1. The tenant's catalog with that ID, if any, is the same catalog: same namespace, and not receiving
   its releases from somewhere else. Otherwise it is refused, before anything is downloaded.
2. The registry has the tag, as a catalog release, with an archive no larger than
   `epistola.catalog.max-zip-size`.
3. The downloaded archive's SHA-256 and size are those its manifest names.
4. The archive is the catalog and version that were asked for.

Then the import does what it does for a file: it **recomputes the fingerprint from the content** and
refuses an archive whose content does not match it; refuses a number the tenant already has with
other content; refuses a release whose dependencies are missing, and a catalog format that is too
new. The labels on a manifest (version, fingerprint, namespace) are shown in listings but never
trusted: a tampered archive keeps whatever label it was given.

A catalog the tenant does not have yet is created with that registry as its source
(`oci:<registry>/<namespace>/<key>`). The background update check asks the registry for its highest
version, and the catalogs list offers a newer one through the same dialog. A catalog the tenant has
receives the release as any import does: its next release moves to it, or, when that has changes of
its own or the release is lower, it is recorded beside the others (ADR 0032).

## Tags are mutable: turn on immutable tags

Most registries let anyone with push rights overwrite a tag without a word: CNCF distribution,
zot without a policy, and GitHub Container Registry all do. The suite checks before it publishes and
always recomputes the fingerprint when it installs, so an overwritten tag is detected rather than
silently installed, but it cannot stop someone else from overwriting one. Where the registry offers
it, make tags immutable for the catalog repositories:

- **Harbor**: an immutable tag rule on the project (`**` for the repositories under the prefix).
- **Amazon ECR**: `imageTagMutability: IMMUTABLE` on each repository.
- **Azure Container Registry**: lock the tags, `az acr repository update --write-enabled false`
  (per tag or repository).
- **zot**: an access-control policy that grants `create` but not `update` on the repositories.
- **Docker Hub**: immutable tags in the repository settings, where the plan offers them.
- **GitHub Container Registry** has no such option.

## Tested, and not yet

The integration tests run against CNCF distribution 2 (`registry:2`), anonymous and behind htpasswd
Basic authentication. The Bearer token flow is tested against a scripted token service only, not
against a real one; Harbor, GHCR, ECR, ACR and zot are untested by the suite (the design spike
pushed to and pulled from distribution 2 and 3 and zot). Releases that pin other catalogs' releases
need those installed first: getting the missing ones from the registry automatically is a
follow-up, as is a REST endpoint.
