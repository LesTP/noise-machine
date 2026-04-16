---
name: security-plan
description: Security architecture review — run during Architecture or early Implementation
---

Review the project's security posture at the structural level. These are
decisions that are expensive to change later.

## Universal (all projects)

1. **Authentication approach** — if the project has auth, is it delegated
   to a proven provider (Clerk, Supabase Auth, Auth0, etc.)? Never
   hand-roll auth, especially with AI-generated code.
2. **Secrets management** — are API keys and credentials loaded from
   environment variables or a secrets manager? Never hardcoded, never
   committed.
3. **Dependency trust** — have all packages been verified to actually
   exist in the registry? (LLMs sometimes hallucinate package names that
   attackers can register.) Are versions pinned?
4. **.gitignore** — is it in place from the start? Does it cover .env,
   credentials, build artifacts, OS files?
5. **Input validation** — is there a plan for sanitizing all external
   input? Parameterized queries for any database access?
6. **Environment separation** — are test and production environments
   distinct? No test data touching real systems?
7. **AI API cost controls** — if calling external AI APIs, are spending
   caps set in the dashboard AND enforced in code?

## Web/SaaS (skip if not applicable)

8. **Rate limiting strategy** — is rate limiting planned for all
   endpoints from day one? Starting point: 100 req/hour per IP,
   stricter on sensitive routes (e.g., 3 password resets per email/hour).
9. **Storage permissions** — are file storage buckets scoped so users
   can only access their own files?
10. **Row-level security** — if using a database with direct client
    access (e.g., Supabase), is RLS enabled from the start?
11. **CORS policy** — is it planned to allow only production domains?
    Never wildcard.
12. **Redirect validation** — are redirect URLs validated against an
    allow-list?

## LLM-integrated apps (skip if not applicable)

13. **Prompt injection** — if the app passes user input to an LLM, is
    there a plan to sanitize inputs and outputs to prevent injection?

Present findings as: decisions already made, decisions needed, and
recommendations. Do not make changes — this is a planning review.
