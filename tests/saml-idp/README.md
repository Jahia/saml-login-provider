# SAML IdP fixture — manual end-to-end testing

`saml-login-provider` only decides **where** to send an unauthenticated visitor: on a configured
host it redirects to `/home.connect.saml`. The `jahia-ee-dev` image serves 189 bundles and none of
them is `jahia-authentication` or `saml-authentication-valve`, so out of the box that redirect
target is a 400. The Cypress suite can therefore only assert *"the `Location` header looks right"*
(`02-samlLoginRedirect.cy.ts`), never that the redirect leads anywhere.

This fixture closes that gap: a real Keycloak IdP plus the authentication modules, wired so you can
follow the redirect through a login and back into Jahia.

It is **not** part of CI. `tests/ci.startup.sh`, the provisioning manifests and the specs are
untouched.

## Use

```bash
cd tests && ./ci.startup.sh          # the module's own stack: jahia + tests_stack network
cd tests/saml-idp
cp .env.example .env                 # then edit the values
./setup.sh
```

`setup.sh` is idempotent. It deploys the modules, starts Keycloak, imports the realm, sets the test
user's password, generates the SP keystore, and installs the connector config.

Configuration comes from `.env`, or from `SAML_IDP_ENV_FILE=/path/to/file`, or from variables
already exported — whichever is present.

### What you get

| | |
|---|---|
| Keycloak admin | <http://localhost:8180/admin> |
| Realm | `jahia-saml` |
| SAML end user | `saml.tester` |
| **Browse the SP at** | **<http://digitall.localhost:8080>** — this is what resolves to `digitall` |
| Jahia SP entity ID | `jahia-digitall` |
| ACS | `http://digitall.localhost:8080/home.callback.saml` |
| Connector | site `digitall`, OSGi factory config `org.jahia.modules.auth-digitall.cfg` |

`http://localhost:8080` still works for everything else, but it resolves to `systemsite`, which has
no SAML connector — see *Things that are easy to get wrong* below.

### The one manual step: the user mapper

`SAML2Filter.handleCallback()` hands the SAML profile to whichever mappers the connector has
configured (`config.getMappers()`). With none configured the assertion is accepted, the callback
redirects normally, and **no Jahia session is created** — a silent no-op rather than an error.

Wire it once in the UI:

> Administration → **digitall** → Authentication → SAML → **JCR Auth provider** →
> map the connector's `username` field to **Login username** (`ssoLoginId`, the only mandatory
> one) → Save

### Then exercise saml-login-provider itself

> `http://digitall.localhost:8080/jahia/administration/samlLoginProvider` → host list = `digitall.localhost`

The module matches `request.getServerName()`, so the value must be the host you browse with.
Log out, request a protected page, and you should be carried to Keycloak and back.

## Things that are easy to get wrong

**The three modules must come from the same generation.** They wire to each other through
`org.jahia.modules.jahiaauth.service`, and the import ranges are narrow. Mix generations and a
bundle silently fails to resolve — no error beyond `/home.connect.saml` continuing to answer 400.
`Jahia-Required-Version` is about the platform and says nothing about this; all six versions below
claim to support 8.2.3.0+, yet only same-generation sets actually work together.

| Module | Version | Exports / imports `jahiaauth.service` |
|---|---|---|
| `jahia-authentication` | 2.1.0 | exports `2.1.0` |
| `jcr-auth-provider` | 4.2.0 | imports `[2.0,3)` |
| `saml-authentication-valve` | 4.1.1 | imports `[2.0,3)` |

The previous generation (`1.8.1` / `3.4.1` / `3.1.1`) is equally self-consistent — the 3.x valve
imports `[1.7,2)` — but the two cannot be crossed in either direction. Check both ends before
bumping any one of them.

**The 4.x valve no longer registers a `LoginUrlProvider`.** `org.jahia.modules.saml2.providers.`
`CustomLoginProvider` existed in 3.1.1 and is gone in 4.1.1. That matters here: `AGENTS.md` notes
that core's `LogoutConfig` only ever consults the *first* bound provider, and this module assumes
it is the only one deployed. On the 3.x valve both modules bound one and the ordering decided the
behaviour; on 4.x that contention disappears, so a bench built on 3.x would not have reflected
what you ship against.

**The connector must live on the site your browsing host resolves to — and on a stock stack
nothing resolves to `digitall`.** `SAML2Util.findSiteKeyForRequest()` uses
`getSiteByServerName(request.getServerName())`, and `saml-login-provider` redirects with no
`siteKey` parameter. Measured on a fresh `tests/` stack:

```
site=systemsite  serverName=localhost
site=digitall    serverName=localhost
resolve(localhost) -> systemsite     resolve(digitall.localhost) -> null
resolve(jahia)     -> null           defaultSite        -> digitall
```

digitall is not *missing* a server name — it ships with the **same one systemsite has**. The lookup
has two candidates, systemsite wins, and digitall's server name is dead config. Nothing else
resolves either: there is no fallback to the default site even though that is `digitall`. So a
connector bound to `digitall` is unreachable by construction, and you get
`No SAML2 settings found for siteKey: systemsite`.

