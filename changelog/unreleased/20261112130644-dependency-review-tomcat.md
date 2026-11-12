---
type: fix
scopes: [deps]
title: The embedded Tomcat is no longer held below the version Spring Boot ships.
---

An old security override pinned the embedded Tomcat to 11.0.25. Spring Boot now ships 11.0.27, so
the override was pulling every image two patch releases back. It is gone, along with Jackson
overrides that had become redundant. The epistola-contract dependency moves to its 2.0.0 release,
and the build toolchain, npm and JVM dependencies are brought up to date.
