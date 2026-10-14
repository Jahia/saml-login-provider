import { DocumentNode } from 'graphql'
import {
    configureDigitallHostNames,
    DIGITALL,
    requestUntil,
    setSamlLoginProviderEnabled,
} from '../utils/samlLoginProvider'

describe('SAML Login Provider Settings', () => {
    // Site-level route: /administration/:site/<route key> (target administration-sites,
    // permission siteAdminSamlLoginProvider checked on the site node).
    const siteKey = DIGITALL.siteKey
    const adminPath = `/jahia/administration/${siteKey}/samlLoginProvider`
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const updateSamlLoginProviderConfig: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/updateSamlLoginProviderConfig.graphql')
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSamlLoginProviderConfig: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSamlLoginProviderConfig.graphql')

    // Every test must (re)start from a known, empty baseline for the site and must not assume
    // isolation from other tests or specs.
    function resetConfig() {
        return cy.apollo({
            mutation: updateSamlLoginProviderConfig,
            // An omitted buttonLabel keeps the current one: reset it explicitly
            variables: { siteKey, fqdn: null, buttonLabel: '' },
        })
    }

    function readButtonLabel() {
        return cy
            .apollo({ query: getSamlLoginProviderConfig, variables: { siteKey } })
            .its('data.samlLoginProvider.config.buttonLabel')
    }

    /** The sign-in button text on digitall's login page, once the saved value is in effect. */
    function choicePageButtonShows(text: string) {
        const button = (r: Cypress.Response<unknown>) =>
            new DOMParser()
                .parseFromString(r.body as string, 'text/html')
                .querySelector('#googleLoginForm button')
                ?.textContent?.trim() ?? ''
        requestUntil({ url: `http://${DIGITALL.host}:8080/saml-login` }, (r) => button(r) === text).then((r) => {
            expect(button(r), 'sign-in button on the login page').to.eq(text)
        })
    }

    // The administration app and the config query can take well over Cypress's 4s default on a
    // cold Jahia: wait for the form itself once, rather than letting the next assertion race it.
    const PAGE_LOAD_TIMEOUT_MS = 20000

    function openSettings() {
        cy.visit(adminPath)
        cy.get('#slp-fqdn input', { timeout: PAGE_LOAD_TIMEOUT_MS }).should('be.visible')
    }

    function readFqdn() {
        return cy.apollo({ query: getSamlLoginProviderConfig, variables: { siteKey } }).its('data.samlLoginProvider.config.fqdn')
    }

    before(() => {
        // The list only accepts the site's own names: give digitall two of them. The settings screen
        // only shows on sites where the module is enabled.
        configureDigitallHostNames()
        setSamlLoginProviderEnabled(siteKey, true)
    })

    beforeEach(() => {
        cy.login()
        resetConfig()
    })

    after(() => {
        cy.login()
        resetConfig()
    })

    it('loads the settings page with the fqdn field and action buttons', () => {
        openSettings()

        cy.get('#slp-fqdn input').should('be.visible')
        cy.get('#slp-fqdn input').should('have.value', '')

        cy.contains('button', 'Save').should('be.visible')
        cy.contains('button', 'Cancel').should('be.visible')
    })

    it('reads the current (empty) configuration via GraphQL', () => {
        readFqdn().should('eq', '')
    })

    it('saves a valid comma-separated host list and shows a success message', () => {
        openSettings()

        cy.get('#slp-fqdn input').type(`${DIGITALL.host},${DIGITALL.alias}`)
        cy.contains('button', 'Save').click()

        cy.contains('SAML auto-login configuration saved successfully.').should('be.visible')

        // Verify persistence via GraphQL
        readFqdn()
            .should('eq', `${DIGITALL.host},${DIGITALL.alias}`)
    })

    it('shows a validation error for an invalid host and does not save', () => {
        openSettings()

        // Embedded space inside a host is invalid on both the client regex and
        // the server-side HostList validation.
        cy.get('#slp-fqdn input').type('edit jahia.com')
        cy.contains('button', 'Save').click()

        cy.get('#slp-fqdn-error').should('be.visible').and('contain', 'Enter a comma-separated list of valid hostnames')

        // No success alert, and the configuration must remain untouched
        cy.contains('SAML auto-login configuration saved successfully.').should('not.exist')
        readFqdn().should('eq', '')
    })

    it('rejects an invalid host list at the GraphQL mutation level too', () => {
        cy.apollo({
            mutation: updateSamlLoginProviderConfig,
            variables: { siteKey, fqdn: 'bad host.com' },
        }).then((response: { message?: string; graphQLErrors?: unknown[]; networkError?: unknown }) => {
            const hasError = Boolean(
                (response.graphQLErrors && response.graphQLErrors.length > 0) ||
                    response.networkError ||
                    response.message,
            )
            expect(hasError, 'mutation should return an error for an invalid host list').to.eq(true)
        })

        // The invalid value must never be persisted
        readFqdn().should('eq', '')
    })

    it('cancels edits and reverts the form to the last loaded value', () => {
        // Pre-load a saved value via the API
        cy.apollo({
            mutation: updateSamlLoginProviderConfig,
            variables: { siteKey, fqdn: DIGITALL.host },
        })

        openSettings()
        cy.get('#slp-fqdn input').should('have.value', DIGITALL.host)

        // Modify without saving
        cy.get('#slp-fqdn input').clear()
        cy.get('#slp-fqdn input').type(DIGITALL.alias)
        cy.get('#slp-fqdn input').should('have.value', DIGITALL.alias)

        cy.contains('button', 'Cancel').click()

        // Value should revert to the last loaded one
        cy.get('#slp-fqdn input').should('have.value', DIGITALL.host)

        // A visible cancel acknowledgment must appear (a11y fix: previously only
        // announced via the aria-live region, not shown to sighted users)
        cy.contains('Changes discarded.').should('be.visible')

        // And nothing was persisted from the cancelled edit
        readFqdn()
            .should('eq', DIGITALL.host)
    })

    it('reloads the saved value when navigating back to the settings page', () => {
        cy.apollo({
            mutation: updateSamlLoginProviderConfig,
            variables: { siteKey, fqdn: DIGITALL.alias },
        })

        openSettings()
        cy.get('#slp-fqdn input').should('have.value', DIGITALL.alias)

        cy.visit('/jahia/administration')
        openSettings()

        cy.get('#slp-fqdn input').should('have.value', DIGITALL.alias)
    })

    it('clears the list back to empty, disabling the module', () => {
        cy.apollo({
            mutation: updateSamlLoginProviderConfig,
            variables: { siteKey, fqdn: DIGITALL.host },
        })

        openSettings()
        cy.get('#slp-fqdn input').should('have.value', DIGITALL.host)

        cy.get('#slp-fqdn input').clear()
        cy.contains('button', 'Save').click()

        cy.contains('SAML auto-login configuration saved successfully.').should('be.visible')

        readFqdn().should('eq', '')
    })

    it('scopes the page to the site and lists the hosts it may contain', () => {
        openSettings()

        cy.contains(`This setting applies to the site ${siteKey} only.`).should('be.visible')
        cy.get('#slp-fqdn-hint').should('contain', DIGITALL.host).and('contain', DIGITALL.alias)
        readFqdn().should('eq', '')
    })

    it('refuses a host that is not one of the site\'s own names, in the form and in the API', () => {
        openSettings()

        cy.get('#slp-fqdn input').type('other-site.example.com')
        cy.contains('button', 'Save').click()
        cy.get('#slp-fqdn-error').should('be.visible').and('contain', "Each host must be one of this site's hosts")
        cy.contains('SAML auto-login configuration saved successfully.').should('not.exist')

        // The server enforces it on its own, whatever the client does
        cy.apollo({
            mutation: updateSamlLoginProviderConfig,
            variables: { siteKey, fqdn: 'other-site.example.com' },
        }).then((response: { message?: string; graphQLErrors?: unknown[]; networkError?: unknown }) => {
            const hasError = Boolean(
                (response.graphQLErrors && response.graphQLErrors.length > 0) || response.networkError || response.message,
            )
            expect(hasError, 'mutation should refuse a host of another site').to.eq(true)
        })
        readFqdn().should('eq', '')
    })

    it('saves a custom sign-in button text and shows it on the site\'s login page', () => {
        openSettings()
        cy.get('#slp-button-label input').should('have.value', '').and('have.attr', 'placeholder', 'Sign in with Google')

        cy.get('#slp-button-label input').type('  Sign in with Acme SSO ')
        cy.contains('button', 'Save').click()

        cy.contains('saved successfully').should('be.visible')
        readButtonLabel().should('eq', 'Sign in with Acme SSO')
        choicePageButtonShows('Sign in with Acme SSO')
    })

    it('restores the default text when the field is cleared', () => {
        cy.apollo({ mutation: updateSamlLoginProviderConfig, variables: { siteKey, fqdn: null, buttonLabel: 'Temporary' } })
        openSettings()
        cy.get('#slp-button-label input').should('have.value', 'Temporary').clear()
        cy.contains('button', 'Save').click()

        cy.contains('saved successfully').should('be.visible')
        readButtonLabel().should('eq', '')
        choicePageButtonShows('Sign in with Google')
    })

    it('keeps the button text when a caller only updates the host list', () => {
        cy.apollo({ mutation: updateSamlLoginProviderConfig, variables: { siteKey, fqdn: null, buttonLabel: 'Kept' } })

        cy.apollo({ mutation: updateSamlLoginProviderConfig, variables: { siteKey, fqdn: DIGITALL.host } })

        readButtonLabel().should('eq', 'Kept')
    })

    it('rejects a button text that is too long at the GraphQL mutation level', () => {
        cy.apollo({
            mutation: updateSamlLoginProviderConfig,
            variables: { siteKey, fqdn: null, buttonLabel: 'x'.repeat(81) },
        }).then((response: { message?: string; graphQLErrors?: unknown[]; networkError?: unknown }) => {
            const hasError = Boolean(
                (response.graphQLErrors && response.graphQLErrors.length > 0) ||
                    response.networkError ||
                    response.message,
            )
            expect(hasError, 'mutation should return an error for a too long button text').to.eq(true)
        })
        readButtonLabel().should('eq', '')
    })
})
