/*
 * Add a server name alias (j:serverNameAliases) to a site, idempotently.
 *
 * The host list is per site and only accepts the site's own names, and the "unlisted host of a
 * configured site gets the choice page" rule needs a site answering on two names. The specs give
 * digitall its server name with configureSiteHost.groovy and a second name with this script.
 *
 * @@ALIAS@@ and @@SITE@@ are substituted by the Cypress specs (cy.executeGroovy replacements).
 */
import org.jahia.services.content.*

def alias = '@@ALIAS@@'
def siteKey = '@@SITE@@'

JCRTemplate.instance.doExecuteWithSystemSession(null, 'default', null, { session ->
    def site = session.getNode('/sites/' + siteKey)
    def aliases = site.hasProperty('j:serverNameAliases') ?
            (site.getProperty('j:serverNameAliases').values*.string as List) : []
    if (!aliases.contains(alias)) {
        aliases << alias
        site.setProperty('j:serverNameAliases', aliases as String[])
        session.save()
    }
    return null
} as JCRCallback)
