# Security Policy

We take the security of Tatrman seriously. Tatrman sits on the path between an
AI agent and governed enterprise data, so we would rather hear about a problem
early and privately than read about it in the wild.

## Reporting a vulnerability

**Please do not open a public issue for a security problem.**

Report privately through either channel:

- **GitHub Private Vulnerability Reporting** (preferred): use the **"Report a
  vulnerability"** button under this repository's **Security** tab. This opens a
  private advisory visible only to the maintainers.
- **Email**: `security@tatrman.org`.

Include, as far as you can: the affected component and version, a description of
the issue, reproduction steps or a proof of concept, and the impact you foresee.

## What to expect

- **Acknowledgement within 5 working days.** We will confirm receipt and give you
  a point of contact.
- We will keep you updated as we investigate, agree on a disclosure timeline with
  you, and credit you in the advisory unless you prefer to stay anonymous.
- We ask that you give us a reasonable window to ship a fix before any public
  disclosure (coordinated disclosure).

## Supported versions

Tatrman is pre-1.0. Only the **latest released minor** receives security fixes;
there is no back-porting to older lines until we cut a 1.0 and publish a support
policy alongside it.

| Version | Supported |
|---|---|
| Latest released minor | ✅ |
| Anything older | ❌ |

## No bounty program

We do **not** run a paid bug-bounty program. We are grateful for responsible
disclosures and will credit reporters publicly, but there is no monetary reward.
We would rather be honest about that than imply one that does not exist.
