# DataEase Enhance — phase 1: identity, organization and ACL foundations

This module is original self-developed code. It does not depend on proprietary `de-xpack` sources or disable third-party license checks.

## Deployment and activation

Default: `dataease.enhance.enabled=false`. Nothing is registered unless explicitly enabled.
Run database backup before first activation. Enhanced login uses its own credentials and does
**not** automatically import existing enterprise users or permissions.

Only activate in an isolated non-production environment until the remaining legacy API
integration has been reviewed. The existing DataEase `de2api` routes are filtered
when enhancement is enabled; external shared-link compatibility needs its own tests.

Configure via protected environment values (never commit actual values):

```yaml
dataease:
  enhance:
    enabled: ${DE_ENHANCE_ENABLED:false}
    token-secret: ${DE_ENHANCE_TOKEN_SECRET:}
    bootstrap-password: ${DE_ENHANCE_BOOTSTRAP_PASSWORD:}
    session-minutes: 60
    node-id: 1
```

`token-secret` must be 32+ characters and must be stable across restarts.
`bootstrap-password` must be 12+ characters **at first activation**; it is
never applied again if a user already exists. Store secrets in environment or a
secret manager; never expose them in browser configuration. Use a unique
`node-id` (0..1023) per writer.

## Source boundaries

- `addons/dataease-enhance/`: self-owned functionality, migration and tests.
- `core/pom.xml`, `core/core-backend/pom.xml`: minimal module wiring.
- Three `core/.../substitute/permissions/*` controllers gain an opt-in
  condition, preserving community behavior when disabled.
- No modifications to the proprietary X-Pack submodule.

## API foundation

Original DataEase compatibility:
`POST /login/localLogin` (RSA transport),
`GET /login/refresh`, `GET /logout`,
`GET /user/info`, `GET /user/personInfo`,
`POST /user/mumberPager/{page}/{size}`, `POST /org/mounted`.

New enhanced APIs (all require `X-DE-TOKEN`):
- `GET/POST /enhance/admin/users`: list/create user (explicit strong password).
- `POST /enhance/admin/users/{id}/enabled` and `/reset-password`.
- `GET/POST /enhance/admin/orgs` and `DELETE /enhance/admin/orgs/{id}`.
- `POST /enhance/admin/memberships`, `DELETE /enhance/admin/memberships/{uid}/{oid}`.
- `GET/POST /enhance/admin/roles`, `POST /enhance/admin/assign-role`.
- `PUT /enhance/admin/roles/{id}/menus` and `/resources`.
- `GET /enhance/permissions/check`, `GET /enhance/menus/check`.
- `POST /enhance/me/password`.
- `GET /enhance/admin/audit`.

User/organization changes revoke existing sessions as appropriate.
ACL is deny-by-default for non-system administrators. Dynamic menu visibility is integrated in `MenuServer`; backend ACL checks remain the actual security boundary. Resource data filters
and legacy chart/dataset server-side `@DePermit` enforcement are **not fully
integrated** yet; do not call this a completed production RBAC deployment.

## Data ownership

Versioned additive SQL (tracked in `de_ext_schema_version`): `src/main/resources/db/enhance/V001__identity_org_acl.sql`.
Tables use `de_ext_*` prefixes and do not mutate DataEase-owned tables.
Bootstrap only inserts the initial organization/admin when enhanced user table
is empty. Back up database and perform schema migration dry-runs before rolling out.

## Regression tests

```bash
mvn -f sdk/pom.xml -DskipTests install
mvn -f addons/dataease-enhance/pom.xml test
```

High-risk integration tests still needed before enabling in a public deployment:
existing share/embed flows, dynamic menus, dataset/chart row-column security,
multi-organization authorization propagation, login RSA exchange,
and all affected WebSocket consumers.

## Follow-up hardening and integration checks

