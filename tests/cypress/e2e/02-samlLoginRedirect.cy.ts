import {
    DIGITALL,
    locationOf,
    requestUntil,
    setSamlLoginProviderEnabled,
    setSamlLoginProviderHosts,
    setUpDigitallForSaml,
} from '../utils/samlLoginProvider'

describe('SAML Login redirect behavior', () => {
    // A request is routed by the site its host resolves to, and the module only acts on a site where
    // it is enabled and that has a SAML connector. digitall gets both, and answers on DIGITALL.host
    // and DIGITALL.alias (network aliases of the jahia service); the baseUrl host "jahia" resolves
    // to no site at all.
    const urlOn = (host: string) => `http://${host}:8080`
    const protectedPath = '/jahia/administration'
    const choiceLoginPath = '/saml-login'
    const samlConnectPath = '/home.connect.saml'

    before(() => {
        setUpDigitallForSaml()
    })

    beforeEach(() => {
        // Only DIGITALL.host is listed; the alias is another name of the same, configured site.
        setSamlLoginProviderHosts(DIGITALL.siteKey, DIGITALL.host)
        cy.clearCookies()
    })

    afterEach(() => {
        // Leave the site as other specs expect it: module enabled, no host list.
        setSamlLoginProviderEnabled(DIGITALL.siteKey, true)
        setSamlLoginProviderHosts(DIGITALL.siteKey, null)
    })

    it('redirects an unauthenticated request straight to the SAML connect endpoint on a listed host', () => {
        // The new host list reaches the provider asynchronously: poll for its effect rather than
        // racing it (see requestUntil).
        const url = `${urlOn(DIGITALL.host)}${protectedPath}`
        requestUntil({ url }, (r) => locationOf(r).includes(samlConnectPath)).then((response) => {
            expect(response.status, 'unauthenticated request should be redirected').to.be.oneOf([
                301, 302, 303, 307, 401,
            ])
            expect(locationOf(response)).to.include(samlConnectPath)
        })
    })

    it('sends an unauthenticated request to the choice login page on an unlisted host of the same site', () => {
        const url = `${urlOn(DIGITALL.alias)}${protectedPath}`
        requestUntil({ url }, (r) => locationOf(r).includes(choiceLoginPath)).then((response) => {
            expect(response.status, 'unauthenticated request should be redirected').to.be.oneOf([
                301, 302, 303, 307, 401,
            ])
            expect(locationOf(response)).to.include(choiceLoginPath)
        })
    })

    it('shows the modified login page on every host of a site that has SAML set up but no host list', () => {
        setSamlLoginProviderHosts(DIGITALL.siteKey, null)

        for (const host of [DIGITALL.host, DIGITALL.alias]) {
            requestUntil({ url: `${urlOn(host)}${protectedPath}` }, (r) => locationOf(r).includes(choiceLoginPath)).then(
                (response) => {
                    expect(locationOf(response), `${host}: choice page, not SAML`).to.include(choiceLoginPath)
                },
            )
        }
    })

    it('leaves the stock login alone on a site where the module is not enabled, even on a listed host', () => {
        requestUntil({ url: `${urlOn(DIGITALL.host)}${protectedPath}` }, (r) => locationOf(r).includes(samlConnectPath))

        setSamlLoginProviderEnabled(DIGITALL.siteKey, false)

        // The site's module list reaches routing within a few seconds (cached index): poll for it.
        const stock = (r: Cypress.Response<unknown>) =>
            !locationOf(r).includes(samlConnectPath) && !locationOf(r).includes(choiceLoginPath)
        requestUntil({ url: `${urlOn(DIGITALL.host)}${protectedPath}` }, stock, { attempts: 30, intervalMs: 1000 }).then(
            (response) => {
                expect(stock(response), `stock login, got ${locationOf(response) || response.status}`).to.be.true
            },
        )
    })

    it('leaves the stock login alone on a host whose site is not configured', () => {
        // First make sure digitall's list is in effect, so the check below is not just early.
        requestUntil({ url: `${urlOn(DIGITALL.host)}${protectedPath}` }, (r) =>
            locationOf(r).includes(samlConnectPath),
        )

        // "jahia" resolves to no site, so no list applies to it: Jahia's own login flow.
        cy.request({ url: protectedPath, followRedirect: false, failOnStatusCode: false }).then((response) => {
            expect(locationOf(response), 'no SAML redirect').to.not.include(samlConnectPath)
            expect(locationOf(response), 'no choice page').to.not.include(choiceLoginPath)
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
