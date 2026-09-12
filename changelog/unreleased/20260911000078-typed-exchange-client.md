---
type: refactor
scopes: [exchange]
audience: dev
title: Typed Exchange client.
---

Suite now calls Epistola Exchange through the generated `app.epistola.exchange:api-client` instead of hand-parsing `JsonNode`, so the wire contract is checked at compile time. Discovery and the OAuth token endpoints stay hand-written - they implement `.well-known` and RFC 6749, not the Exchange API. Adopting it immediately exposed three ways `FakeExchangeServer` had drifted from the contract, all invisible while the parser only read the fields it wanted: it omitted the required `tenantName`, `NamespaceSummary.name`, `PublicationSubmission.namespace`, `createdAt` and `updatedAt`, and it reported a `PENDING` submission state that Exchange has never defined.
