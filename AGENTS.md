# saml-login-provider

Jahia OSGi **server-level** module that redirects unauthenticated users to the SAML connect
endpoint on configured hosts. Admin UI at `/jahia/administration/{siteKey}/samlLoginProvider`
(any site — the configuration itself is server-wide).

## Key Facts

- **artifactId**: `saml-login-provider` | **version**: `2.0.0-SNAPSHOT`
- **Java package**: `org.jahia.community.modules.samlloginprovider`
- **jahia-depends**: `default,siteSettings,graphql-dxm-provider`
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
| `SamlLoginProviderQueryExtension` / `MutationExtension` | GraphQL query/mutation, `DXGraphQLProvider` type extensions |
| `SamlLoginProviderPermissions` | Site-admin permission gate shared by both extensions |
| `GqlSamlLoginProviderConfig` | GraphQL return type (`{fqdn}`) |

## GraphQL API

| Operation | Name | Notes |
|-----------|------|-------|
| Query | `samlLoginProviderConfig(siteKey!)` → `GqlSamlLoginProviderConfig` | `siteKey` only gates the permission check |
| Mutation | `updateSamlLoginProviderConfig(siteKey!, fqdn)` → `GqlSamlLoginProviderConfig` | Empty/null `fqdn` disables the redirect |

Permission: `siteAdminSamlLoginProvider` (checked via `callerSession.getNode(sitePath).hasPermission(...)`
on the caller's session, both query and mutation — see `SamlLoginProviderPermissions`).

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

- Admin path: `/jahia/administration/{siteKey}/samlLoginProvider`
- Specs cover: reading/saving the host list, validation errors, and the `/saml-login` /
  `/home.connect.saml` redirect behavior for configured vs. non-configured hosts

## Gotchas

- The configuration is **server-wide**, stored in `karaf/etc/org.jahia.community.samlloginprovider.cfg`
  — not scoped to the site the admin screen is opened from. Do not reintroduce per-site storage.
- `LogoutConfig` (core) only ever consults the **first** bound `LogoutUrlProvider` — this module
  assumes it is the only one deployed; do not silently rely on multiple providers cooperating.
- `resolveOriginalUrl` must keep returning `null` for `.saml`/`/saml-login` suffixed URLs — otherwise
  the choice page or the SAML endpoint itself could become a post-login redirect target (loop).
- `HostList`/`WebUtils` validation must stay in front of any JCR path concatenation
  (`SamlLoginProviderPermissions`) — an unvalidated `siteKey` could otherwise normalize to a
  different node.
