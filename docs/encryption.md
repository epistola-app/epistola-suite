# Credential encryption at rest

Epistola encrypts the sensitive credentials it stores so that a database dump
alone never exposes a usable secret. This covers:

- **Catalog credentials** — `catalogs.source_auth_credential` (auth used to reach
  a remote catalog).
- **Code-list credentials** — `code_lists.credential` (auth used to fetch a
  remote code list).
- **Hub credentials** — the support tier's `app_metadata` entry
  (`support.hub.credentials`).

> **API keys are different and are _not_ encrypted.** They are a verify-only
> secret, so they are **hashed** (SHA-256) — the plaintext is never stored and
> never needs to be recovered. Encrypting them would be strictly weaker. See
> [API keys](#api-keys) below.

## How it works

The crypto primitive lives in `modules/epistola-crypto` (`CredentialCipher`):

- **AES-256-GCM** authenticated encryption (JDK built-in, no external library).
- A fresh random **96-bit nonce** per encryption; a **128-bit GCM tag**.
- The **key id is bound as AAD**, so a ciphertext cannot be relabelled with a
  different key id without failing the authentication tag.

Stored values use a self-describing envelope that fits the existing `TEXT`
columns:

```
enc:v1:<keyId>:<base64url( nonce ‖ ciphertext ‖ tag )>
```

- `enc:` is the sentinel marking ciphertext. Any value **without** it is treated
  as legacy plaintext and passed through on read (the next write re-encrypts it).
- `v1` is the scheme version.
- `<keyId>` selects the decryption key (and is the AAD).

Persistence is transparent: domain types carry a `Secret` field, and JDBI's
`SecretArgumentFactory` / `SecretColumnMapper` (registered in `JdbiConfig`)
encrypt on write and decrypt on read. Code above the JDBI layer only ever sees
plaintext via `Secret.value`, and `Secret.toString()` is redacted to prevent
accidental logging.

## The keyset and key ids

Configuration lives under `epistola.encryption`:

```yaml
epistola:
  encryption:
    enabled: true
    primary-key-id: k1 # key used for all NEW encryptions
    keys: # every listed key can DECRYPT (selected by the envelope's key id)
      - id: k1
        material: <base64 of 32 random bytes>
```

`keys` is a **set**, not just current+previous, because key ids are embedded in
every ciphertext and decryption picks the matching key. Holding several keys at
once is what makes rotation safe — including overlapping rotations (a second
rotation started before the first finished backfilling) and decoupling "add a
new key" from "retire an old one". In steady state the list has a single entry.

Generate a key with:

```bash
openssl rand -base64 32
```

## Configuration by environment

- **Local dev** (`local` profile): a **fixed** dev key is committed in
  `application-local.yaml` (clearly marked throwaway / non-secret). It is fixed
  rather than random-per-boot so credentials in your local database survive
  restarts.
- **Other non-prod profiles with no keys configured**: the app generates an
  **ephemeral in-memory key** and logs a warning. Encrypted data is not readable
  after a restart — fine for throwaway databases, not for anything persistent.
- **`prod` profile**: **fails fast at startup** if encryption is enabled but no
  valid key is configured.
- **Disabled** (`epistola.encryption.enabled=false`): the cipher is a pure
  pass-through and credentials are stored as plaintext. Local/opt-out only.

At startup the cipher validates that every key's material base64-decodes to
exactly 32 bytes, that `primary-key-id` is present in the keyset, and runs a
self-test encrypt/decrypt under the primary key.

## Helm

The chart wires the keyset as indexed environment variables sourced from a
Kubernetes Secret (`charts/epistola`):

```yaml
encryption:
  enabled: true
  primaryKeyId: k1
  keys:
    - id: k1
      existingSecret: epistola-encryption # Secret name
      secretKey: k1 # key within the Secret (defaults to id)
```

This renders (app Deployment only — the migration Job never touches ciphertext):

```
EPISTOLA_ENCRYPTION_ENABLED=true
EPISTOLA_ENCRYPTION_PRIMARYKEYID=k1
EPISTOLA_ENCRYPTION_KEYS_0_ID=k1
EPISTOLA_ENCRYPTION_KEYS_0_MATERIAL=<from secretKeyRef>
```

Create the Secret with, e.g.:

```bash
kubectl create secret generic epistola-encryption \
  --from-literal=k1="$(openssl rand -base64 32)"
```

## Key rotation runbook

1. **Add** a new key to the keyset (e.g. `k2`) alongside the existing one(s).
2. **Promote** it: set `primary-key-id: k2`. New writes now use `k2`; values
   under `k1` still decrypt because `k1` is still in the keyset.
3. **Backfill**: run the `ReencryptCredentials` command (a `SystemInternal`
   maintenance operation) to rewrite every stored credential under the new
   primary. It is idempotent — values already on the primary are skipped — and
   it also upgrades any legacy plaintext. Pass the hub-credentials metadata key
   (`support.hub.credentials`) in `metadataKeys` to include it.
4. **Verify**: run the `VerifyNoStaleKeyIds` query and confirm
   `allOnPrimary == true` (no value still references an old key id, and no
   plaintext remains).
5. **Retire**: remove the old key from the keyset.

Rotating the keyset is a routine, no-downtime operation; only step 5 is gated on
step 4 passing.

## API keys

API keys are stored as a SHA-256 hash (`api_keys.key_hash`) and validated by
hashing the presented key and looking up the hash — the plaintext is never
persisted. To keep the hot `/api/**` authentication path fast, lookups are
served from a short-TTL, bounded in-memory cache (`ApiKeyAuthCache`, default 60s
/ 10k entries) that also caches negative results; revoking a key invalidates the
cache immediately, and `ApiKey.isUsable()` is re-checked on every cache hit so a
cached-but-expired key still fails.

## Tenant backups

Faithful tenant backups (see [`tenant-backup.md`](tenant-backup.md)) reuse this
cipher: the whole backup archive is base64-wrapped and encrypted with the same
keyset via `TenantBackupCrypto` (a second, outer layer — credential columns
inside the dump are already `enc:v1:` ciphertext and ride through verbatim).
Restoring a backup therefore needs the same keyset present, exactly as reading
the live database's encrypted columns does; the multi-key keyset handles backups
taken before a rotation as long as the old key id is retained.
