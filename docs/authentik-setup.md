# authentik Setup

Epistola's OIDC login is **provider-neutral** — it works with any compliant OpenID Connect
provider. This guide covers [authentik](https://goauthentik.io/). The login mechanism is identical
to Keycloak; only the provider configuration differs. Epistola makes **no** admin-API calls to
authentik — it only reads the claims in the token, so **you** own the group/role model in authentik.

> Read [auth.md](auth.md) for the overall authentication model and
> [authorization.md](authorization.md) for the role/permission catalog.

## What Epistola needs from the token (the contract)

On each login Epistola reads `sub` and `email`, plus **at least one** of these claims to resolve
memberships (both may be present; results are merged):

| Claim    | Type            | Example values                                                                                                             |
| -------- | --------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `groups` | array of string | `["/epistola/tenants/acme-corp/content-viewer", "/epistola/platform/tenant-manager"]` — paths must start with `/epistola/` |
| `roles`  | array of string | `["ept_acme-corp_content-viewer", "eps_tenant_manager"]` — flat prefix encoding                                            |

The conventions (group paths, `epg_`/`ept_`/`eps_` prefixes, known role names) are documented once
in [keycloak-setup.md](keycloak-setup.md#group-path-convention) and apply verbatim here.

> **Recommendation — use the flat `roles` claim, not `groups`.** Flat, prefix-encoded roles are the
> recommended best practice across all providers: portable, client-scoped, and simpler. The
> hierarchical `groups` claim still works but **may be removed in a future release**, so new
> deployments should standardise on flat roles. The rest of this guide uses `roles`.

## 1. Create the OAuth2/OpenID provider

In authentik, **Applications → Providers → Create → OAuth2/OpenID Provider**:

- **Client type:** Confidential (Epistola is a server-side web app).
- **Client ID / Client Secret:** note both — they map to `oidc.clientId` / the client-secret Secret.
- **Redirect URIs:** `https://<your-epistola-host>/login/oauth2/code/<registrationId>`, where
  `<registrationId>` is the id you choose in Epistola config (e.g. `authentik`). For local dev this
  is `http://localhost:4000/login/oauth2/code/authentik`.
- **Scopes:** include `openid`, `profile`, `email`, plus the custom scope mapping from step 3.
- **Signing key:** any configured certificate (Epistola validates via the provider's JWKS).

Then create an **Application** bound to this provider and grant the relevant users/groups access.

## 2. Note the issuer

authentik's issuer looks like `https://<authentik-host>/application/o/<application-slug>/`
(**with** the trailing slash). This is your `oidc.issuerUri`. Epistola discovers the authorization,
token, userinfo and JWKS endpoints from `<issuer>/.well-known/openid-configuration` — no per-endpoint
configuration needed.

## 3. Emit the membership claim

Create a **Customisation → Property Mapping → Scope Mapping**. Two names are involved — don't
confuse them:

- **Claim name** — the key _inside_ the token that Epistola reads. This **must** be `roles` (the
  default; override with `oidc.flatRolesClaimName` / `EPISTOLA_AUTH_FLATROLES_CLAIMNAME`).
- **Scope name** — the OAuth2 scope the client requests to trigger the mapping. This is **your
  choice** (it could even be `roles`); this guide uses `epistola-roles` to keep it visually distinct
  from the claim. Whatever you pick, add it to the provider's _Selected Scopes_ **and** to
  `oidc.scope` (step 4) so Epistola requests it.

Name your authentik groups after the encoded roles — e.g. a group `ept_acme-corp_content-viewer` for
"content-viewer in the acme-corp tenant", `eps_tenant_manager` for the platform tenant-manager role
— then have the mapping emit them under the `roles` **claim**:

```python
# Scope name: epistola-roles  (your choice; must match oidc.scope). Claim key: roles (fixed).
return {
    "roles": [
        group.name
        for group in request.user.ak_groups.all()
        if group.name.startswith(("epg_", "ept_", "eps_"))
    ]
}
```

Prefer hierarchical `groups`? Name the authentik groups as full paths
(`/epistola/tenants/acme-corp/content-viewer`, …) and return them under a `groups` claim key instead.
See the [group path convention](keycloak-setup.md#group-path-convention) for the exact shape.

## 4. Configure Epistola

### Helm

```yaml
config:
  profiles: "prod" # no "keycloak" profile — authentik needs none

oidc:
  enabled: true
  registrationId: authentik # becomes the redirect-URI segment + env-var name
  clientId: <authentik-client-id>
  existingSecret: epistola-authentik-client # Secret holding the client secret
  secretKey: client-secret
  issuerUri: https://sso.example.com/application/o/epistola/
  scope: "openid,profile,email,epistola-roles" # add your roles scope-mapping name from step 3
  ssoButtonLabel: "Sign in with authentik" # optional; defaults to "Sign in with SSO"
  # userNameAttribute: preferred_username   # optional
  # backchannelBaseUrl: http://authentik:9000  # only for split-horizon networking
```

`autoProvision` defaults to `true`, so a user's local record is created on first login.

### Plain environment variables (non-Helm)

The registration id (`AUTHENTIK` below) must match `oidc.registrationId`:

```bash
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_AUTHENTIK_CLIENTID=<client-id>
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_AUTHENTIK_CLIENTSECRET=<client-secret>
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_AUTHENTIK_SCOPE=openid,profile,email,epistola-roles
SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_AUTHENTIK_ISSUERURI=https://sso.example.com/application/o/epistola/
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI=https://sso.example.com/application/o/epistola/
EPISTOLA_AUTH_AUTOPROVISION=true
EPISTOLA_AUTH_OIDC_SSOBUTTONLABEL=Sign in with authentik
```

No Spring profile is required — the presence of the registration properties enables OIDC login.

## 5. Split-horizon networking (optional)

If authentik is reachable at a different URL from inside the cluster than from the browser, set
`oidc.backchannelBaseUrl` (`EPISTOLA_AUTH_OIDC_BACKCHANNELBASEURL`) to the internal base URL. Epistola
reads the provider's real endpoints from its discovery document and routes only the server-to-server
calls (token, userinfo, JWKS) through the internal host, keeping browser-facing endpoints external.

## 6. Verify

1. Sign in via the SSO button and decode the issued token (e.g. <https://jwt.io>). Confirm a
   top-level `roles` (or `groups`) claim with your encoded values.
2. Open **`/profile`** in the app — it lists the resolved tenant memberships, global roles and
   platform roles. Empty despite claims in the token means the **names** don't match the encoding
   (see the [role vocabulary](keycloak-setup.md#group-path-convention)).
