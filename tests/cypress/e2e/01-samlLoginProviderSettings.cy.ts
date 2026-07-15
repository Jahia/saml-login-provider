import { DocumentNode } from 'graphql'

describe('SAML Login Provider Settings', () => {
    // Server-level route: no site prefix (target administration-server-configuration:10,
    // permission samlLoginProviderAdmin checked on the JCR root node).
    const adminPath = '/jahia/administration/samlLoginProvider'
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const updateSamlLoginProviderConfig: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/updateSamlLoginProviderConfig.graphql')
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSamlLoginProviderConfig: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSamlLoginProviderConfig.graphql')

    // The configuration is server-wide. Every test must (re)start from a known,
    // empty baseline and must not assume isolation from other tests or specs.
    function resetConfig() {
        return cy.apollo({
            mutation: updateSamlLoginProviderConfig,
            variables: { fqdn: null },
        })
    }

    beforeEach(() => {
        cy.login()
        resetConfig()
    })

    after(() => {
        cy.login()
        resetConfig()
    })

    it('loads the settings page with the fqdn field and action buttons', () => {
        cy.visit(adminPath)

        cy.get('#slp-fqdn input').should('be.visible')
        cy.get('#slp-fqdn input').should('have.value', '')

        cy.contains('button', 'Save').should('be.visible')
        cy.contains('button', 'Cancel').should('be.visible')
    })

    it('reads the current (empty) configuration via GraphQL', () => {
        cy.apollo({ query: getSamlLoginProviderConfig }).its('data.samlLoginProvider.config.fqdn').should('eq', '')
    })

    it('saves a valid comma-separated host list and shows a success message', () => {
        cy.visit(adminPath)

        cy.get('#slp-fqdn input').type('edit.jahia.com,editpp.jahia.com')
        cy.contains('button', 'Save').click()

        cy.contains('SAML auto-login configuration saved successfully.').should('be.visible')

        // Verify persistence via GraphQL
        cy.apollo({ query: getSamlLoginProviderConfig })
            .its('data.samlLoginProvider.config.fqdn')
            .should('eq', 'edit.jahia.com,editpp.jahia.com')
    })

    it('shows a validation error for an invalid host and does not save', () => {
        cy.visit(adminPath)

        // Embedded space inside a host is invalid on both the client regex and
        // the server-side HostList validation.
        cy.get('#slp-fqdn input').type('edit jahia.com')
        cy.contains('button', 'Save').click()

        cy.get('#slp-fqdn-error').should('be.visible').and('contain', 'Enter a comma-separated list of valid hostnames')

        // No success alert, and the configuration must remain untouched
        cy.contains('SAML auto-login configuration saved successfully.').should('not.exist')
        cy.apollo({ query: getSamlLoginProviderConfig }).its('data.samlLoginProvider.config.fqdn').should('eq', '')
    })

    it('rejects an invalid host list at the GraphQL mutation level too', () => {
        cy.apollo({
            mutation: updateSamlLoginProviderConfig,
            variables: { fqdn: 'bad host.com' },
        }).then((response: { message?: string; graphQLErrors?: unknown[]; networkError?: unknown }) => {
            const hasError = Boolean(
                (response.graphQLErrors && response.graphQLErrors.length > 0) ||
                    response.networkError ||
                    response.message,
            )
            expect(hasError, 'mutation should return an error for an invalid host list').to.eq(true)
        })

        // The invalid value must never be persisted
        cy.apollo({ query: getSamlLoginProviderConfig }).its('data.samlLoginProvider.config.fqdn').should('eq', '')
    })

    it('cancels edits and reverts the form to the last loaded value', () => {
        // Pre-load a saved value via the API
        cy.apollo({
            mutation: updateSamlLoginProviderConfig,
            variables: { fqdn: 'original.jahia.com' },
        })

        cy.visit(adminPath)
        cy.get('#slp-fqdn input').should('have.value', 'original.jahia.com')

        // Modify without saving
        cy.get('#slp-fqdn input').clear()
        cy.get('#slp-fqdn input').type('changed.jahia.com')
        cy.get('#slp-fqdn input').should('have.value', 'changed.jahia.com')

        cy.contains('button', 'Cancel').click()

        // Value should revert to the last loaded one
        cy.get('#slp-fqdn input').should('have.value', 'original.jahia.com')

        // A visible cancel acknowledgment must appear (a11y fix: previously only
        // announced via the aria-live region, not shown to sighted users)
        cy.contains('Changes discarded.').should('be.visible')

        // And nothing was persisted from the cancelled edit
        cy.apollo({ query: getSamlLoginProviderConfig })
            .its('data.samlLoginProvider.config.fqdn')
            .should('eq', 'original.jahia.com')
    })

    it('reloads the saved value when navigating back to the settings page', () => {
        cy.apollo({
            mutation: updateSamlLoginProviderConfig,
            variables: { fqdn: 'persist.jahia.com' },
        })

        cy.visit(adminPath)
        cy.get('#slp-fqdn input').should('have.value', 'persist.jahia.com')

        cy.visit('/jahia/administration')
        cy.visit(adminPath)

        cy.get('#slp-fqdn input').should('have.value', 'persist.jahia.com')
    })

    it('clears the list back to empty, disabling the module', () => {
        cy.apollo({
            mutation: updateSamlLoginProviderConfig,
            variables: { fqdn: 'todelete.jahia.com' },
        })

        cy.visit(adminPath)
        cy.get('#slp-fqdn input').should('have.value', 'todelete.jahia.com')

        cy.get('#slp-fqdn input').clear()
        cy.contains('button', 'Save').click()

        cy.contains('SAML auto-login configuration saved successfully.').should('be.visible')

        cy.apollo({ query: getSamlLoginProviderConfig }).its('data.samlLoginProvider.config.fqdn').should('eq', '')
    })
})
