---
type: fix
scopes: [security, observability]
breaking: true
title: Metrics and health details are served only on the internal management port.
---

The actuator (`/actuator/health`, `/actuator/info`, `/actuator/prometheus`) now serves on the management port `4040` in every profile, not only under `prod`. Before, an installation without the `prod` profile served it on the application port, so an ingress in front of the application published metrics carrying tenant and template keys. Kubernetes probes stay on `/livez` and `/readyz` on the application port. **Action on upgrade:** move a Prometheus scrape configured for port `4000` to `4040`; the Helm chart scrapes `4040` by default.
