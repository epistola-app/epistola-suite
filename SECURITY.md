# Security Policy

## Supported Versions

| Version                                        | Supported |
| ---------------------------------------------- | --------- |
| Two most recently released minor release lines | Yes       |

Security updates are provided for the two most recently released minor release
lines. As long as fewer than two minor release lines have been released, all
available minor release lines are supported.

## Reporting a Vulnerability

We take security seriously. If you discover a security vulnerability, please report it responsibly.

### How to Report

**Please use GitHub's private vulnerability reporting feature:**

1. Open the repository's
   [private vulnerability reporting form](https://github.com/epistola-app/epistola-suite/security/advisories/new)
2. Click "Report a vulnerability"
3. Fill out the form with details about the vulnerability

This ensures your report remains private until we can address it.

Do not open a public issue or pull request, and do not push proof-of-concept or
fix commits to a public fork. Public activity can disclose the vulnerability
before users have a patched release available.

### What to Include

- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Any suggested fixes (optional)

### Response Timeline

- **Acknowledgment:** Within 48 hours
- **Initial Assessment:** Within 1 week
- **Resolution Timeline:** Depends on severity and complexity

### What to Expect

1. We will acknowledge your report within 48 hours
2. We will triage the report in a draft GitHub Security Advisory
3. We will develop and review the fix in the advisory's temporary private fork
4. We will test the fix and prepare a patched release before public disclosure
5. We will coordinate disclosure timing and keep you informed of progress
6. We will credit you in the advisory and release notes unless you prefer anonymity

Published vulnerability information is kept in this repository's
[`vulnerabilities/`](vulnerabilities/) directory. The repository record is the
canonical public record; GitHub Security Advisories provide private coordination
before disclosure and a synchronized publication mirror afterward.

### Scope

This security policy applies to:

- The Epistola Suite application (`apps/epistola`)
- The Editor module (`modules/editor`)
- Official Docker images

### Out of Scope

- Third-party dependencies (please report to upstream maintainers)
- Issues in development/test environments only
- Social engineering attempts

"Out of scope" here is about **reporting**: a vulnerability in Apache Tomcat or Thymeleaf
belongs with its maintainers, not in our inbox. It does not mean we ignore such
vulnerabilities in what we ship. When a scanner flags a component of ours, the finding and
our assessment of whether it is reachable are recorded as a `kind: dependency` record under
[`vulnerabilities/`](vulnerabilities/) and listed in
[`VULNERABILITIES.md`](VULNERABILITIES.md), with a machine-readable
[OpenVEX](https://openvex.dev) document published alongside each release. See
[`docs/sbom.md`](docs/sbom.md#assessing-a-scanner-finding).

## Security Best Practices

When contributing, please:

- Never commit secrets, credentials, or API keys
- Follow secure coding practices
- Keep dependencies up to date
- Report any security concerns promptly

Thank you for helping keep Epistola Suite secure!
