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
- Leave the list empty to turn the module off for that site (fail-safe default)
- Click **Save**

A host declared by several sites (Jahia allows it; stock `localhost` is the usual case) cannot be
listed, and keeps the standard login: Jahia would serve it from an arbitrary one of those sites.

Each site has its own list, and a list only ever applies to requests for its own site: a request
is matched to the site its host resolves to — the same lookup the SAML connect endpoint uses — and
only that site's list is consulted. The lists are stored as
`karaf/etc/org.jahia.community.samlloginprovider.site-<siteKey>.cfg`, one file per configured site.

### Host List Format

The `fqdn` field accepts a comma-separated list of hostnames. Each must be one of the site's own
names (server name or alias), and is otherwise validated as follows:

- **RFC 1123 labels**: Each dot-separated label is 1–63 characters, containing only letters, digits, and hyphens; hyphens cannot start or end a label
- **Single-label hosts** (e.g., `localhost`) and **IPv4 literals** (e.g., `192.168.1.1`) are accepted, for local/test setups and IP-based access
- **Trailing-dot notation** (e.g., `host.example.`) is rejected: the application's server-name resolution never produces this form, so it would silently never match
- **Maximum length**: 253 characters per hostname
- Whitespace is trimmed automatically; case is normalized to lowercase

### Critical Deployment Constraint

**Every host listed for a site must be served by a working SAML authentication valve (saml-authentication-valve module) connector for that site.** If you add a host that lacks one, users attempting to log in on that host will be redirected to `/home.connect.saml`, which will respond with HTTP 400 and provide no fallback login method — effectively locking out all users on that host. Verify SAML valve setup before adding hosts to this list.

## Behavior

Decided by the site the request's host resolves to:

| Host | Login | Logout |
|------|-------|--------|
| In its site's list | Straight redirect to `/home.connect.saml` (no login form) | Redirect to a public landing page instead of the referer, to avoid a silent SAML re-login |
| Another host of a site that has a list | `/saml-login`: a page identical to the standard login form, plus a "Sign in with Google" button | Standard behavior |
| Host of a site without a list, or of no site | Standard Jahia `/cms/login` page | Standard behavior |

## Permission

Access to a site's settings screen requires the `siteAdminSamlLoginProvider` permission **on that
site**, checked on the site node (declared under Jahia's `site-admin` permission — see
`src/main/import/permissions.xml`), so site administrators manage their own site. Because a site's
list only applies to requests resolving to that site and only accepts that site's own names,
holding the permission on one site gives no control over any other.

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
`siteAdminSamlLoginProvider` permission is checked on that site. `update` rejects any host that is
not the site's server name or one of its aliases.
