/*
 * Give the target site a server name that actually resolves to it.
 *
 * WHY. SAML2Util.findSiteKeyForRequest() resolves the site with
 * getSiteByServerName(request.getServerName()), and saml-login-provider redirects to
 * /home.connect.saml with no siteKey parameter. On a stock tests/ stack BOTH systemsite and
 * digitall carry j:serverName = localhost, systemsite wins the lookup, and every other host
 * resolves to null (there is no fallback to the default site, even though that is digitall):
 *
 *   resolve(localhost)  -> systemsite
 *   resolve(127.0.0.1)  -> null
 *   resolve(jahia)      -> null
 *
 * So a connector bound to digitall is unreachable until some hostname maps to digitall.
 *
 * This adds @@HOST@@ to digitall's j:serverNameAliases rather than touching systemsite's
 * j:serverName. It is additive and reversible, it leaves localhost -> systemsite exactly as it
 * was, and it mirrors production, where content sites answer on their own hostnames.
 *
 * @@HOST@@ and @@SITE@@ are substituted by setup.sh.
 */
import org.jahia.services.content.*
import org.jahia.services.sites.JahiaSitesService

def host = '@@HOST@@'
def siteKey = '@@SITE@@'
def out = new StringBuilder()

JCRTemplate.instance.doExecuteWithSystemSession(null, 'default', null, { session ->
    def site = session.getNode('/sites/' + siteKey)

    def aliases = site.hasProperty('j:serverNameAliases')
            ? site.getProperty('j:serverNameAliases').values*.string as List
            : []

    if (aliases.contains(host)) {
        out << "alias ${host} already present on ${siteKey}\n"
    } else {
        aliases << host
        site.setProperty('j:serverNameAliases', aliases as String[])
        session.save()
        out << "added alias ${host} to ${siteKey}\n"
    }

    out << "resolve(${host}) -> " + JahiaSitesService.instance.getSiteByServerName(host, session)?.siteKey + "\n"
    out << "resolve(localhost) -> " + JahiaSitesService.instance.getSiteByServerName('localhost', session)?.siteKey + "\n"
    return null
} as JCRCallback)

new File('/tmp/slp-site-host.txt').text = out.toString()
