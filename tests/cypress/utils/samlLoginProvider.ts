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
}

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