`setup.sh` fixes this by giving digitall its **own** `j:serverName` (= `SITE_HOST`, default
`digitall.localhost`) via `configure-site-host.groovy`, run through the provisioning API. That is the field
behind Administration → Sites → digitall → Web project settings → **Server name**, so the
configuration is visible where you would look for it. `systemsite` is untouched and
`localhost → systemsite` still holds. You then browse the SP at `http://digitall.localhost:8080`.
Browsing at `localhost:8080` lands on systemsite, which has no connector.

**`SITE_HOST` must be a real hostname — never `localhost` or `127.0.0.1`.** Jahia rewrites site
URLs to *absolute* ones built from `j:serverName`, so a loopback literal gets baked into every link
on the site:

```
href="http://127.0.0.1:8080/home.html"
href="http://127.0.0.1:8080/de/home.html"
```

which is meaningless to any client that is not this machine. `digitall.localhost` is a real name
that still needs no hosts-file entry — browsers and glibc/systemd-resolved both map `*.localhost`
to loopback.

Afterwards:

```
systemsite   serverName=localhost
digitall     serverName=digitall.localhost
resolve(localhost) -> systemsite     resolve(digitall.localhost) -> digitall
```

Changing `SITE_HOST` also means updating `redirectUris` and the `saml_assertion_consumer_url_*`
attributes in `realm/jahia-saml-realm.json`.

**The SP keystore must be supplied, not auto-generated.** `SAML2Util.validateSettings()` does
generate one when `keyStore` is absent, but only in memory. A `.cfg` in `karaf/etc` is read-only
(Felix logs `Adding READ_ONLY attribute to configuration` / `File is not writeable`), so it is never
persisted and the next request fails with `keystoreResource cannot be null`. Auto-generation only
works for connectors created through the admin UI, which are JCR-backed and writable. `setup.sh`
therefore builds `sp-keystore.jks` with `keytool` and ships it as base64.

**`incomingTargetUrl` must end in `callback.saml`.** `SAML2Filter` matches `*.saml` and dispatches
on the suffix (`connect` / `callback` / `metadata`). The sample config shipped inside the module
(`META-INF/configurations/org.jahia.modules.auth-sample.cfg`) still carries the pre-3.x
`/cms/login.SAML.incoming`, which this filter would never see.

**Binary properties are base64.** `ConnectorConfig.getBinaryProperty()` is
`Base64.getDecoder().decode(getProperty(...))`, so `identityProviderMetadata` and `keyStore` go into
a flat `.cfg` as base64 strings.

**Deploying a module jar does not enable it on a site.** Dropping a jar in `/var/jahia/modules`
installs and starts the bundle, but leaves `j:installedModules` on `/sites/<site>` untouched. This
is easy to miss here because `SAML2Filter` is a *global servlet filter*: the whole login flow works
with the module enabled on no site at all, so the only symptom is that
`saml-authentication-valve` appears "not enabled on the websites" — and the per-site settings
screen where you wire the connector's mapper is absent. `setup.sh` enables
`jahia-authentication`, `jcr-auth-provider` and `saml-authentication-valve` on `SITE_KEY` through
the provisioning API (`{"enable": "...", "site": "..."}`).

`saml-login-provider` is deliberately *not* enabled on any site: it is a server-level module whose
admin route lives under Server settings and whose permission is checked on the JCR root node.

**The realm JSON admits no stray keys.** Keycloak's importer deserializes strictly: one unknown
field anywhere — an attempted `"_comment"` key, say, since JSON has no comments — fails the import
*and* aborts startup (`Failed to import realms` … `Unrecognized field`, exit 1). Explanations go in
this file, not in the JSON. `setup.sh` now detects a Keycloak that exited and prints its last log
lines rather than burning the full timeout and carrying on with a broken IdP.

**`SAML2Client` instances are cached per siteKey.** `SAML2Util` keeps a `clients` map, and deleting
a connector's `.cfg` does not evict it — the endpoint keeps serving a working AuthnRequest form from
a config that no longer exists. Restart Jahia if you need a connector change to be truly clean.
This also means "is the valve up?" must be tested as *not* 404: once a connector works the endpoint
answers 200 (the HTTP-POST auto-submit form), not 400.

**Container naming.** The IdP is `slp-saml-idp-keycloak`, deliberately *not* `saml-idp-keycloak` —
that name belongs to the fixture in `jma-jahia-security-scan/docker/saml-idp.yml`. Port 8180 is
still shared, so don't run both at once.

## Teardown

```bash
docker compose down -v      # removes Keycloak and its realm; leaves Jahia untouched
```

The modules and the connector config stay in the Jahia container. To drop the connector:

```bash
docker exec jahia rm -f /var/jahia/karaf/etc/org.jahia.modules.auth-systemsite.cfg
```

## If you lock yourself out

Every host in `saml-login-provider`'s list must have a working SAML connector. Add one that does not
and unauthenticated users go to `/home.connect.saml` and get a 400 with no fallback — including you,
once your session ends. `HostList.isValid()` checks hostname syntax, not SAML readiness. The config
is a plain file, so no UI is needed to recover:

```bash
docker exec jahia sh -c 'echo "fqdn=" > /var/jahia/karaf/etc/org.jahia.community.samlloginprovider.cfg'
```

An empty list is the fail-safe "inactive everywhere" state.
