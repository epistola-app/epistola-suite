---
paths:
  - "**/application*.yaml"
  - "**/application*.yml"
  - "**/*Properties.kt"
---

# Configuration properties

A configuration knob is an operator-facing promise, so it does not land alone.

- **Document the default and the effect.** A new property with no documented behaviour is a finding
  in review. Operator-facing means the docs under `docs/`, the Helm chart
  (`charts/epistola/`), and a CHANGELOG entry saying what an operator must do on upgrade.
- **Default to off for anything that changes who can reach what.** The commercial support tier is
  off by default (`epistola.support.enabled=false`) and OSS deployments never construct its beans;
  Exchange is off by default (`epistola.exchange.enabled`). Follow that shape.
- **Prefer a feature toggle over a property** when the switch is per tenant rather than per
  installation, and read it through `ResolveFeatureToggles` / `GetFeatureToggles` rather than
  injecting the service.
- Keep the local profile's values local: `application-local.yaml` is for development defaults, not
  for shipping behaviour.
- Secrets never go in a properties file — `NoHardcodedSecretsTest` and gitleaks both look.

Files live at `apps/epistola/src/main/resources/application.yaml` plus `-local`, `-localauth` and
`-prod` variants; feature modules carry their own `*Properties.kt`.

## Verify

```bash
./gradlew :apps:epistola:unitTest --tests "*NoHardcodedSecretsTest"
helm lint charts/epistola      # if the knob reaches the chart
```
