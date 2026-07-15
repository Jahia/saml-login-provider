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

- Go to **Administration → `<any site>` → SAML auto-login**
- Fill in the comma-separated list of hosts (FQDNs) the automatic SAML login applies to
- Leave the list empty to disable the module entirely (fail-safe default)
- Click **Save**

This setting is **server-wide** — it is shared by all sites, even though the admin screen is
reached from a specific site.

## Behavior

| Host | Login | Logout |
|------|-------|--------|
| In the configured list | Straight redirect to `/home.connect.saml` (no login form) | Redirect to a public landing page instead of the referer, to avoid a silent SAML re-login |
| Not in the list | `/saml-login`: a page identical to the standard login form, plus a "Sign in with Google" button | Standard behavior |
| List empty (not configured) | Standard Jahia `/cms/login` page everywhere | Standard behavior |

## Permission

Access to the settings screen requires the `siteAdminSamlLoginProvider` permission on the site node.
Assign this permission via **Administration → `<your-site>` → Roles & permissions**.

## GraphQL API

The module exposes a GraphQL API via the `graphql-dxm-provider` extension point.

```graphql
# Retrieve the current configuration
query {
  samlLoginProviderConfig(siteKey: "mySite") {
    fqdn
  }
}

# Update the configuration (server-wide; empty fqdn disables the redirect)
mutation {
  updateSamlLoginProviderConfig(siteKey: "mySite", fqdn: "edit.jahia.com,editpp.jahia.com") {
    fqdn
  }
}
```

`siteKey` is only used to resolve the site-admin permission check — the stored configuration
itself is server-wide.
