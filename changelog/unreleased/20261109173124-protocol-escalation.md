---
type: feat
scopes: [support]
title: A partner asks the installation to escalate a request to the connection it escalates to.
---

A support connection can now ask, through the protocol (`ESCALATION` in its changes feed), to escalate a
request it handles. The installation attaches the connection the partner escalates to, such as Epistola
support, as its support desk would, and tells the desk in an internal note; when that cannot be done,
the note says why. Who a connection escalates to is set in its configuration (`escalates-to`) or on the
platform page.
