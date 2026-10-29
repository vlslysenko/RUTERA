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
| PUT | `/app/me/password` | `{currentPassword, newPassword}` -> 204; 422 details `currentPassword: auth.password.wrong_current`, `newPassword: validation.required \| validation.too_short \| validation.too_long` |
| GET | `/app/i18n/{lang}` | flat `{key: text}` dictionary of `messages_<lang>.properties` (public) |
| GET | `/actuator/health` | liveness (public) |

`User = {id, login, name, role, firma: {id, name} | null, language, startScreen | null, privileges: string[]}`;
`privileges` is the effective set of `module.entity/action` keys (empty for the superuser, who bypasses checks),
so the client can hide what the user may not do. Every 4xx/5xx body is `{code, message?, details?}` where
`code` is an i18n key; `message` is that text in the language of the request (the user's language, otherwise
`Accept-Language`).

### User administration (`/app/admin/users`, privileges `admin.user/view` and `admin.user/manage`)

| Method | Path | Body / result |
|---|---|---|
| GET | `/app/admin/users` | `AdminUser[]` of the own firma, sorted by name; deleted users excluded |
| GET | `/app/admin/users/{id}` | `AdminUser`; 404 for unknown ids and users of other firmas |
| POST | `/app/admin/users` | `{login, firstName, lastName?, role, customRoleId?, phone?, language?, password}` -> 201 `AdminUser` |
| PUT | `/app/admin/users/{id}` | `{firstName, lastName?, role, customRoleId?, phone?, language, active}` -> `AdminUser` |
| PUT | `/app/admin/users/{id}/password` | `{newPassword}` -> 204 (also lifts a lockout) |
| POST | `/app/admin/users/{id}/unlock` | 204: clears `locked_until` and the failure counter |
| DELETE | `/app/admin/users/{id}` | 204: soft delete; the login stays reserved |

`AdminUser = {id, login, firstName, lastName, name, role, customRoleId, phone, language, active, locked,
failedLogins, createdAt, updatedAt}`. `role` is one of `OWNER, DISPATCHER, LOGISTICIAN, ACCOUNTANT, DRIVER,
CUSTOM` (`CUSTOM` needs a `customRoleId` of the firma); `login` is trimmed and lower-cased, 3 to 254 characters
of letters, digits and `. _ @ + -`, globally unique; `language` defaults to the firma language on creation.
Passwords are 10 (`RUTERA_PASSWORD_MIN_LENGTH`) to 72 characters.

Rules: a user may manage only users whose role level is greater than or equal to their own and may assign only
such roles (`Role.getLevel()`: OWNER 1, DISPATCHER/LOGISTICIAN/ACCOUNTANT 3, CUSTOM its own level, DRIVER 9),
otherwise 403 `admin.user.role_not_allowed`. Nobody changes their own role or `active` flag, resets their own
password here or deletes themselves: 403 `admin.user.self_forbidden` (own name, phone and language are fine).
422 `error.validation` carries per-field codes, including `login: admin.user.login_taken`. Deactivating or
deleting a user ends their session on their next request; a role change takes effect for an open session on its
next `GET /app/auth/me` (the client calls it on every load).

Service and controller methods are guarded with `@RequiresPrivilege("module.entity/action")` (AOP, module
`core.security`); the effective set = role defaults + custom role + per-user overrides, computed at login. Every
request carries `requestId`, `firmaId` and `userId` in the MDC (log lines) and the same `requestId` in `audit_log`.

## Conventions

- SMDP (`C:\IQ_VSL\GIT\pc-server-new\pc-server`) is a read-only source of ideas and code; nothing is copied 1:1.
- Every business table carries `firma_id` (Hibernate `@TenantId`); the only exception is `app_user`.
- Texts live in `messages_xx.properties` only; English is the source language, new keys go to the end of each file.
- Privilege keys are `module.entity/action` strings from `com.rutera.common.security.Privileges`.
