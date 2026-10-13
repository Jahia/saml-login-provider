import {
    configureDigitallHostNames,
    DIGITALL,
    locationOf,
    requestUntil,
    setSamlLoginProviderHosts,
} from '../utils/samlLoginProvider'

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
    const idpUrl = 'http://idp.jahia.test:8180'
    const realm = 'jahia-saml'
    const idpMetadataUrl = `${idpUrl}/realms/${realm}/protocol/saml/descriptor`
    // Must match the clientId in saml-idp/realm/jahia-saml-realm.json.
    const spEntityId = 'jahia-digitall'
    // The IdP user and the names the realm file gives it.
    const samlUser = 'saml.tester'
    const samlFirstName = 'Saml'
    const samlLastName = 'Tester'
    const samlConnectPath = '/home.connect.saml'
    // Protected: an anonymous request is a 401 that saml-login-provider turns into a redirect.
    const protectedPath = '/start'

    const IDP_STARTUP_POLL = { attempts: 40, intervalMs: 3000 }
    const CONNECTOR_POLL = { attempts: 30, intervalMs: 1000 }

    // A fresh password for the IdP user on every run, so no credential is stored anywhere.
    // crypto.randomUUID() is unavailable here: the spec runs on http://, not a secure context.
    function randomSecret(): string {
        const bytes = new Uint8Array(24)
        crypto.getRandomValues(bytes)
        return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
    }

    const samlPassword = randomSecret()

    function resetIdpUserPassword() {
        cy.request({
            method: 'POST',
            url: `${idpUrl}/realms/master/protocol/openid-connect/token`,
            form: true,
            log: false,
            // OAuth 2.0 wire field names, fixed by the token endpoint.
            body: {
                // eslint-disable-next-line camelcase
                grant_type: 'password',
                // eslint-disable-next-line camelcase
                client_id: 'admin-cli',
                username: Cypress.env('KEYCLOAK_ADMIN'),
                password: Cypress.env('KEYCLOAK_ADMIN_PASSWORD'),
            },
        }).then((tokenResponse) => {
            const auth = { bearer: tokenResponse.body.access_token as string }
            cy.request({
                url: `${idpUrl}/admin/realms/${realm}/users`,
                qs: { username: samlUser, exact: true },
                auth,
            }).then((usersResponse) => {
                expect(usersResponse.body, `IdP user ${samlUser}`).to.have.length(1)
                cy.request({
                    method: 'PUT',
                    url: `${idpUrl}/admin/realms/${realm}/users/${usersResponse.body[0].id}/reset-password`,
                    auth,
                    log: false,
                    body: { type: 'password', value: samlPassword, temporary: false },
                })
            })
        })
    }

    before(() => {
        requestUntil({ url: idpMetadataUrl }, (r) => r.status === 200, IDP_STARTUP_POLL)
            .its('status')
            .should('eq', 200)
        resetIdpUserPassword()

        configureDigitallHostNames()
        cy.executeGroovy('groovy/configureSamlConnector.groovy', {
            '@@SITE@@': siteKey,
            '@@SP_ENTITY_ID@@': spEntityId,
            '@@SP_BASE_URL@@': siteUrl,
            // Jahia fetches this itself, over the stack network.
            '@@IDP_METADATA_URL@@': idpMetadataUrl,
            '@@POST_LOGIN_PATH@@': '/home.html',
        })

        // Felix FileInstall picks the connector .cfg up asynchronously. Until it has, the valve
        // answers 400 (no connector for this site); once it has, it serves the HTTP-POST
        // auto-submit form aimed at the IdP. Only then is it safe to enable the redirect: a
        // configured host without a working connector locks visitors out.
        const connectorServes = (r: Cypress.Response<unknown>) =>
            r.status === 200 && typeof r.body === 'string' && r.body.includes('idp.jahia.test')
        requestUntil({ url: `${siteUrl}${samlConnectPath}` }, connectorServes, CONNECTOR_POLL).then((r) => {
            expect(connectorServes(r), `${samlConnectPath} serves the AuthnRequest form`).to.be.true
        })

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

        // Where the callback sends the visitor back: the page first asked for, to which Jahia adds
        // ?site=. Watched as a request rather than read from cy.url()/cy.location(): right after
        // the AUT comes back from the IdP's origin, Cypress intermittently yields [] for both.
        const escapedSiteUrl = siteUrl.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
        cy.intercept({ method: 'GET', url: new RegExp(`^${escapedSiteUrl}${protectedPath}\\?site=`) }).as('landing')

        // The module must send this straight to the SAML connect endpoint, which auto-submits the
        // AuthnRequest to the IdP.
        cy.visit(`${siteUrl}${protectedPath}`, { failOnStatusCode: false })

        // The callback is serialized into the IdP origin: it can only see what is passed in args.
        cy.origin(idpUrl, { args: { user: samlUser, password: samlPassword } }, ({ user, password }) => {
            cy.get('#kc-form-login').should('be.visible')
            cy.get('#username').type(user)
            cy.get('#password').type(password, { log: false })
            cy.get('#kc-login').click()
        })

        // The IdP POSTs the signed assertion to /home.callback.saml, which returns the visitor to
        // the URL they first asked for (carried as ?redirect=). Jahia answering that page with 403
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
            .then((connect) => {
                const authnRequest = parseForm(connect.body, 'form', siteUrl)
                expect(authnRequest.action, 'AuthnRequest target').to.eq(`${idpUrl}/realms/${realm}/protocol/saml`)
                expect(authnRequest.fields, 'AuthnRequest form').to.have.property('SAMLRequest')
                return cy.request({ method: 'POST', url: authnRequest.action, form: true, body: authnRequest.fields })
            })
            .then((loginPage) => {
                const login = parseForm(loginPage.body, '#kc-form-login', idpUrl)
                return cy.request({
                    method: 'POST',
                    url: login.action,
                    form: true,
                    log: false,
                    body: { ...login.fields, username: samlUser, password: samlPassword },
                })
            })
            .then((idpAnswer) => {
                const samlResponse = parseForm(idpAnswer.body, 'form', idpUrl)
                // Pac4j appends ?client_name=SAML2Client to the ACS URL it advertises.
                expect(samlResponse.action, 'assertion consumer service').to.match(
                    new RegExp(`^${siteUrl}/home\\.callback\\.saml(\\?|$)`),
                )
                expect(samlResponse.fields, 'IdP answer').to.have.property('SAMLResponse')
                return cy.request({
                    method: 'POST',
                    url: samlResponse.action,
                    form: true,
                    body: samlResponse.fields,
                    followRedirect: false,
                    failOnStatusCode: false,
                })
            })
            .then((callback) => {
                expect(callback.status, 'callback answer').to.be.oneOf([302, 303])
                expect(locationOf(callback), 'back to the page first asked for').to.include(protectedPath)
                // The callback only stores the authenticated profile in the session; Jahia's
                // authentication valve turns it into a login on the NEXT request -- the redirect a
                // browser follows. Skip it and the session stays anonymous.
                return cy.request({
                    url: new URL(locationOf(callback), siteUrl).href,
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

interface ParsedForm {
    action: string
    fields: Record<string, string>
}

/** The first form matching selector in an HTML page: absolute action URL and named input values. */
function parseForm(html: string, selector: string, pageUrl: string): ParsedForm {
    const form = new DOMParser().parseFromString(html, 'text/html').querySelector<HTMLFormElement>(selector)
    expect(form, `${selector} in the page`).to.not.be.null
    const fields: Record<string, string> = {}
    form.querySelectorAll<HTMLInputElement>('input[name]').forEach((input) => {
        fields[input.name] = input.value
    })
    return { action: new URL(form.getAttribute('action') ?? '', pageUrl).href, fields }
}
