# Guides for AI operators, per role

**Purpose.** One file per RUTERA role. It tells an AI agent that works on behalf of a user with that role which
functions and data it can reach and the simplest way to read or change them through the MCP server or the REST API.
It is part of the product, not internal documentation: the AI reads it when it connects to RUTERA.

**Place in the product (decision of 2026-10-27).** The guides belong to the paid module "AI access" (public API +
MCP). The module is not the channel of the RUTERA web interface (React uses the internal `/app` interface); a firma
gets it with `firma.ai_access`, switched by the operator (cloud) or by `RUTERA_AI_ACCESS` (standalone).

**Status.** Version 0.2 of 2026-11-03: the API and the MCP server exist, so the guides name the real tools (108 tools
generated from `/api/v1/openapi.json`, see `README.md` of the repository, "MCP server for AI assistants"). Version
0.1 (2026-10-27, Russian) had placeholders.

## Maintenance rule (decision of 2026-10-27)

The guides are maintained together with the code:

1. A change that adds, changes or removes a function of the public API / MCP updates, in the same commit or pull
   request, the guides of the roles that can use the function.
2. Every file has a version and a change log at the end.
3. Review check: a tool that exists but appears in no guide is a defect. The tool names follow from the API paths
   (`<resource>_<list|get|create|update|patch|delete>` or an action name), so a renamed path renames the tool.

## Files

| File | Role | In short |
|---|---|---|
| `owner.md` | Owner (OWNER) | everything in the firma, including finance, driver pay and administration |
| `dispatcher.md` | Dispatcher (DISPATCHER) | fleet, drivers without pay, absences, tachograph, orders, trips, CMR |
| `logistician.md` | Logistician (LOGISTICIAN) | orders, trips, CMR, trip calculator; fleet, drivers and hours read-only |
| `accountant.md` | Accountant (ACCOUNTANT) | invoices, payments, debts and reminders, fuel, driver pay |
| `driver.md` | Driver (DRIVER) | own data only: trips and stops, hours and violations, absences, trip photos |
| `_template.md` | - | template for a new role |

Custom roles (CUSTOM, own privilege set) use the guide of the closest standard role; the privileges of the user
always win over the text of a guide.

## Structure of every file

1. **Context**: who you act for, the limits (one firma, the user's privileges).
2. **Connection**: MCP, REST, authorization.
3. **Access**: modules with their level (read / manage / none) and the privilege keys behind them.
4. **Typical tasks**: what is asked most and which tools answer it.
5. **Limits and rules**: what is not possible, what needs the user's confirmation.
6. **Change log**.

## Connection (all roles)

| What | Where |
|---|---|
| MCP server | `https://<host>/mcp` (Streamable HTTP, JSON-RPC; protocol versions 2025-06-18, 2025-03-26, 2024-11-05) |
| Authorization for claude.ai and other OAuth clients | OAuth 2.1: the client discovers RUTERA from the 401 of `/mcp`, the user signs in and allows the connection on a RUTERA page; the connection then appears under API tokens of the user |
| Authorization with a fixed header | a personal token of the user: `Authorization: Bearer rtr_...` (Settings -> API tokens) |
| REST API | `https://<host>/api/v1/...`, OpenAPI 3 document at `/api/v1/openapi.json` |
| Limits | 120 calls per token and minute by default (429 with `Retry-After`); every call is logged with the token |

Without the AI access add-on of the firma every call answers 403 `api.ai_access_required`.

## How the AI gets its guide (proposal, not built yet)

- Today the MCP server returns general instructions in `initialize`; the role guides are not served yet.
- Proposal: the MCP server returns the guide of the user's role as a resource (`rutera://guide`) and in its
  instructions; the same text at `GET /api/v1/guide` for clients without MCP.
- The effective privileges of the user always win over the guide: an action the user may not do answers 403
  `auth.access_denied`.

## Rules for all roles

- You act for exactly one user inside exactly one firma; other firmas do not exist for you.
- Actions that cannot be undone or that leave RUTERA (e-mail to a contractor, deleting, issuing a CMR, cancelling
  a document) only after the user's explicit confirmation.
- Answer in the user's language.
- Dates are `yyyy-MM-dd`, times ISO instants in UTC, amounts in the currency of the record; errors come back as
  `{code, message, details}` with a code per field (`validation.required`, `...taken`).
- Update tools (PUT) replace the whole record: read it first, change the fields, send it back complete.

## Language

From version 0.2 the guides are written in English (the owner's rule for files; also what AI assistants read
best). Open question 14 of `../REQUIREMENTS.md`: whether to serve translations in the user's language.

Related: `../REQUIREMENTS.md` (4.12 "AI access", principle 8, section 17), `../../README.md` (API and MCP).

## Change log

| Version | Date | Changes |
|---|---|---|
| 0.1 | 2026-10-27 | Drafts in Russian with placeholder tools |
| 0.2 | 2026-11-03 | English; real tools, connection with OAuth and tokens, access from the role defaults in code |
