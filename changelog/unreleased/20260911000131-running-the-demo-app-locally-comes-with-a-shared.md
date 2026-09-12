---
type: feat
scopes: [demo]
audience: dev
title: Running the demo app locally comes with a shared secret.
---

Exercising the all-tenant `/api` credential meant exporting `EPISTOLA_DEMO_SHAREDSECRET` by hand every time. A second document in `application-demo.yaml`, gated on `local`, now supplies a fixed placeholder — so it applies to `local,demo` and cannot reach the published demo image, which runs `demo` without `local`. The value is deliberately unmistakable and low-entropy, like the one in `DemoSharedSecretEndToEndIT`: a realistic-looking credential trips secret scanners and invites being copied somewhere real. Deployments still supply their own through the environment.
