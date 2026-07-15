import {DocumentNode} from 'graphql';
import {editSite} from '@jahia/cypress';

describe('Security.txt Settings', () => {
    function setServerName(siteKey: string, serverName: string) {
        editSite(siteKey, {serverName: serverName});
    }

    const siteKey = 'digitall';
    const adminPath = `/jahia/administration/${siteKey}/securitytxt`;
    const securityPath = '/.well-known/security.txt';
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const updateSecurityTxt: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/updateSecurityTxt.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSecurityTxtSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSecurityTxtSettings.graphql');

    before(() => {
        cy.login();
        setServerName(siteKey, 'jahia');
        // Reset all settings to empty before each test to guarantee a clean state
        cy.apollo({
            mutation: updateSecurityTxt,
            variables: {
                siteKey,
                contact: null,
                expires: null,
                acknowledgments: null,
                canonical: null,
                encryption: null,
                hiring: null,
                policy: null,
                preferredLanguages: null
            }
        });
    });

    it('check mandatory fields', () => {
        cy.login();
        cy.visit(adminPath);

        cy.get('#securitytxt-contact').should('be.visible');
        cy.get('#securitytxt-expires').should('be.visible');

        // Action buttons
        cy.contains('button', 'Save').should('be.visible');
        cy.contains('button', 'Cancel').should('be.visible');
    });

    it('saves text fields and shows a success alert', () => {
        cy.login();
        cy.visit(adminPath);

        cy.get('#securitytxt-contact input').type('mailto:security@example.com');
        cy.get('#securitytxt-expires').type('2027-12-31T23:59:59');

        cy.contains('button', '+ Canonical URL').click();
        cy.get('#securitytxt-canonical input').type('https://example.com/.well-known/security.txt');

        cy.contains('button', '+ Preferred languages').click();
        cy.get('#securitytxt-preferredLanguages input').type('en, fr');

        cy.contains('button', 'Save').click();

        cy.contains('Security.txt settings saved successfully.').should('be.visible');

        // Verify persistence via GraphQL
        cy.apollo({query: getSecurityTxtSettings, variables: {siteKey}})
            .its('data.securityTxt.settings')
            .should((settings: Record<string, string>) => {
                expect(settings.contact).to.equal('mailto:security@example.com');
                expect(settings.canonical).to.equal('https://example.com/.well-known/security.txt');
                expect(settings.preferredLanguages).to.equal('en, fr');
            });

        cy.request('http://jahia:8080' + securityPath);
    });

    it('saves the expires field in RFC 3339 format with local timezone offset', () => {
        cy.login();
        cy.visit(adminPath);

        // The input type is datetime-local; the component converts to RFC 3339 on save
        cy.get('#securitytxt-expires').type('2027-12-31T23:59:59');
        cy.contains('button', 'Save').click();

        cy.contains('Security.txt settings saved successfully.').should('be.visible');

        // Verify the stored value is in RFC 3339 format: YYYY-MM-DDTHH:MM:SS.mss±HH:MM
        cy.apollo({query: getSecurityTxtSettings, variables: {siteKey}})
            .its('data.securityTxt.settings.expires')
            .should('match', /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?([+-]\d{2}:\d{2}|Z)$/);
        cy.request('http://jahia:8080' + securityPath);
    });

    it('cancels edits and reverts the form to the last saved values', () => {
        cy.login();
        // Pre-load a saved contact via API
        cy.apollo({
            mutation: updateSecurityTxt,
            variables: {
                siteKey,
                contact: 'mailto:original@example.com',
                expires: null,
                acknowledgments: null,
                canonical: null,
                encryption: null,
                hiring: null,
                policy: null,
                preferredLanguages: null
            }
        });

        cy.visit(adminPath);
        cy.get('#securitytxt-contact input').should('have.value', 'mailto:original@example.com');

        // Modify without saving
        cy.get('#securitytxt-contact input').clear();
        cy.get('#securitytxt-contact input').type('mailto:changed@example.com');
        cy.get('#securitytxt-contact input').should('have.value', 'mailto:changed@example.com');

        cy.contains('button', 'Cancel').click();

        // Value should revert to the last saved one
        cy.get('#securitytxt-contact input').should('have.value', 'mailto:original@example.com');
    });

    it('sets and retrieves all text fields via the GraphQL API', () => {
        cy.login();
        cy.apollo({
            mutation: updateSecurityTxt,
            variables: {
                siteKey,
                contact: 'mailto:api@example.com',
                expires: '2027-06-01T00:00:00.000+00:00',
                canonical: 'https://api.example.com/.well-known/security.txt',
                preferredLanguages: 'en',
                acknowledgments: null,
                encryption: null,
                hiring: null,
                policy: null
            }
        }).then((result: { data: { securityTxt: { update: Record<string, string> } } }) => {
            const s = result.data.securityTxt.update;
            expect(s.contact).to.equal('mailto:api@example.com');
            expect(s.canonical).to.equal('https://api.example.com/.well-known/security.txt');
            expect(s.preferredLanguages).to.equal('en');
        });

        cy.apollo({query: getSecurityTxtSettings, variables: {siteKey}})
            .its('data.securityTxt.settings')
            .should((settings: Record<string, string>) => {
                expect(settings.contact).to.equal('mailto:api@example.com');
                expect(settings.canonical).to.equal('https://api.example.com/.well-known/security.txt');
                expect(settings.preferredLanguages).to.equal('en');
            });
        cy.request('http://jahia:8080' + securityPath);
    });

    it('clears a field by saving an empty value', () => {
        cy.login();
        // Pre-load required fields (contact + expires) and an optional field (canonical) to clear
        cy.apollo({
            mutation: updateSecurityTxt,
            variables: {
                siteKey,
                contact: 'mailto:todelete@example.com',
                expires: '2027-06-01T00:00:00.000+00:00',
                acknowledgments: null,
                canonical: 'https://example.com/.well-known/security.txt',
                encryption: null,
                hiring: null,
                policy: null,
                preferredLanguages: null
            }
        });

        cy.visit(adminPath);
        cy.get('#securitytxt-canonical input').should('have.value', 'https://example.com/.well-known/security.txt');

        cy.get('#securitytxt-canonical input').clear();
        cy.contains('button', 'Save').click();
        cy.contains('Security.txt settings saved successfully.').should('be.visible');

        cy.apollo({query: getSecurityTxtSettings, variables: {siteKey}})
            .its('data.securityTxt.settings.canonical')
            .should('be.null');
    });

    it('reloads saved values when navigating back to the settings page', () => {
        cy.login();
        // Save via API
        cy.apollo({
            mutation: updateSecurityTxt,
            variables: {
                siteKey,
                contact: 'mailto:persist@example.com',
                canonical: 'https://persist.example.com/.well-known/security.txt',
                preferredLanguages: 'de',
                expires: null,
                acknowledgments: null,
                encryption: null,
                hiring: null,
                policy: null
            }
        });

        // First visit
        cy.visit(adminPath);
        cy.get('#securitytxt-contact input').should('have.value', 'mailto:persist@example.com');

        // Navigate away and come back
        cy.visit('/jahia/administration');
        cy.visit(adminPath);

        cy.get('#securitytxt-contact input').should('have.value', 'mailto:persist@example.com');
        cy.get('#securitytxt-canonical input').should(
            'have.value',
            'https://persist.example.com/.well-known/security.txt'
        );
        cy.get('#securitytxt-preferredLanguages input').should('have.value', 'de');
    });

    it('shows NodePicker search inputs and triggers a page search on Enter', () => {
        cy.login();
        cy.visit(adminPath);

        // Add optional NodePicker fields so their search inputs are rendered
        cy.contains('button', '+ Acknowledgments page').click();
        cy.contains('button', '+ PGP key file').click();
        cy.contains('button', '+ Hiring page').click();
        cy.contains('button', '+ Security policy page').click();

        // There are 4 NodePicker fields: acknowledgments, encryption (files), hiring, policy
        cy.get('input[placeholder="Search…"]').should('have.length', 4);

        // Search in the first NodePicker (Acknowledgments — pages)
        cy.get('input[placeholder="Search…"]').first().type('home');
        cy.get('input[placeholder="Search…"]').first().type('{enter}');

        // After pressing Enter the query fires; open the dropdown and verify it has items
        cy.get('.moonstone-dropdown_container').first().should('be.visible').click();
        cy.get('.moonstone-menu:not(.moonstone-hidden)')
            .find('.moonstone-menuItem')
            .should('have.length.greaterThan', 0);
    });

    it('shows NodePicker search inputs and triggers a file search on Enter', () => {
        cy.login();
        cy.visit(adminPath);

        // Add optional NodePicker fields so their search inputs are rendered
        cy.contains('button', '+ PGP key file').click();

        // The Encryption NodePicker is the second one (index 1 among search inputs)
        cy.get('input[placeholder="Search…"]').eq(0).type('placeholder');
        cy.get('input[placeholder="Search…"]').eq(0).type('{enter}');

        // Open the dropdown and verify it has items
        cy.get('.moonstone-dropdown_container').eq(1).should('be.visible').click();
        cy.get('.moonstone-menu:not(.moonstone-hidden)')
            .find('.moonstone-menuItem')
            .should('have.length.greaterThan', 0);
    });
});
