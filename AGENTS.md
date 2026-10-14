# saml-login-provider

Jahia OSGi module that replaces the login page of a site with a SAML-aware one. On a site where the
module is **enabled** and that has **SAML authentication set up** (an enabled saml-authentication-valve
connector), unauthenticated visitors get `/saml-login` (credentials form + "Sign in with Google"),
and the hosts in that site's list go straight to the SAML connect endpoint. A host that no site
declares (typically the editing host) falls back to systemsite's settings. Admin UI at
`/jahia/administration/<siteKey>/samlLoginProvider` (the site's administration, shown only where
the module is enabled).

## Key Facts

- **artifactId**: `saml-login-provider` | **version**: `1.1.0`
- **Java package**: `org.jahia.community.modules.samlloginprovider`
- **jahia-depends**: `default,siteSettings,graphql-dxm-provider,jahia-authentication` (whether a
  site has SAML set up is read from jahia-authentication's `SettingsService`)
- Configuration stored as one file-backed OSGi **factory** configuration per site
  (`karaf/etc/org.jahia.community.samlloginprovider.site-<siteKey>.cfg`, properties `siteKey`,
  `fqdn`, `buttonLabel`), not JCR — see `FileSiteHostsStore`
- Fail-safe default: a site where the module is not enabled, a site with no SAML connector, and a
  host declared by several sites all keep Jahia's stock login; so does a host no site declares
  unless systemsite qualifies

## Architecture

| Class | Role |
|-------|------|
| `SamlLoginUrlProvider` | `LoginUrlProvider`/`LogoutUrlProvider` OSGi services; routes by the site the host resolves to, only where the module is enabled and SAML is set up |
| `SamlSetup` / `JahiaAuthSamlSetup` | Whether a site has an enabled SAML connector (`SettingsService.getConnectorConfig(site, "Saml")`, property `enabled`) |
| `SamlLoginPageFilter` | Serves `/saml-login` (credentials form + "Sign in with Google") |
| `CmsLoginRedirectFilter` | On `/cms/*`, for a plain `GET`/`HEAD` of `/cms/login` (path parameters stripped, no `loginError`/`username`/`restMode`/`redirectActive`): redirects to `SamlLoginUrlProvider.loginUrlFor(request, redirect)` where the provider acts. Covers core's Shiro-guarded paths, which never reach the provider |
| `SamlLoginReturnFilter` | Serves `/saml-login-return?redirect=<target>` at order 0 (after core's `JcrSessionFilter`, -1): the SAML login completes there, then the visitor goes on to the validated target. SAML redirects (connect URL, choice page's Google form) go through it |
| `SamlRedirectCookieFilter` | On the valve's connect endpoint (registered as `*.saml` — Jahia matches `*.<ext>` on the last dot only — then filtered on the `connect.saml` suffix), just before the valve's `SAML2Filter`: writes the valve's `redirect` cookie with `SameSite=None; Secure`, so a cross-site IdP (Google) does not lose the post-login target |
| `SiteHostsStore` / `FileSiteHostsStore` | Per-site host lists; the file-backed `ManagedServiceFactory` (factory PID `org.jahia.community.samlloginprovider.site`) that reads and atomically writes one `.cfg` per site |
| `SiteResolver` / `JahiaSiteResolver` | Host → the ONE site declaring it, whether no site declares it (`isUnclaimed`), site → its server names and whether the module is enabled on it. Reads every site through a system session; the routing index is cached 5 s, validation reads are fresh |
| `LoginSite` | The site governing a host's login: its own unique site, `systemsite` (fallback) when no site declares it, none when several do. Shared by the provider and the filter |
| `ButtonLabel` | A site's custom SAML sign-in button text: normalization (trim), validation (≤ 80 UTF-16 units, no control/line-separator chars) and its URL-encoded `.cfg` form |
| `HostList` | Parsing/validation of the comma-separated `fqdn` host list |
| `WebUtils` | Shared URL-encoding and site-key validation helpers |
| `SamlLoginProviderQueryExtension` / `MutationExtension` | Publish the single `samlLoginProvider` namespace field on `DXGraphQLProvider.Query` / `Mutation` |
| `SamlLoginProviderGraphQLExtensionsProvider` | `DXGraphQLExtensionsProvider` OSGi component that publishes this package's `@GraphQLTypeExtension` classes to graphql-dxm-provider |
| `SamlLoginProviderQuery` / `SamlLoginProviderMutation` | Namespace containers holding the actual `config` / `update` operations |
| `SamlLoginProviderPermissions` | Site-admin permission gate shared by both containers (checks the site node) |
| `SamlLoginProviderServices` | OSGi lookups for the GraphQL containers (instantiated by graphql-java, so no injection) |
| `GqlSamlLoginProviderConfig` | GraphQL return type (`{siteKey, fqdn, serverNames, samlConfigured, buttonLabel}`) |

## GraphQL API

All operations are grouped under one `samlLoginProvider` namespace field (on `Query` and
`Mutation` respectively) rather than added as flat root fields, per this project's convention for
avoiding cross-module field name collisions in the platform-wide GraphQL schema.

| Operation | Name | Notes |
|-----------|------|-------|
| Query | `samlLoginProvider { config(siteKey: String!) }` → `GqlSamlLoginProviderConfig` | The site's list, its `serverNames`, `samlConfigured` (enabled SAML connector) and `buttonLabel` (empty = default) |
| Mutation | `samlLoginProvider { update(siteKey: String!, fqdn, buttonLabel) }` → `GqlSamlLoginProviderConfig` | `buttonLabel` omitted keeps the current text, `""` restores the default. Empty/null `fqdn`: nobody goes straight to SAML (the site's hosts get the choice page). Every host must be the site's server name or an alias, declared by no other site; systemsite may also list hosts no site declares |

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
  SAML modules provisioned on digitall — ending in a Jahia session with the mapped attributes, and
  (spec 04) the systemsite fallback on `edit.jahia.test`, a host no site declares: choice page,
  `siteKey=systemsite` in the Google form and the connect URL, and a login through systemsite's
  connector (whose callback is `/home.callback.saml?siteKey=systemsite`)
- Specs 03/04 run only inside the compose network (`digitall.jahia.test` / `edit.jahia.test` /
  `idp.jahia.test` aliases);
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
- **A host that NO site declares falls back to systemsite** (`LoginSite`) — never a shared host.
  This is the one deliberate exception to "a list only affects its own site": systemsite's admin is
  effectively the server admin, and nobody else governs such hosts. Keep three things in step:
  `JahiaSiteResolver.isUnclaimed` must stay `false` when the sites could not be read (otherwise a
  read failure would send every host to systemsite); every SAML URL for a fallback host must carry
  `siteKey=systemsite` (the valve's `SAML2Util.findSiteKeyForRequest` reads that parameter first,
  else resolves the host, finds no site and answers 400) — the provider adds it to the connect URL,
  the filter adds a hidden field to the Google form; and systemsite's connector needs
  `Saml.incomingTargetUrl = /home.callback.saml?siteKey=systemsite` and its `serverLocation` on the
  editing host, or the IdP's callback cannot find the connector either. A host later declared by a
  site leaves systemsite's governance: systemsite's stored entry for it is then simply ignored.
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
- **`buttonLabel` is stored URL-encoded, never raw.** Felix FileInstall reads `.cfg` values as typed
  properties: a leading `[` is an array, `"` starts a quoted token, `${...}` is interpolated. A raw
  free-text label could be misread or break the whole file, host list included. `ButtonLabel.encode`
  emits only `[A-Za-z0-9%+._*-]`; a bad label in a hand-edited file rejects the whole update, like a
  bad host. The file is deleted only when the site has neither hosts nor a label. The choice page
  takes the label of the site governing the host (`LoginSite`, so systemsite for the editing host)
  and HTML-escapes it; any failure falls back to the translated default.
- **Core's Shiro guard bypasses `LoginUrlProvider`.** `/tools/**`, `/modules/tools/**`,
  `/modules/**/*.jsp`, `*.raw`, `/cms/serverStats` are protected by Shiro (`applicationcontext-security.xml`
  in jahia-impl, `loginUrl` hard-wired to `/cms/login`); `JahiaAuthenticationFilter.redirectToLogin`
  redirects to `/cms/login?redirect=<uri>` itself, and an anonymous `GET /cms/login` forwards to the
  stock `error_401.jsp` — `LoginConfig` is never consulted. `CmsLoginRedirectFilter` fixes that at the
  `/cms/login` end. The incoming `redirect` is untrusted: `loginUrlFor` keeps only a plain relative
  path (printable ASCII, no `\`, not `//`) and drops login endpoints, now including `/cms/login`
  (loop). The provider is also published as `SamlLoginUrlProvider` for this filter to reference.
- **A SAML login must not complete on a Shiro-guarded URL** (core bug). The valve's callback only
  stores the profile; the next request completes the login, and the auth pipeline then flags the
  session for invalidation. On `/tools` etc. Shiro (order -3) runs that pipeline itself
  (`JahiaAuthenticationFilter.onAccessDenied`), invalidates the session, then `executeLogin`s a
  subject bound to the dead session: `InvalidSessionException`, a 500 until reload. Elsewhere
  `JcrSessionFilter` (-1) handles it fine. So SAML targets are wrapped by
  `SamlLoginUrlProvider.returnHop`: `/saml-login-return?redirect=<target>`, served at order 0. The
  credentials form needs no hop (it logs in within `/cms/login`). The hop must not use
  `encodeRedirectURL`: Shiro rejects `;jsessionid=` on its paths (`blockSemicolon`). The hop path
  is part of the loop guard. With no target the Google form carries no `redirect` at all (the valve
  then uses `postLoginPath`); an empty hop would send visitors home. The valve's `&site=<key>` now
  lands on the hop URL and is not carried on to the target.
- **Cross-site IdPs lose the post-login target without `SamlRedirectCookieFilter`.** The valve
  (4.1.1, `SAML2Util.storeAuthenticationContext`) keeps the validated `redirect` parameter in a
  `redirect` cookie with no `SameSite`, read back at the callback, else falls back to the
  connector's `postLoginPath` (+ `?site=<siteKey>`). Browsers treat it as `Lax` and do not send it
  on the IdP's cross-site POST. The filter writes that one cookie itself as `SameSite=None; Secure`,
  only over HTTPS (`isSecure()` or `X-Forwarded-Proto: https`) and only for RFC 6265-safe values;
  otherwise it delegates unchanged. It must stay ordered before the valve (`-5.45` < `-5.4`;
  Jahia sorts filters ascending), and its pattern must stay `*.saml`: `*.connect.saml` never matches.
  CI cannot show the browser side (http, same-site Keycloak); spec 04 checks the header instead.
- `resolveOriginalUrl` must keep returning `null` for `.saml`/`/saml-login` suffixed URLs — otherwise
  the choice page or the SAML endpoint itself could become a post-login redirect target (loop).
- **Login lockout risk**: routing requires an *enabled* connector (`SamlSetup`), so a listed host is
  never sent to SAML on a site with none — but an enabled connector that is broken (wrong IdP
  metadata, a systemsite connector without `?siteKey=systemsite` in its callback, an IdP that does
  not accept the callback URL) still fails the login of every listed host, with no form to fall
  back to. Verify the connector end to end on an unlisted host (the choice page's Google button)
  before listing hosts; `/cms/login` keeps working for local accounts either way.