- JWT issue/expiration uses \`java.util.Date\`, compatible with the pinned \`java-jwt 3.12.1\`.
- Login failures update the lockout counter atomically. Expired/untrusted/disabled/locked sessions fail closed.
- \`EnhanceOrgGovernanceController\` adds organization-scoped member/role operations.
- \`EnhanceRoleServer\` extends compatibility with the existing \`RoleApi\` paths
  (detail, role CRUD, role selection, user binding/unbinding).
- Org administrators cannot assign global \`SYS_ADMIN\` or mutate a different organization.
- Ordinary members cannot list every member's account details.
- \`/sysParameter/defaultSettings\` may be read by an authenticated normal user
  because the Vue login bootstrap requires it; secret-bearing settings remain denied.

The source-level isolation check uses cached Spring JDBC / H2 / BCrypt / JWT
jars with a temporary minimal SDK \`TokenVO\` stub. Nine committed H2-focused
JUnit cases were executed via a local JUnit-reflection harness with no database
or external network. This is **not** a substitute for the repository Maven
reactor build or a full HTTP/controller integration test.

**Production switch remains off by default.** Legacy dataset/chart permission
enforcement is still pending and non-admin access to those core API paths remains
explicitly blocked. Activation is for isolated development only.

### Additional legacy organization adapter

`EnhanceOrgCompatServer` implements the existing `/org/page/create`,
`/org/page/edit`, `/org/page/delete`, `/org/page/lazyTree`,
`/org/detail/{oid}` and `/org/resourceExist/{oid}` entry points using
only `de_ext_*` relationships. A built-in ORG_ADMIN role may be removed
when its organization has no members, custom roles, grants or child
organizations. A nonempty organization cannot be deleted. `delResource=true`
is rejected: it will **not** remove upstream BI resources. The resource
existence check covers enhancement-owned relations, not all upstream BI
metadata; broader organization/resource migrations still need explicit integration.

The isolated H2 regression suite now includes 11 focused tests,
including HTTP filter checks for anonymous/ordinary users and a
nonempty-organization deletion guard.

### User management compatibility (V002)

The additive V002__user_profile_and_soft_delete.sql describes email, telephone and a soft-delete marker. The Java migration runner checks columns before each ALTER TABLE and marks the version only after completion, making partial ALTER retries safe. Run a database backup and test-environment dry run before activation.

Existing V3 user URLs now support user/member pagination, query by ID, create, edit, enable/disable, soft delete, unlock and password modification. Platform user creation/disable/delete is for system administrators only; organization administrators may edit eligible members but cannot assign the global SYS_ADMIN privilege.

POST /user/create generates an independent 32-character initial password and returns it once with a DE_ENHANCE_ONETIME: prefix and Cache-Control: no-store. The UI shows this in a modal only once. /user/defaultPwd no longer returns a real password. Secure delivery to the new user is the administrator's responsibility.

Soft deletion revokes user sessions and memberships while retaining historic BI ownership references and reserving the account name.

Under enhanced mode, unauthenticated unsigned share/embed requests are intentionally blocked until their signing/authorization path has been integration tested. Core dataset and chart authorizations remain blocked for non-system administrators and are NOT fully integrated.

Tests include EnhanceUserFlowTest for a partial V002 migration restart, one-time credential issuance and deleted-user session revocation. Full Maven build remains blocked by absent upstream proprietary/custom dependencies. Keep dataease.enhance.enabled=false on public production deployments.

### Resource ownership (V003)

The V003__resource_ownership.sql migration creates de_ext_resource_registry, with a
unique (resource_type,resource_id) and a single owning organization. Only a
system administrator can register ownership, using POST
/enhance/admin/resources/register. Reassigning a resource to a different
organization is not supported by that endpoint; it returns a conflict.

Organization administrators may manage permissions of roles belonging to
their own organization through PUT /enhance/org/roles/{id}/menus and
PUT /enhance/org/roles/{id}/resources. Every resource grant is validated
against registered ownership before any existing role grants are replaced.
Unregistered resources and cross-organization grants are rejected.

Important: registration is maintained by a trusted system administrator and
does not yet automatically inspect upstream DataEase resource ownership.
Non-admin access to upstream dataset/chart/export APIs remains denied by
EnhanceTokenFilter until the entire query chain and row/column permissions
have been integrated and tested. This module is not a production-ready
replacement for the proprietary DataEase permission engine.

Latest isolated verification: 13 focused H2 JUnit tests (11 foundation,
1 user flow, 1 cross-organization resource ownership) completed successfully.