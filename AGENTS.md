# saml-login-provider

Jahia OSGi **server-level** module that redirects unauthenticated users to the SAML connect
endpoint on configured hosts. Admin UI at `/jahia/administration/samlLoginProvider` (Server
settings — the configuration itself is server-wide, and so is its admin route and permission).

## Key Facts

- **artifactId**: `saml-login-provider` | **version**: `2.0.0-SNAPSHOT`
- **Java package**: `org.jahia.community.modules.samlloginprovider`
- **jahia-depends**: `default,serverSettings,graphql-dxm-provider`
- Configuration stored as an OSGi config (`org.jahia.community.samlloginprovider.cfg`), not JCR —
  server-wide, not per-site
- Fail-safe default: an empty/unset `fqdn` property disables the module everywhere

## Architecture

| Class | Role |
|-------|------|
| `SamlLoginUrlProvider` | `LoginUrlProvider`/`LogoutUrlProvider` OSGi services; core routing decision |
| `SamlLoginPageFilter` | Serves `/saml-login` (credentials form + "Sign in with Google") |
| `ConfigStore` | Read/write access to the OSGi config through `ConfigService` |
| `HostList` | Parsing/validation of the comma-separated `fqdn` host list |
| `WebUtils` | Shared URL-encoding and site-key validation helpers |
| `SamlLoginProviderQueryExtension` / `MutationExtension` | Publish the single `samlLoginProvider` namespace field on `DXGraphQLProvider.Query` / `Mutation` |
| `SamlLoginProviderQuery` / `SamlLoginProviderMutation` | Namespace containers holding the actual `config` / `update` operations |
| `SamlLoginProviderPermissions` | Server-admin permission gate shared by both containers (checks the JCR root node) |
| `GqlSamlLoginProviderConfig` | GraphQL return type (`{fqdn}`) |

## GraphQL API

All operations are grouped under one `samlLoginProvider` namespace field (on `Query` and
`Mutation` respectively) rather than added as flat root fields, per this project's convention for
avoiding cross-module field name collisions in the platform-wide GraphQL schema.

| Operation | Name | Notes |
|-----------|------|-------|
| Query | `samlLoginProvider { config(siteKey) }` → `GqlSamlLoginProviderConfig` | `siteKey` is optional, caller-context only — it does not gate the permission check |
| Mutation | `samlLoginProvider { update(siteKey, fqdn) }` → `GqlSamlLoginProviderConfig` | Empty/null `fqdn` disables the redirect |

Permission: `samlLoginProviderAdmin` — a genuine **server-level** permission (declared under the
built-in `admin` node in `permissions.xml`), checked via
`callerSession.getNode("/").hasPermission(...)` on the caller's session, both query and mutation
— see `SamlLoginProviderPermissions`. This replaced a per-site `siteAdminSamlLoginProvider`
permission that let any single site's admin rewrite this server-wide setting for the whole
platform (privilege escalation) — see the Gotchas section.

## Build

```bash
mvn clean install
yarn build
yarn lint
```

## Tests

```bash
mvn test                 # JUnit 5 + Mockito unit tests
```

### Cypress Docker (see `tests/README.md`)

```bash
cd tests
cp .env.example .env
yarn install
./ci.build.sh && ./ci.startup.sh
```

- Admin path: `/jahia/administration/samlLoginProvider`
- Specs cover: reading/saving the host list, validation errors, and the `/saml-login` /
  `/home.connect.saml` redirect behavior for configured vs. non-configured hosts

## Gotchas

- The configuration is **server-wide**, stored in `karaf/etc/org.jahia.community.samlloginprovider.cfg`
  — not scoped to any site. The default `.cfg` file shipped by the module starts with the line
  `# default configuration - won't be overridden`, which means it is copied to `karaf/etc` only
  on first deployment; subsequent changes made via the admin UI or manual edits are preserved
  across module redeployments. Do not reintroduce per-site storage, and do not re-gate it behind a
  per-site permission: a prior version of this module checked `siteAdminSamlLoginProvider` on
  `/sites/{siteKey}`, which let any single site's admin disable SAML enforcement platform-wide or
  add an unconfigured host (login lockout / DoS) on hosts they had no authority over. The
  permission and admin route are now genuinely server-level; keep it that way.
- `LogoutConfig` (core) only ever consults the **first** bound `LogoutUrlProvider` — this module
  assumes it is the only one deployed; do not silently rely on multiple providers cooperating.
- `resolveOriginalUrl` must keep returning `null` for `.saml`/`/saml-login` suffixed URLs — otherwise
  the choice page or the SAML endpoint itself could become a post-login redirect target (loop).
- `SamlLoginProviderPermissions` checks the JCR root node (`/`), not a site node — do not
  reintroduce a `/sites/{siteKey}` path check here.
- **Login lockout risk**: Every host added to the `fqdn` list must have the saml-authentication-valve
  module properly configured and enabled on a site reachable at that host. If a host is added
  without SAML valve setup, users are redirected to `/home.connect.saml` (a 400 error with no
  fallback), locking them out entirely. The `HostList.isValid()` method validates hostname syntax
  but not SAML valve deployment — operators must verify prerequisites before adding hosts.
