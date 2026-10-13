import {
    EDIT_HOST,
    locationOf,
    requestUntil,
    setSamlLoginProviderEnabled,
    setSamlLoginProviderHosts,
    setUpSystemsiteForSaml,
} from '../utils/samlLoginProvider'
import { loginThroughIdp, parseForm, randomSecret, resetIdpUserPassword } from '../utils/idp'

// A host that NO site declares -- in production, typically the editing host (edit.<domain>) --
// falls back to systemsite's settings: the modified login page wherever systemsite has the module
// and SAML set up, straight SAML when systemsite lists the host. The valve cannot find a site from
// such a host, so every SAML URL names systemsite explicitly.
//
// Everything goes through cy.request (no cy.visit to another origin), so before() runs once.
describe('Fallback to systemsite for a host no site declares', () => {
    const siteKey = EDIT_HOST.siteKey
    const editUrl = `http://${EDIT_HOST.host}:8080`
    const protectedPath = '/start'
    const choiceLoginPath = '/saml-login'
    const samlConnectPath = '/home.connect.saml'
    const samlPassword = randomSecret()

    const anonymousStart = () => ({ url: `${editUrl}${protectedPath}` })

    before(() => {
        setUpSystemsiteForSaml()
        resetIdpUserPassword(samlPassword)
        setSamlLoginProviderHosts(siteKey, null)
    })

    after(() => {
        // Leave the baseline other specs expect: systemsite does not act on unclaimed hosts.
        setSamlLoginProviderHosts(siteKey, null)
        setSamlLoginProviderEnabled(siteKey, false)
    })

    it('shows the modified login page on a host no site declares', () => {
        // The routing index refreshes within seconds of the module being enabled on systemsite.
        requestUntil(anonymousStart(), (r) => locationOf(r).includes(choiceLoginPath)).then((response) => {
            expect(locationOf(response), 'choice page, keeping the target').to.include(
                `${choiceLoginPath}?redirect=${encodeURIComponent(protectedPath)}`,
            )
        })
    })

    it('names systemsite in the Google form, so the valve finds its connector', () => {
        cy.request(`${editUrl}${choiceLoginPath}`).then((page) => {
            const google = parseForm(page.body, '#googleLoginForm', editUrl)
            expect(google.fields, 'Google form').to.have.property('siteKey', siteKey)

            // What the button submits: the valve's AuthnRequest page, not a 400 for a host of no site.
            cy.request({ url: google.action, qs: google.fields, failOnStatusCode: false }).then((connect) => {
                expect(connect.status, 'connect endpoint answer').to.eq(200)
                expect(connect.body, 'AuthnRequest form aimed at the IdP').to.include('idp.jahia.test')
            })
        })
    })

    it('goes straight to SAML, naming systemsite, once systemsite lists the host', () => {
        setSamlLoginProviderHosts(siteKey, EDIT_HOST.host)

        requestUntil(anonymousStart(), (r) => locationOf(r).includes(samlConnectPath)).then((response) => {
            expect(locationOf(response), 'SAML connect for systemsite').to.include(
                `${samlConnectPath}?siteKey=${siteKey}&redirect=${encodeURIComponent(protectedPath)}`,
            )
        })
    })

    it("opens a Jahia session through systemsite's connector", () => {
        cy.clearAllCookies()
        const acsUrl = new RegExp(`^${editUrl}/home\\.callback\\.saml\\?siteKey=${siteKey}(&|$)`)

        requestUntil(anonymousStart(), (r) => locationOf(r).includes(samlConnectPath))
            .then((anonymous) => cy.request(new URL(locationOf(anonymous), editUrl).href))
            .then((connect) => loginThroughIdp(connect, editUrl, samlPassword, acsUrl))
            .then((callback) => {
                expect(callback.status, 'callback answer').to.be.oneOf([302, 303])
                expect(locationOf(callback), 'back to the page first asked for').to.include(protectedPath)
                // Jahia's authentication valve turns the stored profile into a login on the NEXT
                // request -- the redirect a browser follows.
                return cy.request({
                    url: new URL(locationOf(callback), editUrl).href,
                    followRedirect: false,
                    failOnStatusCode: false,
                })
            })
            .then((landing) => {
                // A logged-in visitor who is not an editor: refused, not sent round to the IdP again.
                expect(locationOf(landing), 'no second trip to the IdP').to.not.include(samlConnectPath)
                expect(landing.status, 'edit-mode entry for a logged-in non-editor').to.eq(403)
            })
    })
})
