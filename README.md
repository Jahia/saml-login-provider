# Jahia SAML Login/Logout URL Provider

Redirects unauthenticated users straight to the SAML connect endpoint (`/home.connect.saml`)
instead of the Jahia login page on configured hosts, and points the logout button to a public
landing page so logout is not immediately cancelled by a silent SAML re-login. Configured **per
site**, from a React screen in each site's administration, backed by GraphQL.

# Installation

- In DX, go to **Administration → Server settings → System components → Modules**
- Upload the JAR **saml-login-provider-X.X.X.jar**
- Check that the module is started

# Use

## Administration

- Go to **Administration**, select the site, then **SAML auto-login**
  (`/jahia/administration/<siteKey>/samlLoginProvider`)
- Fill in the comma-separated list of **this site's** hosts that should go straight to SAML login.
  The screen shows the hosts the site answers on (its server name and aliases, set in the site's
  settings); only those are accepted
- Leave the list empty to send nobody straight to SAML: every host of the site then gets the
  choice login page (see [Behavior](#behavior))
- Click **Save**

A host declared by several sites (Jahia allows it; stock `localhost` is the usual case) cannot be
listed, and keeps the standard login: Jahia would serve it from an arbitrary one of those sites.

Each site has its own list, and a list only ever applies to requests for its own site: a request
is matched to the site its host resolves to — the same lookup the SAML connect endpoint uses — and
only that site's list is consulted. The lists are stored as
`karaf/etc/org.jahia.community.samlloginprovider.site-<siteKey>.cfg`, one file per configured site.
A saved list takes effect within a second; enabling the module on a site or changing a site's
server names takes up to about 5 seconds to be picked up.

### Hosts no site declares (editing host)

A host that **no** site declares — typically the editing host, e.g. `edit.example.com`, which Jahia
serves without it being any site's server name — falls back to **systemsite**: it gets the
modified login page as soon as the module is enabled on systemsite and systemsite has SAML set up,
and goes straight to SAML once it is in systemsite's list. Only systemsite's list may hold such
hosts.

The SAML valve finds a site from the request's host, which fails for such a host, so its SAML URLs
name systemsite explicitly (`/home.connect.saml?siteKey=systemsite`, and a hidden `siteKey` field in
the choice page's Google form). Systemsite's connector must name it too on the way back, and use
the editing host for its URLs:

```properties
# karaf/etc/org.jahia.modules.auth-systemsite.cfg (or systemsite's Authentication settings)
Saml.serverLocation = https://edit.example.com
Saml.incomingTargetUrl = /home.callback.saml?siteKey=systemsite
```

The IdP must accept that callback URL for systemsite's relying party.

### Host List Format

The `fqdn` field accepts a comma-separated list of hostnames. Each must be one of the site's own
names (server name or alias), and is otherwise validated as follows:

- **RFC 1123 labels**: Each dot-separated label is 1–63 characters, containing only letters, digits, and hyphens; hyphens cannot start or end a label
- **Single-label hosts** (e.g., `localhost`) and **IPv4 literals** (e.g., `192.168.1.1`) are accepted, for local/test setups and IP-based access
- **Trailing-dot notation** (e.g., `host.example.`) is rejected: the application's server-name resolution never produces this form, so it would silently never match
- **Maximum length**: 253 characters per hostname
- Whitespace is trimmed automatically; case is normalized to lowercase

### Prerequisites on the site

The module only acts on a site where it is **enabled** (Administration → the site → Modules) and
that has **SAML authentication set up**: an enabled saml-authentication-valve connector, configured
in the site's Authentication settings or as a `org.jahia.modules.auth-<siteKey>.cfg` file.
Anywhere else the standard Jahia login is untouched. This also means a listed host can never send
visitors to a SAML endpoint that cannot serve them: without a connector, nobody is redirected. The
settings screen warns when the site has no connector yet.

## Behavior

Decided by the site the request's host resolves to. On a site where the module is enabled and SAML
is set up:

| Host | Login | Logout |
|------|-------|--------|
| In the site's list | Straight redirect to `/home.connect.saml` (no login form) | Redirect to a public landing page instead of the referer, to avoid a silent SAML re-login |
| Any other host of the site — including every host when the list is empty | `/saml-login`: a page identical to the standard login form, plus a "Sign in with Google" button | Standard behavior |

After a SAML login the user returns to the page they first asked for, also with an IdP on another
site such as Google: the module sends the SAML valve's `redirect` cookie with `SameSite=None;
Secure`, so the browser keeps it across the IdP's cross-site POST back to Jahia. This needs HTTPS
as the browser sees it (directly, or through a proxy setting `X-Forwarded-Proto: https`); over plain
HTTP, a cross-site IdP returns users to the connector's `postLoginPath` instead. That one cookie
deliberately bypasses Tomcat's cookie processor, so a server-wide `sameSiteCookies` setting does not
apply to it.

A host that no site declares follows the same table with systemsite's settings (see
[Hosts no site declares](#hosts-no-site-declares-editing-host)).

Everywhere else — module not enabled on the site, no SAML connector on it, or a host declared by
several sites — the standard Jahia `/cms/login` page and logout are used.

## Permission

Access to a site's settings screen requires the `siteAdminSamlLoginProvider` permission **on that
site**, checked on the site node (declared under Jahia's `site-admin` permission — see
`src/main/import/permissions.xml`), so site administrators manage their own site. Because a site's
list only applies to requests resolving to that site and only accepts that site's own names,
holding the permission on one site gives no control over any other. The one exception is by
design: systemsite's list also governs hosts that no site declares.

## GraphQL API

The module exposes a GraphQL API via the `graphql-dxm-provider` extension point. Following this
project's convention for grouping a module's operations, both the query and the mutation are
nested under a single `samlLoginProvider` namespace field rather than added as flat root fields.

```graphql
# Retrieve a site's configuration, and the hosts it may list
query {
  samlLoginProvider {
    config(siteKey: "digitall") {
      fqdn
      serverNames
      samlConfigured
    }
  }
}

# Update a site's configuration (empty fqdn turns it off for that site)
mutation {
  samlLoginProvider {
    update(siteKey: "digitall", fqdn: "www.digitall.com") {
      fqdn
    }
  }
}
```

`siteKey` is required: it selects the site whose list is read or written, and the
`siteAdminSamlLoginProvider` permission is checked on that site. `samlConfigured` tells whether the
site has an enabled SAML connector (without one the module leaves the site's login alone). `update`
rejects any host that is not the site's server name or one of its aliases, and any host also
declared by another site; for systemsite it also accepts hosts that no site declares.
