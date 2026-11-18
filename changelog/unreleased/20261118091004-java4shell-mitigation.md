---
type: fix
scopes: [security, docker]
title: The images mitigate the Java4Shell vulnerability (CVE-2026-114938) in the bundled Java runtime.
---

The Java runtime in the images has a memory-corruption bug in its Base64 decoder on x86-64
processors with AVX-512 (recent Intel Xeon, AMD Zen 4 and newer), reachable before login through
the session cookie. The images now start Java with `-XX:-UseBASE64Intrinsics`, which switches the
affected decoder off, also when your deployment sets its own `JAVA_TOOL_OPTIONS`. Nothing behaves
differently; Base64 decoding of large payloads is slightly slower. ARM servers were never affected.
