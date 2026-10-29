# RUTERA

Cloud TMS and fleet management for small transport companies (5 to 30 vehicles).
Requirements: `docs/REQUIREMENTS.md`; database sketch: `docs/DB_SCHEMA.md`; AI role guides: `docs/ai-roles/`.

## Layout

| Path | Content |
|---|---|
| `pom.xml` | Maven parent (Spring Boot 4.0.x, Java 25) |
| `common/` | JPA entities, repositories, Flyway migrations (`db/migration`), i18n bundles (`i18n/messages_xx.properties`, 26 languages), tenant context, privilege catalogue |
| `core/` | Spring Boot application on Jetty: web layer, security, internal API for the React client (`/app/...`) |
| `frontend/` | React + TypeScript client (Vite, Mantine, PWA) |
| `compose.yaml` | MySQL 8.4 for local development |
| `docs/` | Requirements, DB schema, AI role guides, UI mockups |

## Build and run (backend)

Prerequisites: JDK 25, Maven 3.9+, Docker.

```bash
docker compose up -d          # MySQL 8.4 on localhost:3307 (3306 is often taken by another MySQL)
mvn verify                    # builds both modules
java -jar core/target/rutera.jar
```

Configuration defaults live in `core/src/main/resources/application.properties`. An installation overrides
them in `conf/application.properties` next to the jar or with environment variables (`RUTERA_DB_HOST`,
`RUTERA_DB_PORT`, `RUTERA_DB_NAME`, `RUTERA_DB_USER`, `RUTERA_DB_PASSWORD`, `RUTERA_PORT`). `conf/logback.xml`,
when present, replaces the built-in logging configuration. If `DOCKER_HOST` points at a remote engine, the
compose database is published there: set `RUTERA_DB_HOST` to that host.

Schema changes are Flyway migrations in `common/src/main/resources/db/migration/`; Hibernate runs with
`ddl-auto=validate`, so entities and migrations cannot drift apart unnoticed.

`mvn verify` runs the integration tests of `core` against a MySQL 8.4 started by Testcontainers, so Docker must
be reachable (`DOCKER_HOST` needs a scheme, e.g. `tcp://host:2375`; Testcontainers rejects a bare `host:port`).

### First start

On an empty database the application creates the first firma and its owner account from the
`RUTERA_BOOTSTRAP_*` variables (see `.env.example`). Without `RUTERA_BOOTSTRAP_ADMIN_PASSWORD` a random password
is generated and written to the log once (`First start: ... generated password`); change it after signing in.

## Internal API for the React client (`/app`)

Session cookie `RUTERA_SESSION` (HttpOnly, SameSite=Lax) + CSRF: cookie `XSRF-TOKEN` (readable by JavaScript) is
mirrored in header `X-XSRF-TOKEN` on every POST/PUT/PATCH/DELETE, login included. The first call (for example
`GET /app/auth/me`) hands out the CSRF cookie. After five wrong passwords the account is locked for 15 minutes
(`RUTERA_LOCKOUT_ATTEMPTS`, `RUTERA_LOCKOUT_DURATION`).

| Method | Path | Body / result |
|---|---|---|
| POST | `/app/auth/login` | `{login, password}` -> `User`; 401 `{code: auth.login.invalid \| auth.login.locked \| auth.login.disabled \| auth.login.firma_inactive}` |
| POST | `/app/auth/logout` | 204 |
| GET | `/app/auth/me` | `User`; 401 `{code: auth.unauthenticated}`; also refreshes the session's privilege snapshot |
| PUT | `/app/me/settings` | `{language?, startScreen?}` -> `User`; 422 `{code: error.validation, details: {field: validation.*}}` |
| GET | `/app/i18n/{lang}` | flat `{key: text}` dictionary of `messages_<lang>.properties` (public) |
| GET | `/actuator/health` | liveness (public) |

`User = {id, login, name, role, firma: {id, name} | null, language, startScreen | null}`. Every 4xx/5xx body is
`{code, message?, details?}` where `code` is an i18n key; `message` is that text in the language of the request
(the user's language, otherwise `Accept-Language`).

Service and controller methods are guarded with `@RequiresPrivilege("module.entity/action")` (AOP, module
`core.security`); the effective set = role defaults + custom role + per-user overrides, computed at login. Every
request carries `requestId`, `firmaId` and `userId` in the MDC (log lines) and the same `requestId` in `audit_log`.

## Conventions

- SMDP (`C:\IQ_VSL\GIT\pc-server-new\pc-server`) is a read-only source of ideas and code; nothing is copied 1:1.
- Every business table carries `firma_id` (Hibernate `@TenantId`); the only exception is `app_user`.
- Texts live in `messages_xx.properties` only; English is the source language, new keys go to the end of each file.
- Privilege keys are `module.entity/action` strings from `com.rutera.common.security.Privileges`.
