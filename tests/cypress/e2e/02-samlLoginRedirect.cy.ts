import { locationOf, requestUntil, setSamlLoginProviderHosts } from '../utils/samlLoginProvider'

describe('SAML Login redirect behavior', () => {
    // Requests issued by cy.request()/cy.visit() inside the docker network hit
    // Jahia with a Host header equal to JAHIA_HOST (default "jahia"), which is
    // what SamlLoginUrlProvider matches against request.getServerName().
    const configuredHost = 'jahia'
    const nonConfiguredHost = 'not-configured.example.com'
    const protectedPath = '/jahia/administration'
    const choiceLoginPath = '/saml-login'
    const samlConnectPath = '/home.connect.saml'

    afterEach(() => {
        // The configuration is server-wide: always leave a clean baseline
        // behind for other specs.
        setSamlLoginProviderHosts(null)
    })

    it('redirects an unauthenticated request straight to the SAML connect endpoint on a configured host', () => {
        setSamlLoginProviderHosts(configuredHost)
        cy.clearCookies()

        // The new host list reaches the provider asynchronously: poll for its effect rather than
        // racing it (see requestUntil).
        requestUntil({ url: protectedPath }, (r) => locationOf(r).includes(samlConnectPath)).then((response) => {
            expect(response.status, 'unauthenticated request should be redirected').to.be.oneOf([
                301, 302, 303, 307, 401,
            ])
            expect(locationOf(response)).to.include(samlConnectPath)
        })
    })

    it('sends an unauthenticated request to the choice login page on a non-configured host', () => {
        setSamlLoginProviderHosts(nonConfiguredHost)
        cy.clearCookies()

        requestUntil({ url: protectedPath }, (r) => locationOf(r).includes(choiceLoginPath)).then((response) => {
            expect(response.status, 'unauthenticated request should be redirected').to.be.oneOf([
                301, 302, 303, 307, 401,
            ])
            expect(locationOf(response)).to.include(choiceLoginPath)
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

    it('flags the credential fields as invalid and links them to the error message after a failed login attempt', () => {
        cy.clearCookies()
        cy.visit(choiceLoginPath)

        // No aria-invalid/aria-describedby before any attempt has failed
        cy.get('#username').should('not.have.attr', 'aria-invalid')
        cy.get('#password').should('not.have.attr', 'aria-invalid')

        cy.get('#username').type('not-a-real-user')
        cy.get('#password').type('wrong-password')
        cy.get('#loginForm').submit()

        cy.location('pathname').should('eq', choiceLoginPath)
        cy.get('#login-error').should('be.visible').and('have.attr', 'role', 'alert')

        cy.get('#username')
            .should('have.attr', 'aria-invalid', 'true')
            .and('have.attr', 'aria-describedby', 'login-error')
        cy.get('#password')
            .should('have.attr', 'aria-invalid', 'true')
            .and('have.attr', 'aria-describedby', 'login-error')
    })
})
