/// <reference types="cypress" />

// Helpers shared by the specs that change a site's saml-login-provider host list and then
// observe its effect over HTTP.

const UPDATE_HOSTS_MUTATION =
    'mutation UpdateHosts($siteKey: String!, $fqdn: String) ' +
    '{ samlLoginProvider { update(siteKey: $siteKey, fqdn: $fqdn) { fqdn } } }'

/**
 * The site the specs configure and the names it answers on. Both are network aliases of the
 * jahia service in docker-compose.yml, so the cypress container resolves them; the host list only
 * accepts a site's own names, and the choice-page rule needs a site with two.
 */
export const DIGITALL = {
    siteKey: 'digitall',
    host: 'digitall.jahia.test',
    alias: 'www.digitall.jahia.test',
    // Must match the clientId in saml-idp/realm/jahia-saml-realm.json.
    spEntityId: 'jahia-digitall',
}

/**
 * A host that NO site declares, standing for a production editing host (edit.<domain>): its login
 * falls back to systemsite's settings. A network alias of the jahia service in docker-compose.yml.
 */
export const EDIT_HOST = {
    siteKey: 'systemsite',
    host: 'edit.jahia.test',
    // Must match the clientId in saml-idp/realm/jahia-saml-realm.json.
    spEntityId: 'jahia-systemsite',
}

/** The `idp` service of docker-compose.yml, as the cypress container and Jahia both reach it. */
export const IDP = {
    url: 'http://idp.jahia.test:8180',
    realm: 'jahia-saml',
    metadataUrl: 'http://idp.jahia.test:8180/realms/jahia-saml/protocol/saml/descriptor',
}

/**
 * Where a SAML login returns before its target: the login completes there, outside core's Shiro
 * guard (see SamlLoginReturnFilter).
 */
export const RETURN_PATH = '/saml-login-return'

/** The login hop returning to target, as the module puts it in a SAML redirect parameter. */
export function returnHop(target: string): string {
    return `${RETURN_PATH}?redirect=${encodeURIComponent(target)}`
}

const MODULE_ID = 'saml-login-provider'
const SAML_CONNECT_PATH = '/home.connect.saml'
const IDP_STARTUP_POLL = { attempts: 40, intervalMs: 3000 }
const CONNECTOR_POLL = { attempts: 30, intervalMs: 1000 }

const DEFAULT_POLL_ATTEMPTS = 20
const DEFAULT_POLL_INTERVAL_MS = 500

export interface PollOptions {
    attempts?: number
    intervalMs?: number
}

/** The redirect target of a non-followed response, or '' when there is none. */
export function locationOf(response: Cypress.Response<unknown>): string {
    return (response.redirectedToUrl || (response.headers.location as string) || '') as string
}

/**
 * Set the host list as root and fail loudly if Jahia refuses.
 *
 * Deliberately cy.request rather than cy.apollo: a spec that visits another origin (spec 03 goes
 * to digitall.jahia.test) is reloaded by Cypress at that origin, after which cy.apollo's fetch to
 * the Jahia baseUrl is cross-origin. Jahia's API security filter then answers "Permission denied"
 * in a 200 GraphQL body, which cy.apollo does not treat as a failure -- the setting is silently
 * left unchanged. cy.request is origin-independent, and the same-host Origin header is what the
 * security filter requires before it lets a session cookie reach GraphQL at all.
 */
export function setSamlLoginProviderHosts(siteKey: string, fqdn: string | null): void {
    const baseUrl = Cypress.config('baseUrl')
    cy.login()
    cy.request({
        method: 'POST',
        url: `${baseUrl}/modules/graphql`,
        headers: { Origin: baseUrl },
        body: { query: UPDATE_HOSTS_MUTATION, variables: { siteKey, fqdn } },
    }).then((response) => {
        expect(response.body.errors, `errors setting the host list of ${siteKey} to ${fqdn}`).to.be.undefined
        expect(response.body.data.samlLoginProvider.update.fqdn, 'host list').to.eq(fqdn ?? '')
    })
    cy.logout()
}

/**
 * Give digitall its own server name and an alias. Digitall ships with the same j:serverName as
 * systemsite (localhost), so until then no host resolves to it -- see configureSiteHost.groovy.
 * Idempotent.
 */
export function configureDigitallHostNames(): void {
    cy.executeGroovy('groovy/configureSiteHost.groovy', { '@@HOST@@': DIGITALL.host, '@@SITE@@': DIGITALL.siteKey })
    cy.executeGroovy('groovy/addSiteAlias.groovy', { '@@ALIAS@@': DIGITALL.alias, '@@SITE@@': DIGITALL.siteKey })
}

/**
 * Re-issue a request until isReady accepts the response, then yield that response -- or yield the
 * last one once the attempts run out, so the caller's own assertions report what was really seen.
 *
 * For state the server applies asynchronously. Notably the host list: the mutation returns as soon
 * as the configuration is written, but SamlLoginUrlProvider receives it from ConfigurationAdmin
 * a fraction of a second later, and a request in between is still routed by the old list.
 */
