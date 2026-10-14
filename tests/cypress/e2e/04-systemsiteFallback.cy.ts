import {
    EDIT_HOST,
    locationOf,
    requestUntil,
    RETURN_PATH,
    returnHop,
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

    interface Hop {
        url: string
        status: number
        location?: string
    }

    /** Follow a redirect chain by hand from a non-followed response, recording every hop. */
    const followRedirects = (first: Cypress.Response<unknown>, base: string, maxHops = 8) => {
        const chain: Hop[] = []
        const step = (response: Cypress.Response<unknown>, url: string, hops: number): Cypress.Chainable<Hop[]> => {
            const location = locationOf(response)
            chain.push({ url, status: response.status, location })
            if (!location || response.status < 300 || response.status >= 400 || hops >= maxHops) {
                return cy.wrap(chain, { log: false })
            }
            const next = new URL(location, url).href
            return cy
                .request({ url: next, followRedirect: false, failOnStatusCode: false })
                .then((r) => step(r, next, hops + 1))
        }
        return step(first, `${base}/home.callback.saml`, 0)
    }

    /** Anonymous /tools: core's redirect to /cms/login, then where that page sends the visitor. */
    const toolsLoginHop = () =>
        cy.request({ url: `${editUrl}/tools`, followRedirect: false, failOnStatusCode: false }).then((tools) => {
            expect(locationOf(tools), "core's own login redirect").to.include('/cms/login')
            return cy.request({
                url: new URL(locationOf(tools), editUrl).href,
                followRedirect: false,
                failOnStatusCode: false,
            })
        })

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

    it('shows the modified login page for /tools, which core guards with its own login redirect', () => {
        // Jahia's Shiro guard sends anonymous /tools visitors to /cms/login?redirect=/tools directly,
        // never through the 401 page; the module catches that page instead.
        toolsLoginHop().then((login) => {
            expect(locationOf(login), 'core login page sent on').to.include(
                `${choiceLoginPath}?redirect=${encodeURIComponent('/tools')}`,
            )
        })
    })

    it('keeps the stock login page for a failed login, so its error shows', () => {
        cy.request({ url: `${editUrl}/cms/login?loginError=bad_password`, followRedirect: false, failOnStatusCode: false })
            .its('status')
            .should('not.be.oneOf', [301, 302, 303, 307])
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

    it('keeps the page asked for across a cross-site IdP, by sending the redirect cookie with SameSite=None', () => {
        // The valve keeps the post-login target in a "redirect" cookie and reads it back when the IdP
        // POSTs to the callback. From an IdP on another site (Google), browsers only send it with
        // SameSite=None -- which in turn needs Secure, so this asks as if through a TLS proxy.
        // (The browser side cannot be shown here: CI runs on http, and its IdP shares jahia.test.)
        const connect = `${editUrl}${samlConnectPath}?siteKey=${siteKey}&redirect=${encodeURIComponent(protectedPath)}`
        const redirectCookie = (r: Cypress.Response<unknown>) =>
            ([].concat(r.headers['set-cookie'] ?? []) as string[]).find((c) => c.startsWith('redirect=')) ?? ''

        cy.request({ url: connect, headers: { 'X-Forwarded-Proto': 'https' } }).then((response) => {
            expect(redirectCookie(response), 'redirect cookie behind a TLS proxy').to.eq(
                `redirect=${protectedPath}; Path=/; Secure; HttpOnly; SameSite=None`,
            )
        })
        // Plain http: browsers reject SameSite=None without Secure, so the valve's own cookie stays.
        cy.request({ url: connect }).then((response) => {
            expect(redirectCookie(response), 'redirect cookie over plain http').to.match(
                new RegExp(`^redirect=${protectedPath};`),
            )
            expect(redirectCookie(response), 'redirect cookie over plain http').to.not.include('SameSite=None')
        })
    })

    it('goes straight to SAML, naming systemsite, once systemsite lists the host', () => {
        setSamlLoginProviderHosts(siteKey, EDIT_HOST.host)

        requestUntil(anonymousStart(), (r) => locationOf(r).includes(samlConnectPath)).then((response) => {
            expect(locationOf(response), 'SAML connect for systemsite').to.include(
                `${samlConnectPath}?siteKey=${siteKey}&redirect=${encodeURIComponent(returnHop(protectedPath))}`,
            )
        })
        toolsLoginHop().then((login) => {
            expect(locationOf(login), '/tools straight to SAML too').to.include(
                `${samlConnectPath}?siteKey=${siteKey}&redirect=${encodeURIComponent(returnHop('/tools'))}`,
            )
        })
    })

    it('lands on /tools after a SAML login, without core failing the first request', () => {
        // The first request after the callback completes the login. On /tools, core's Shiro filter
        // runs that login itself and fails on the session it just invalidated
        // (InvalidSessionException, a 500) -- so the module returns visitors through a hop that
        // completes it the normal way first.
        cy.clearAllCookies()
        const acsUrl = new RegExp(`^${editUrl}/home\\.callback\\.saml\\?siteKey=${siteKey}(&|$)`)

        toolsLoginHop()
            .then((login) => {
                expect(locationOf(login), 'straight to SAML from /tools').to.include(samlConnectPath)
                return cy.request(new URL(locationOf(login), editUrl).href)
            })
            .then((connect) => loginThroughIdp(connect, editUrl, samlPassword, acsUrl))
            .then((callback) => followRedirects(callback, editUrl))
            .then((chain) => {
                const seen = chain.map((hop) => `${hop.status} ${hop.url}`).join(' -> ')
                expect(chain.filter((hop) => hop.status >= 500), `no server error: ${seen}`).to.be.empty
                const tools = chain.find((hop) => new URL(hop.url).pathname === '/tools')
                expect(tools, `/tools reached: ${seen}`).to.not.be.undefined
                // Logged in now: not sent to log in again (saml.tester may lack the tools permission)
                expect(tools?.location ?? '', `/tools answer: ${seen}`).to.not.match(/\/cms\/login|\.saml/)
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
                // Back through the module's login hop, where the login completes, then on
                expect(locationOf(callback), 'callback answer').to.include(RETURN_PATH)
                return cy.request({ url: new URL(locationOf(callback), editUrl).href, followRedirect: false, failOnStatusCode: false })
            })
            .then((hop) => {
                expect(locationOf(hop), 'back to the page first asked for').to.match(new RegExp(`${protectedPath}($|\\?)`))
                // The callback only stores the authenticated profile in the session; Jahia's
                // authentication valve turns it into a login on the NEXT request (the hop). Skip it
                // and the session stays anonymous.
                return cy.request({
                    url: new URL(locationOf(hop), editUrl).href,
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
