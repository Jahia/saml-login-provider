import {
    DIGITALL,
    IDP,
    locationOf,
    requestUntil,
    RETURN_PATH,
    setSamlLoginProviderHosts,
    setUpDigitallForSaml,
} from '../utils/samlLoginProvider'
import { loginThroughIdp, randomSecret, resetIdpUserPassword, SAML_USER } from '../utils/idp'

// Full auto-login round trip: an unauthenticated visitor on a configured host is sent by
// saml-login-provider to /home.connect.saml, the valve hands them to a real Keycloak, and the
// signed assertion brings them back into a Jahia session.
//
// Spec 02 stops at "the Location header points at /home.connect.saml". This one follows it all
// the way, against the `idp` service from docker-compose.yml.
//
// NOTE: the test's first cy.visit goes to digitall.jahia.test, not the Jahia baseUrl, so Cypress
// reloads the spec at that origin and runs before() a second time. Everything in before() is
// idempotent, and the reload also re-draws samlPassword, which the second before() then sets.
describe('SAML auto login', () => {
    const siteKey = DIGITALL.siteKey
    // Network aliases from docker-compose.yml. Both live under jahia.test, so the IdP's POST back
    // to Jahia is same-site and the session cookie travels with it.
    const siteHost = DIGITALL.host
    const siteUrl = `http://${siteHost}:8080`
    const idpUrl = IDP.url
    const realm = IDP.realm
    const samlUser = SAML_USER.username
    const samlFirstName = SAML_USER.firstName
    const samlLastName = SAML_USER.lastName
    const samlConnectPath = '/home.connect.saml'
    // Protected: an anonymous request is a 401 that saml-login-provider turns into a redirect.
    const protectedPath = '/start'

    // A fresh password for the IdP user on every run, so no credential is stored anywhere.
    const samlPassword = randomSecret()

    before(() => {
        setUpDigitallForSaml()
        resetIdpUserPassword(samlPassword)

        setSamlLoginProviderHosts(siteKey, siteHost)
        requestUntil({ url: `${siteUrl}${protectedPath}` }, (r) => locationOf(r).includes(samlConnectPath)).then(
            (r) => {
                expect(locationOf(r), 'host list in effect').to.include(samlConnectPath)
            },
        )
    })

    after(() => {
        // Server-wide setting: leave the baseline other specs expect.
        setSamlLoginProviderHosts(siteKey, null)
    })

    it('takes an unauthenticated visitor through the IdP login page and back to the page they asked for', () => {
        cy.clearAllCookies()

        // Where the visitor ends up: the page first asked for, after the module's login hop.
        // Watched as a request rather than read from cy.url()/cy.location(): right after the AUT
        // comes back from the IdP's origin, Cypress intermittently yields [] for both.
        const escapedSiteUrl = siteUrl.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
        cy.intercept({ method: 'GET', url: new RegExp(`^${escapedSiteUrl}${protectedPath}(\\?|$)`) }).as('landing')

        // The module must send this straight to the SAML connect endpoint, which auto-submits the
        // AuthnRequest to the IdP.
        cy.visit(`${siteUrl}${protectedPath}`, { failOnStatusCode: false })
        // The same pattern sees this anonymous visit first (sent on to SAML); the landing comes next.
        cy.wait('@landing').then(({ response }) => {
            expect(response?.statusCode, 'anonymous visit sent on to SAML').to.be.oneOf([301, 302, 303])
        })

        // The callback is serialized into the IdP origin: it can only see what is passed in args.
        cy.origin(idpUrl, { args: { user: samlUser, password: samlPassword } }, ({ user, password }) => {
            cy.get('#kc-form-login').should('be.visible')
            cy.get('#username').type(user)
            cy.get('#password').type(password, { log: false })
            cy.get('#kc-login').click()
        })

        // The IdP POSTs the signed assertion to /home.callback.saml, which returns the visitor through
        // the module's login hop to the URL they first asked for (carried as ?redirect=). Jahia answering that page with 403
        // -- not with another redirect to the IdP -- means it saw a logged-in visitor, one who
        // simply is not an editor (/start is the edit-mode entry).
        cy.wait('@landing', { timeout: 30000 }).then(({ response }) => {
            expect(response?.statusCode, 'landing page answered to a logged-in non-editor').to.eq(403)
        })

        // The full SESSION check (who is logged in, with which attributes) is the next test: the
        // session cookie is set while the AUT is coming back from the IdP's origin, and Cypress
        // keeps cookies from such responses in its own simulated jar rather than the browser's --
        // after the round trip the browser holds no cookie to inspect, although Jahia opened the
        // session (the 403 above was served through that simulated jar).
    })

    it('opens a Jahia session for the IdP user, carrying the attributes the connector maps', () => {
        cy.clearAllCookies()

        // The same round trip at the HTTP level. cy.request reads and writes the browser's real
        // cookie jar whatever origin the runner is on, so the session can be checked at the end.
        // Every hop is the form the browser would auto-submit or the user would fill in.
        cy.request({ url: `${siteUrl}${protectedPath}`, followRedirect: false, failOnStatusCode: false })
            .then((anonymous) => {
                expect(locationOf(anonymous), 'redirect of an anonymous request').to.include(samlConnectPath)
                return cy.request(new URL(locationOf(anonymous), siteUrl).href)
            })
            // Pac4j appends ?client_name=SAML2Client to the ACS URL it advertises.
            .then((connect) =>
                loginThroughIdp(connect, siteUrl, samlPassword, new RegExp(`^${siteUrl}/home\\.callback\\.saml(\\?|$)`)),
            )
            .then((callback) => {
                expect(callback.status, 'callback answer').to.be.oneOf([302, 303])
                // Back through the module's login hop, where the login completes, then on
                expect(locationOf(callback), 'callback answer').to.include(RETURN_PATH)
                return cy.request({ url: new URL(locationOf(callback), siteUrl).href, followRedirect: false, failOnStatusCode: false })
            })
            .then((hop) => {
                expect(locationOf(hop), 'back to the page first asked for').to.match(new RegExp(`${protectedPath}($|\\?)`))
                // The callback only stores the authenticated profile in the session; Jahia's
                // authentication valve turns it into a login on the NEXT request (the hop). Skip it
                // and the session stays anonymous.
                return cy.request({
                    url: new URL(locationOf(hop), siteUrl).href,
                    followRedirect: false,
                    failOnStatusCode: false,
                })
            })
            .then((landing) => {
                // Not sent round to the IdP again: the visitor is now known to Jahia. (/start itself
                // answers 403 here -- saml.tester is an authenticated visitor, not an editor.)
                expect(locationOf(landing), 'no second trip to the IdP').to.not.include(samlConnectPath)
            })

        // Checked the way a visitor sees it: Digitall's header greets a logged-in user by display
        // name and offers Logout. The name is "<j:firstName> <j:lastName>", both written by the
        // connector's user mapper from the SAML assertion -- so this proves the session AND the
        // mapping. (Not via GraphQL: Jahia's default API policy grants it to privileged users only,
        // and a freshly provisioned SAML visitor rightly is not one.)
        cy.request(`${siteUrl}/home.html`).then((home) => {
            const page = (home.body as string).replace(/\s+/g, ' ')
            // Assert on extracts, so a failure reports the header rather than the whole page.
            const headerName = page.match(/fa-user"><\/i>&nbsp;([^<]*?)<span class="caret">/)?.[1]
            expect(headerName, 'name in the logged-in header').to.eq(`${samlFirstName}&nbsp;${samlLastName}`)
            expect(page.includes('href="/cms/logout'), 'Logout offered').to.be.true
        })
    })
})
