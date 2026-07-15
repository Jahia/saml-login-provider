# Jahia SAML Login/Logout URL Provider

Redirects unauthenticated users straight to the SAML connect endpoint (`/home.connect.saml`)
instead of the Jahia login page on configured hosts, and points the logout button to a public
landing page so logout is not immediately cancelled by a silent SAML re-login. Ships a React
administration UI (Server Administration) backed by GraphQL.

# Installation

- In DX, go to **Administration → Server settings → System components → Modules**
- Upload the JAR **saml-login-provider-X.X.X.jar**
- Check that the module is started

# Use

## Administration

- Go to **Administration → Server settings → Configuration → SAML auto-login**
- Fill in the comma-separated list of hosts (FQDNs) the automatic SAML login applies to
- Leave the list empty to disable the module entirely (fail-safe default)
- Click **Save**

This setting is **server-wide** — it is shared by all sites, and the admin screen itself lives
under Server settings (not under any particular site) to reflect that.

### Host List Format

The `fqdn` field accepts a comma-separated list of hostnames. Each hostname is validated as follows:

- **RFC 1123 labels**: Each dot-separated label is 1–63 characters, containing only letters, digits, and hyphens; hyphens cannot start or end a label
- **Single-label hosts** (e.g., `localhost`) and **IPv4 literals** (e.g., `192.168.1.1`) are accepted, for local/test setups and IP-based access
- **Trailing-dot notation** (e.g., `host.example.`) is rejected: the application's server-name resolution never produces this form, so it would silently never match
- **Maximum length**: 253 characters per hostname
- Whitespace is trimmed automatically; case is normalized to lowercase

### Critical Deployment Constraint

**Every host listed in the `fqdn` field must have the SAML authentication valve (saml-authentication-valve module) properly configured and enabled.** If you add a host that lacks SAML valve configuration, users attempting to log in on that host will be redirected to `/home.connect.saml`, which will respond with HTTP 400 and provide no fallback login method — effectively locking out all users on that host. Verify SAML valve setup before adding hosts to this list.

## Behavior

| Host | Login | Logout |
|------|-------|--------|
| In the configured list | Straight redirect to `/home.connect.saml` (no login form) | Redirect to a public landing page instead of the referer, to avoid a silent SAML re-login |
| Not in the list | `/saml-login`: a page identical to the standard login form, plus a "Sign in with Google" button | Standard behavior |
| List empty (not configured) | Standard Jahia `/cms/login` page everywhere | Standard behavior |

## Permission

Access to the settings screen requires the `samlLoginProviderAdmin` permission, a genuine
server-level permission checked on the JCR root node (declared as a child of Jahia's built-in
`admin` "server settings" permission — see `src/main/import/permissions.xml`). It is **not** a
per-site permission: this configuration affects every site and host on the platform, so holding
a per-site admin role must never be enough to change it. Assign this permission via
**Administration → Server settings → Users and roles → Roles & permissions** (server-level
roles).

## GraphQL API

The module exposes a GraphQL API via the `graphql-dxm-provider` extension point. Following this
project's convention for grouping a module's operations, both the query and the mutation are
nested under a single `samlLoginProvider` namespace field rather than added as flat root fields.

```graphql
# Retrieve the current configuration
query {
  samlLoginProvider {
    config {
      fqdn
    }
  }
}

# Update the configuration (server-wide; empty fqdn disables the redirect)
mutation {
  samlLoginProvider {
    update(fqdn: "edit.jahia.com,editpp.jahia.com") {
      fqdn
    }
  }
}
```

Both `config` and `update` accept an optional `siteKey` argument, kept for caller-context/audit
purposes only (e.g. which admin site tab a request originated from). It does **not** scope
storage and has **no effect on authorization** — access is always gated by the genuine
server-level `samlLoginProviderAdmin` permission, regardless of the value (or absence) of
`siteKey`.
