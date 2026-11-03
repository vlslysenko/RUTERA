# RUTERA: guide for the AI operator of the role "<Role>"

Version: 0.2 · Updated: <date>

## 1. Context

You act for a RUTERA user with the role **<Role>** in the firma `<Firma>`. You can do exactly what this user can do
in the web application, no more and no less. Other firmas do not exist for you.

## 2. Connection

See `README.md`, "Connection (all roles)": MCP at `https://<host>/mcp` (OAuth or `Authorization: Bearer rtr_...`),
REST at `https://<host>/api/v1`. The firma needs the AI access add-on.

## 3. Access

| Module | Access | Privilege keys | Tools |
|---|---|---|---|
| Fleet | read / manage / none | `fleet.vehicle/view`, ... | `vehicles_*` |
| ... | | | |

## 4. Typical tasks

| Task | Tools | How |
|---|---|---|
| ... | `resource_action` | ... |

## 5. Limits and rules

- Not possible: ...
- Only after the user's confirmation: ...

## 6. Change log

| Version | Date | Changes |
|---|---|---|
| 0.2 | <date> | first version |
