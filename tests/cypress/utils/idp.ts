/// <reference types="cypress" />

import { IDP } from './samlLoginProvider'

// Helpers for the specs that log in through the CI IdP (the `idp` service of docker-compose.yml).

/** The IdP user and the names the realm file gives it. */
export const SAML_USER = {
    username: 'saml.tester',
    firstName: 'Saml',
    lastName: 'Tester',
}

/**
 * A fresh secret, so no credential is stored anywhere. crypto.randomUUID() is unavailable here:
 * the specs run on http://, not a secure context.
 */
export function randomSecret(): string {
    const bytes = new Uint8Array(24)
    crypto.getRandomValues(bytes)
    return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

/** Give the IdP user this password, through the Keycloak admin API. */
export function resetIdpUserPassword(password: string): void {
    cy.request({
        method: 'POST',
        url: `${IDP.url}/realms/master/protocol/openid-connect/token`,
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
            url: `${IDP.url}/admin/realms/${IDP.realm}/users`,
            qs: { username: SAML_USER.username, exact: true },
            auth,
        }).then((usersResponse) => {
            expect(usersResponse.body, `IdP user ${SAML_USER.username}`).to.have.length(1)
            cy.request({
                method: 'PUT',
                url: `${IDP.url}/admin/realms/${IDP.realm}/users/${usersResponse.body[0].id}/reset-password`,
                auth,
                log: false,
                body: { type: 'password', value: password, temporary: false },
            })
        })
    })
}

export interface ParsedForm {
    action: string
    fields: Record<string, string>
}

/** The first form matching selector in an HTML page: absolute action URL and named input values. */
export function parseForm(html: string, selector: string, pageUrl: string): ParsedForm {
    const form = new DOMParser().parseFromString(html, 'text/html').querySelector<HTMLFormElement>(selector)
    expect(form, `${selector} in the page`).to.not.be.null
    const fields: Record<string, string> = {}
    form.querySelectorAll<HTMLInputElement>('input[name]').forEach((input) => {
        fields[input.name] = input.value
    })
    return { action: new URL(form.getAttribute('action') ?? '', pageUrl).href, fields }
}

/**
 * Drive a SAML login at the HTTP level, from the valve's AuthnRequest page to the callback, and
 * yield the callback's (non-followed) answer. cy.request reads and writes the browser's real cookie
 * jar whatever origin the runner is on, so the session can be checked afterwards. Every hop is the
 * form the browser would auto-submit or the user would fill in.
 *
 * @param connectPage the valve's answer to /home.connect.saml (the auto-submit AuthnRequest form)
 * @param acsUrl the assertion consumer service URL the IdP must post back to
 */
export function loginThroughIdp(
    connectPage: Cypress.Response<unknown>,
    pageUrl: string,
    password: string,
    acsUrl: RegExp,
): Cypress.Chainable<Cypress.Response<unknown>> {
    const authnRequest = parseForm(connectPage.body as string, 'form', pageUrl)
    expect(authnRequest.action, 'AuthnRequest target').to.eq(`${IDP.url}/realms/${IDP.realm}/protocol/saml`)
    expect(authnRequest.fields, 'AuthnRequest form').to.have.property('SAMLRequest')
    return cy
        .request({ method: 'POST', url: authnRequest.action, form: true, body: authnRequest.fields })
        .then((loginPage) => {
            const login = parseForm(loginPage.body, '#kc-form-login', IDP.url)
            return cy.request({
                method: 'POST',
                url: login.action,
                form: true,
                log: false,
                body: { ...login.fields, username: SAML_USER.username, password },
            })
        })
        .then((idpAnswer) => {
            const samlResponse = parseForm(idpAnswer.body, 'form', IDP.url)
            expect(samlResponse.action, 'assertion consumer service').to.match(acsUrl)
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
}
