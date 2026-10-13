/*
 * Disable a module on a site, the way the site's Modules administration does.
 *
 * The provisioning API offers "enable" but no "disable" operation, and @jahia/cypress's
 * disableModule only edits j:installedModules through GraphQL, bypassing the template manager.
 *
 * @@MODULE@@ and @@SITE@@ are substituted by the Cypress specs (cy.executeGroovy replacements).
 */
import org.jahia.registries.ServicesRegistry
import org.jahia.services.content.*

JCRTemplate.instance.doExecuteWithSystemSession({ session ->
    ServicesRegistry.instance.jahiaTemplateManagerService.uninstallModulesByIds(
            ['@@MODULE@@'], '/sites/@@SITE@@', session)
    session.save()
    return null
} as JCRCallback)
