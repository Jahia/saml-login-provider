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
| Jahia SP entity ID | `jahia-digitall` |
| ACS | `http://localhost:8080/home.callback.saml` |
| Connector | OSGi factory config `org.jahia.modules.auth-<site>.cfg` |

### The one manual step: the user mapper

`SAML2Filter.handleCallback()` hands the SAML profile to whichever mappers the connector has
configured (`config.getMappers()`). With none configured the assertion is accepted, the callback
redirects normally, and **no Jahia session is created** — a silent no-op rather than an error.

Wire it once in the UI:

> Administration → *(site)* → Authentication → SAML → **JCR Auth provider** →
> map the connector's `username` field to **Login username** (`ssoLoginId`, the only mandatory
> one) → Save

### Then exercise saml-login-provider itself

> `/jahia/administration/samlLoginProvider` → host list = `localhost`

Log out, request a protected page, and you should be carried to Keycloak and back.

## Things that are easy to get wrong

**Module versions are not free.** `saml-authentication-valve 3.1.1` imports
`org.jahia.modules.jahiaauth.service;version="[1.7,2)"`. `jahia-authentication 2.0.0` exports
`2.0.0` and is therefore **outside that range** — install it and the valve never resolves, with no
visible error beyond `/home.connect.saml` continuing to answer 400. `Jahia-Required-Version` is
about the platform and says nothing about this. The resolvable set, pinned in `setup.sh`:

| Module | Version | Why |
|---|---|---|
| `jahia-authentication` | 1.8.1 | exports `1.8.1`, inside the valve's range |
| `jcr-auth-provider` | 3.4.1 | imports `[1.0,2)` |
| `saml-authentication-valve` | 3.1.1 | imports `[1.7,2)` |

**The connector must live on the site your browsing host resolves to.**
`SAML2Util.findSiteKeyForRequest()` uses `getSiteByServerName(request.getServerName())`, and
`saml-login-provider` redirects with no `siteKey` parameter. On a stock stack `localhost` resolves
to **`systemsite`**, not `digitall` — binding `digitall` gives you a connector nothing reaches
(`No SAML2 settings found for siteKey: systemsite`). Hence `SITE_KEY=systemsite` by default. To use
`digitall`, first give it the server name you browse with (Administration → Sites → digitall → Web
project settings → Server name), then set `SITE_KEY=digitall`.

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
