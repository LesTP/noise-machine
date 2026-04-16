---
name: security-review
description: Pre-shipping security review — run before release
---

Review the project for security issues before shipping. Check each
applicable section and report findings.

## Universal (all projects)

1. **No hardcoded secrets** — scan for API keys, tokens, passwords, or
   connection strings in source code. Check for anything that should be
   in environment variables.
2. **Dependency audit** — run the appropriate audit tool (npm audit,
   pip-audit, cargo audit) and report findings. Do not auto-fix without
   review.
3. **Dependencies verified** — confirm all packages exist in their
   registries and are the intended packages (no typosquatting).
4. **Debug artifacts removed** — no console.log, print(), debug flags,
   or TODO-FIXME items that expose internals in production code.
5. **.gitignore coverage** — confirm .env, credentials, build artifacts,
   and OS-specific files are excluded.
6. **Input sanitization** — verify all external inputs are validated.
   All database queries use parameterized statements.
7. **Secret rotation** — are current secrets less than 90 days old?
   Flag any that need rotation.
8. **Backup and restore** — if the project has persistent data, are
   backups automated and has restoration been tested?

## Web/SaaS (skip if not applicable)

9. **HTTPS enforced** — all traffic over HTTPS, cookies set to
   Secure/HTTPOnly/SameSite.
10. **Security headers** — CSP, HSTS, X-Frame-Options, and other
    security headers in place.
11. **CORS locked down** — only production domain(s) allowed. No
    wildcard origins.
12. **Rate limits active** — all endpoints rate-limited, especially
    auth and password reset routes.
13. **Server-side auth checks** — permissions verified server-side on
    every protected endpoint. UI-level checks are not security.
14. **Upload validation** — file uploads limited in size and validated
    by file signature, not just extension.
15. **Webhook signatures verified** — any incoming webhooks (payment,
    etc.) verified by signature before processing.
16. **Redirect allow-list** — all redirect URLs validated against an
    allow-list.
17. **Audit logging** — critical actions logged: deletions, role
    changes, payments, exports.
18. **Account deletion** — if users can create accounts, there is a
    working deletion/data export flow (GDPR compliance).
19. **Email auth** — if sending email, SPF/DKIM records configured.
20. **DDoS protection** — edge protection in place (Cloudflare, Vercel
    edge, or equivalent).

## LLM-integrated apps (skip if not applicable)

21. **Prompt injection mitigated** — user inputs to LLMs are sanitized,
    LLM outputs are treated as untrusted before rendering or executing.
22. **AI API costs capped** — spending limits set in provider dashboard
    AND enforced in code.

## Organization/team (skip for solo projects)

23. **MFA enabled** — multi-factor auth on all team accounts
    (hosting, DNS, payment providers, code repos).
24. **Key rotation on staff changes** — secrets rotated when team
    members leave or change roles.
25. **CI secret scanning** — automated secret scanning (GitGuardian,
    Dependabot, Snyk) running in CI pipeline.

Present findings as:
- **Critical** — must fix before shipping
- **Important** — should fix, creates real risk if left
- **Advisory** — best practice, low immediate risk

Do not make changes. Present the report and wait for direction.
