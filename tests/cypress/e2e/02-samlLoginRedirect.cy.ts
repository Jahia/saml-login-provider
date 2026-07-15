import { DocumentNode } from 'graphql'

describe('SAML Login redirect behavior', () => {
    // Requests issued by cy.request()/cy.visit() inside the docker network hit
    // Jahia with a Host header equal to JAHIA_HOST (default "jahia"), which is
    // what SamlLoginUrlProvider matches against request.getServerName().
    const configuredHost = 'jahia'
    const nonConfiguredHost = 'not-configured.example.com'
    const protectedPath = '/jahia/administration'
    const choiceLoginPath = '/saml-login'
    const samlConnectPath = '/home.connect.saml'

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const updateSamlLoginProviderConfig: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/updateSamlLoginProviderConfig.graphql')

    function setFqdn(fqdn: string | null) {
        cy.login()
        cy.apollo({ mutation: updateSamlLoginProviderConfig, variables: { fqdn } })
    }

    afterEach(() => {
        // The configuration is server-wide: always leave a clean baseline
        // behind for other specs.
        setFqdn(null)
    })

    it('redirects an unauthenticated request straight to the SAML connect endpoint on a configured host', () => {
        setFqdn(configuredHost)
        cy.clearCookies()

        cy.request({ url: protectedPath, followRedirect: false, failOnStatusCode: false }).then((response) => {
            expect(response.status, 'unauthenticated request should be redirected').to.be.oneOf([
                301, 302, 303, 307, 401,
            ])
            const location = (response.redirectedToUrl || response.headers.location || '') as string
            expect(location).to.include(samlConnectPath)
        })
    })

    it('sends an unauthenticated request to the choice login page on a non-configured host', () => {
        setFqdn(nonConfiguredHost)
        cy.clearCookies()

        cy.request({ url: protectedPath, followRedirect: false, failOnStatusCode: false }).then((response) => {
            expect(response.status, 'unauthenticated request should be redirected').to.be.oneOf([
                301, 302, 303, 307, 401,
            ])
            const location = (response.redirectedToUrl || response.headers.location || '') as string
            expect(location).to.include(choiceLoginPath)
        })
    })

    it('serves /saml-login with both a credentials form and a "Sign in with Google" button', () => {
        cy.clearCookies()
        cy.visit(choiceLoginPath)

        cy.get('#loginForm').should('exist')
        cy.get('#username').should('be.visible')
        cy.get('#password').should('be.visible')

        cy.get('#googleLoginForm').should('exist').and('have.attr', 'action').and('include', samlConnectPath)
        cy.contains('button', 'Sign in with Google').should('be.visible')
    })
})
