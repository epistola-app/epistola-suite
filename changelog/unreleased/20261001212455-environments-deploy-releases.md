---
type: feat
scopes: [environments, generation, catalog]
audience: user
breaking: true
title: An environment serves deployed catalog releases, replacing per-template activations.
---

You deploy a release of a catalog to an environment from the environment's page, the catalog's **Deployments** page, or by ticking environments when you publish. A deployed release is served beside the ones already there, so applications asking for an older version keep getting it; a request that names no version gets the highest. Each deployed release shows how many documents were requested from it, so you can undeploy it once nobody asks. To roll back, undeploy the newer release. A test environment can follow a draft as it is edited, with the preview watermark; an environment marked **production** serves only releases. The environment's history records every deploy and undeploy. **Action on upgrade:** activations are not carried over, so every environment refuses document requests until you deploy; environments named `production`, `prod`, `prd` or `productie` are marked production.
