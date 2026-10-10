/*
 * Give the target site its own server name, so that it actually resolves.
 *
 * WHY. SAML2Util.findSiteKeyForRequest() resolves the site with
 * getSiteByServerName(request.getServerName()), and saml-login-provider redirects to
 * /home.connect.saml with no siteKey parameter. On a stock tests/ stack BOTH systemsite and
 * digitall ship with j:serverName = localhost:
 *
 *   systemsite   serverName=localhost
 *   digitall     serverName=localhost
 *   resolve(localhost) -> systemsite     resolve(anything else) -> null
 *
 * So digitall HAS a server name, it is just the same one systemsite has: the lookup has two
 * candidates, systemsite wins, and digitall's server name is dead config. Nothing else resolves
 * either -- there is no fallback to the default site, even though that is digitall.
 *
 * This sets digitall's j:serverName to @@HOST@@, which is distinct from systemsite's localhost
 * and therefore unambiguous. That is the same field as Administration -> Sites -> digitall ->
 * Web project settings -> Server name, so the configuration is visible where an operator would
 * look for it, and it mirrors production, where content sites answer on their own hostnames.
 * systemsite is left alone and localhost -> systemsite still holds.
 *
 * Any leftover @@HOST@@ entry in j:serverNameAliases is removed, so the host is declared in
 * exactly one place.
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
    def dirty = false

    def current = site.hasProperty('j:serverName') ? site.getProperty('j:serverName').string : null
    if (current == host) {
        out << "${siteKey} serverName already ${host}\n"
    } else {
        site.setProperty('j:serverName', host)
        dirty = true
        out << "${siteKey} serverName ${current} -> ${host}\n"
    }

    // Drop the host from the aliases if an earlier run put it there; it belongs on j:serverName.
    if (site.hasProperty('j:serverNameAliases')) {
        def aliases = site.getProperty('j:serverNameAliases').values*.string as List
        if (aliases.remove(host)) {
            site.setProperty('j:serverNameAliases', aliases as String[])
            dirty = true
            out << "removed redundant alias ${host} from ${siteKey}\n"
        }
    }

    if (dirty) {
        session.save()
    }

    out << "resolve(${host}) -> " + JahiaSitesService.instance.getSiteByServerName(host, session)?.siteKey + "\n"
    out << "resolve(localhost) -> " + JahiaSitesService.instance.getSiteByServerName('localhost', session)?.siteKey + "\n"
    return null
} as JCRCallback)

new File('/tmp/slp-site-host.txt').text = out.toString()
