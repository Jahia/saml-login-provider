# saml-login-provider

Jahia OSGi module that replaces the login page of a site with a SAML-aware one. On a site where the
module is **enabled** and that has **SAML authentication set up** (an enabled saml-authentication-valve
connector), unauthenticated visitors get `/saml-login` (credentials form + "Sign in with Google"),
and the hosts in that site's list go straight to the SAML connect endpoint. Admin UI at
`/jahia/administration/<siteKey>/samlLoginProvider` (the site's administration, shown only where
the module is enabled).

## Key Facts

- **artifactId**: `saml-login-provider` | **version**: `1.0.0`
- **Java package**: `org.jahia.community.modules.samlloginprovider`
- **jahia-depends**: `default,siteSettings,graphql-dxm-provider,jahia-authentication` (whether a
  site has SAML set up is read from jahia-authentication's `SettingsService`)
- Configuration stored as one file-backed OSGi **factory** configuration per site
  (`karaf/etc/org.jahia.community.samlloginprovider.site-<siteKey>.cfg`, properties `siteKey`,
  `fqdn`), not JCR — see `FileSiteHostsStore`
- Fail-safe default: a site where the module is not enabled, a site with no SAML connector, and a
  host resolving to no single site all keep Jahia's stock login

## Architecture

| Class | Role |
|-------|------|
| `SamlLoginUrlProvider` | `LoginUrlProvider`/`LogoutUrlProvider` OSGi services; routes by the site the host resolves to, only where the module is enabled and SAML is set up |
| `SamlSetup` / `JahiaAuthSamlSetup` | Whether a site has an enabled SAML connector (`SettingsService.getConnectorConfig(site, "Saml")`, property `enabled`) |
| `SamlLoginPageFilter` | Serves `/saml-login` (credentials form + "Sign in with Google") |
| `SiteHostsStore` / `FileSiteHostsStore` | Per-site host lists; the file-backed `ManagedServiceFactory` (factory PID `org.jahia.community.samlloginprovider.site`) that reads and atomically writes one `.cfg` per site |
| `SiteResolver` / `JahiaSiteResolver` | Host → site key (`JahiaSitesService.getSitenameByServerName`, the lookup the SAML connect endpoint uses) and site → its server names |
| `HostList` | Parsing/validation of the comma-separated `fqdn` host list |
| `WebUtils` | Shared URL-encoding and site-key validation helpers |
| `SamlLoginProviderQueryExtension` / `MutationExtension` | Publish the single `samlLoginProvider` namespace field on `DXGraphQLProvider.Query` / `Mutation` |
| `SamlLoginProviderGraphQLExtensionsProvider` | `DXGraphQLExtensionsProvider` OSGi component that publishes this package's `@GraphQLTypeExtension` classes to graphql-dxm-provider |
| `SamlLoginProviderQuery` / `SamlLoginProviderMutation` | Namespace containers holding the actual `config` / `update` operations |
| `SamlLoginProviderPermissions` | Site-admin permission gate shared by both containers (checks the site node) |
| `SamlLoginProviderServices` | OSGi lookups for the GraphQL containers (instantiated by graphql-java, so no injection) |
| `GqlSamlLoginProviderConfig` | GraphQL return type (`{siteKey, fqdn, serverNames}`) |

## GraphQL API

All operations are grouped under one `samlLoginProvider` namespace field (on `Query` and
`Mutation` respectively) rather than added as flat root fields, per this project's convention for
avoiding cross-module field name collisions in the platform-wide GraphQL schema.

| Operation | Name | Notes |
|-----------|------|-------|
| Query | `samlLoginProvider { config(siteKey: String!) }` → `GqlSamlLoginProviderConfig` | The site's list plus `serverNames`, the only values the list may contain |
| Mutation | `samlLoginProvider { update(siteKey: String!, fqdn) }` → `GqlSamlLoginProviderConfig` | Empty/null `fqdn` turns the module off for that site; every host must be the site's server name or an alias |

Permission: `siteAdminSamlLoginProvider` — a **site-level** permission (declared under
`site-admin` in `permissions.xml`), checked via `callerSession.getNode("/sites/<siteKey>")
.hasPermission(...)` on the caller's session, for both query and mutation — see
`SamlLoginProviderPermissions`. A missing or unreadable site is denied like a missing permission,
so the API cannot enumerate sites. This is sound only because storage and routing are per site too
— see the Gotchas section.

## Build

```bash
mvn clean install
yarn build
yarn lint:js
yarn lint:scss
```

SonarQube project key: `org.jahia.community:saml-login-provider` (`mvn clean install sonar:sonar`).

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

- Admin path: `/jahia/administration/<siteKey>/samlLoginProvider` (specs use digitall)
- Specs cover: reading/saving the host list, validation errors, the `/saml-login` /
  `/home.connect.saml` redirect behavior for configured vs. non-configured hosts, and (spec 03)
  the full auto-login round trip through a real Keycloak — the `idp` compose service, with the
  SAML modules provisioned on digitall — ending in a Jahia session with the mapped attributes
- Spec 03 runs only inside the compose network (`digitall.jahia.test` / `idp.jahia.test` aliases);
  see `tests/README.md` for iterating on it without rebuilding the image
- The host list reaches `SamlLoginUrlProvider` asynchronously (ConfigurationAdmin), a fraction of
  a second after the mutation returns: specs poll for its effect (`cypress/utils/samlLoginProvider.ts`)
  instead of asserting on the first response
- Run `mvn clean install` first: `ci.build.sh` copies `../target/*-SNAPSHOT.jar` into the image
- Results: `docker cp "cypress:/home/jahians/results" .` (from `tests/`; git-ignored)
- Jahia is published on `127.0.0.1` only (HTTP 8080, JDWP 8000 with JPDA on); Cypress reaches it over the compose network
- Manual end-to-end login from a browser on your machine (separate Keycloak on `127.0.0.1:8180`):
  see `tests/saml-idp/README.md`. It puts a SAML connector on both `digitall` (`digitall.localhost`,
  in the host list) and `systemsite` (`localhost`, reached through `/saml-login`), each with its
  own host in its SP URL and its own Keycloak client. It shares the realm file and the Groovy
  scripts in `tests/cypress/fixtures/groovy/` with spec 03

## Gotchas

- The configuration is **per site**, and three things must stay per site together: **storage**
  (one `.cfg` per site), **routing** (`SamlLoginUrlProvider` resolves the request host to its site
  and consults only that site's list) and **validation** (the mutation accepts only the site's own
  server name and aliases, and only hosts declared by that site ALONE). Together they make the
  per-site permission sound: a site admin's list
  can only ever affect requests that resolve to their own site. Never let one of the three go
  global again. An earlier version stored ONE server-wide list but gated it with this per-site
  permission, which let any site's admin disable SAML enforcement platform-wide or add a host
  (login lockout / DoS) they had no authority over; the fix at the time made everything
  server-level, and the current design makes everything per site instead.
- **Hosts shared by several sites are never routed and cannot be listed.** Jahia does not keep
  server names unique (stock `localhost` is shared by systemsite and UI-created sites) and its own
  lookup returns an arbitrary match, so `JahiaSiteResolver.siteKeyFor` only resolves a host
  declared by exactly one site. Its routing index is cached for 5 s (the login path is hot);
  validation (`sitesClaiming`, `serverNamesOf`) always reads fresh data.
- A file only configures the site its name says: `FileSiteHostsStore.updated` rejects a `siteKey`
  that does not match `felix.fileinstall.filename`, and `save` refuses to overwrite or delete a file
  holding another site's key (site keys differing only by case collide on case-insensitive
  filesystems). `save`, `updated` and `deleted` are `synchronized`.
- Per-site configs are file-backed factory configurations (`ManagedServiceFactory` +
  `<factoryPid>-<siteKey>.cfg` written atomically), NOT created through
  `ConfigurationAdmin.createFactoryConfiguration`: API-created factory configs are bundle-scoped
  and get dropped on bundle reinstall. `save()` also updates the in-memory view, so a change applies
  to the next request rather than whenever FileInstall polls. The module ships no default `.cfg`.
- `LogoutConfig` (core) only ever consults the **first** bound `LogoutUrlProvider` — this module
  assumes it is the only one deployed; do not silently rely on multiple providers cooperating.
- `resolveOriginalUrl` must keep returning `null` for `.saml`/`/saml-login` suffixed URLs — otherwise
  the choice page or the SAML endpoint itself could become a post-login redirect target (loop).
- **Login lockout risk**: every host added to a site's list must be served by a working
  saml-authentication-valve connector for that site. If a host is added
  without SAML valve setup, users are redirected to `/home.connect.saml` (a 400 error with no
  fallback), locking them out entirely. The `HostList.isValid()` method validates hostname syntax
  but not SAML valve deployment — operators must verify prerequisites before adding hosts.