export function requestUntil(
    request: Partial<Cypress.RequestOptions>,
    isReady: (response: Cypress.Response<unknown>) => boolean,
    { attempts = DEFAULT_POLL_ATTEMPTS, intervalMs = DEFAULT_POLL_INTERVAL_MS }: PollOptions = {},
): Cypress.Chainable<Cypress.Response<unknown>> {
    return cy.request({ failOnStatusCode: false, followRedirect: false, log: false, ...request }).then((response) => {
        if (isReady(response) || attempts <= 1) {
            return cy.wrap(response, { log: false })
        }

        // A poll interval against state with no event to wait on, not a guess at timing.
        cy.wait(intervalMs, { log: false })
        return requestUntil(request, isReady, { attempts: attempts - 1, intervalMs })
    })
}

/**
 * Enable or disable this module on a site. Enabling goes through the provisioning API; it has no
 * "disable" operation, so disabling runs the template manager from a Groovy fixture. Both are
 * origin-independent.
 */
export function setSamlLoginProviderEnabled(siteKey: string, enabled: boolean): void {
    if (enabled) {
        cy.runProvisioningScript({
            script: { fileContent: `- enable: "${MODULE_ID}"\n  site: "${siteKey}"`, type: 'application/yaml' },
        })
    } else {
        cy.executeGroovy('groovy/disableModuleOnSite.groovy', { '@@MODULE@@': MODULE_ID, '@@SITE@@': siteKey })
    }
}

/**
 * Make digitall a site the module acts on: its own host names, the module enabled on it, and a
 * working SAML connector bound to the CI IdP. The module only acts where both hold. Idempotent:
 * configureSamlConnector.groovy keeps an equivalent connector, keystore included.
 */
export function setUpDigitallForSaml(): void {
    configureDigitallHostNames()
    setSamlLoginProviderEnabled(DIGITALL.siteKey, true)
    configureSamlConnector(DIGITALL.siteKey, DIGITALL.spEntityId, `http://${DIGITALL.host}:8080`, '')
}

/**
 * Make systemsite govern EDIT_HOST, the way a production editing host is set up: the SAML modules
 * and this one enabled on systemsite, and a connector whose URLs are on that host. Since the valve
 * cannot find a site from such a host, the connector's callback names systemsite -- the same
 * setting production needs. Idempotent.
 */
export function setUpSystemsiteForSaml(): void {
    cy.runProvisioningScript({
        script: {
            fileContent: ['jahia-authentication', 'jcr-auth-provider', 'saml-authentication-valve', MODULE_ID]
                .map((module) => `- enable: "${module}"\n  site: "${EDIT_HOST.siteKey}"`)
                .join('\n'),
            type: 'application/yaml',
        },
    })
    configureSamlConnector(
        EDIT_HOST.siteKey,
        EDIT_HOST.spEntityId,
        `http://${EDIT_HOST.host}:8080`,
        `?siteKey=${EDIT_HOST.siteKey}`,
    )
}

/**
 * Write a site's SAML connector, bound to the CI IdP, and wait until the valve serves it.
 *
 * @param siteUrl where the browser reaches the site; the connector's ACS URL is built on it
 * @param siteQuery appended to the valve's endpoints: empty when the host is one of the site's
 *        names (the valve finds the site from it), else `?siteKey=<site>`
 */
function configureSamlConnector(siteKey: string, spEntityId: string, siteUrl: string, siteQuery: string): void {
    requestUntil({ url: IDP.metadataUrl }, (r) => r.status === 200, IDP_STARTUP_POLL)
        .its('status')
        .should('eq', 200)
    cy.executeGroovy('groovy/configureSamlConnector.groovy', {
        '@@SITE@@': siteKey,
        '@@SP_ENTITY_ID@@': spEntityId,
        '@@SP_BASE_URL@@': siteUrl,
        // Jahia fetches this itself, over the stack network.
        '@@IDP_METADATA_URL@@': IDP.metadataUrl,
        '@@POST_LOGIN_PATH@@': '/home.html',
        '@@INCOMING_TARGET_URL@@': `/home.callback.saml${siteQuery}`,
    })

    // Felix FileInstall picks the connector .cfg up asynchronously. Until it has, the valve answers
    // 400 (no connector for this site); once it has, it serves the HTTP-POST auto-submit form aimed
    // at the IdP.
    const connectorServes = (r: Cypress.Response<unknown>) =>
        r.status === 200 && typeof r.body === 'string' && r.body.includes('idp.jahia.test')
    requestUntil({ url: `${siteUrl}${SAML_CONNECT_PATH}${siteQuery}` }, connectorServes, CONNECTOR_POLL).then((r) => {
        expect(connectorServes(r), `${SAML_CONNECT_PATH} serves the AuthnRequest form`).to.be.true
    })
}
