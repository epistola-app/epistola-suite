---
type: fix
scopes: [chart]
audience: dev
title: The chart's resource defaults now match measured document-generation demand.
---

Pods request 750m CPU and 1536Mi memory, with 3 CPU / 4Gi limits; the default HPA targets 600m CPU and does not scale on retained JVM memory. This supports about 5,000 documents per minute per node; the Kind fixture documents and uses the smaller test / preview profile, which supports about 1,000 documents in two minutes.
