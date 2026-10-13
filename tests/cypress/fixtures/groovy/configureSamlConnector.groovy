/*
 * Write the SAML connector configuration for one site, from inside Jahia.
 *
 * WHY HERE AND NOT ON THE HOST. The connector needs an SP keystore and the IdP metadata. Both
 * used to be produced by saml-idp/setup.sh on the developer's machine (host keytool + curl), which
 * the CI path cannot do: the Cypress container has no JDK. Jahia always has one, and it is on the
 * same docker network as the IdP, so doing the work here gives the manual fixture and the Cypress
 * specs a single implementation.
 *
 * WHY A KEYSTORE AT ALL. SAML2Util.validateSettings() can generate one, but only by mutating the
 * in-memory ConnectorConfig. A .cfg dropped in karaf/etc is read-only to Felix, so that keystore is
 * never persisted and the next request dies with "keystoreResource cannot be null". The keystore
 * and its passphrases are generated fresh whenever the .cfg is (re)written and only ever stored in
 * it -- no secret lives in the repository or on the host.
 *
 * IDEMPOTENT. An existing .cfg is left untouched when everything but the keystore and its
 * passphrases already matches. A Cypress spec's before() can run twice (Cypress reloads a spec
 * whose test visits another origin), and rewriting with a new keystore at that point would swap
 * the SP configuration under a login that is already in flight.
 *
 * Placeholders, substituted by saml-idp/setup.sh (sed) and by the Cypress specs
 * (cy.executeGroovy replacements):
 *   @@SITE@@              site the connector is bound to (factory instance key)
 *   @@SP_ENTITY_ID@@      SAML relying-party identifier; must match the IdP client id
 *   @@SP_BASE_URL@@       scheme://host:port the BROWSER uses for the site; the ACS URL is built
 *                         from it, so it must be the site's j:serverName
 *   @@IDP_METADATA_URL@@  where JAHIA fetches the IdP metadata -- a container-network address,
 *                         which need not be the address the browser uses
 *   @@POST_LOGIN_PATH@@   where the valve sends the user after a successful callback
 *   @@INCOMING_TARGET_URL@@  the callback path, appended to @@SP_BASE_URL@@ to make the ACS URL.
 *                         Plain /home.callback.saml when the host is one of the site's names (the
 *                         valve then finds the site from the host); a host that NO site declares
 *                         must name it, e.g. /home.callback.saml?siteKey=systemsite
 */
import java.nio.file.Files
import java.nio.file.StandardCopyOption

def siteKey = '@@SITE@@'
def spEntityId = '@@SP_ENTITY_ID@@'
def spBaseUrl = '@@SP_BASE_URL@@'
def idpMetadataUrl = '@@IDP_METADATA_URL@@'
def postLoginPath = '@@POST_LOGIN_PATH@@'
def incomingTargetUrl = '@@INCOMING_TARGET_URL@@'

final int METADATA_CONNECT_TIMEOUT_MS = 5000
final int METADATA_READ_TIMEOUT_MS = 10000
final int KEYTOOL_TIMEOUT_MS = 60000

// The site key becomes part of a file name in karaf/etc: refuse anything that is not a single,
// plain path segment.
if (!(siteKey ==~ /[A-Za-z0-9][A-Za-z0-9_-]*/)) {
    throw new IllegalArgumentException("Refusing unsafe site key: ${siteKey}")
}
// SAML2Filter matches *.saml and dispatches on the suffix (connect/callback/metadata), so the path
// part must end in callback.saml; only a siteKey query parameter may follow.
if (!(incomingTargetUrl ==~ /\/([A-Za-z0-9_-]+\.)*callback\.saml(\?siteKey=[A-Za-z0-9][A-Za-z0-9_-]*)?/)) {
    throw new IllegalArgumentException("Refusing incoming target URL: ${incomingTargetUrl}")
}

def etcDir = new File(System.getProperty('karaf.etc') ?: '/var/jahia/karaf/etc')
def target = new File(etcDir, "org.jahia.modules.auth-${siteKey}.cfg")
def out = new StringBuilder()

// ------------------------------------------------------------------ IdP metadata
def metadata = new URL(idpMetadataUrl).getText(
        connectTimeout: METADATA_CONNECT_TIMEOUT_MS, readTimeout: METADATA_READ_TIMEOUT_MS)
if (!metadata.contains('EntityDescriptor')) {
    throw new IllegalStateException("No SAML EntityDescriptor at ${idpMetadataUrl}")
}
def metadataB64 = metadata.getBytes('UTF-8').encodeBase64().toString()
out << "fetched IdP metadata from ${idpMetadataUrl} (${metadata.length()} chars)\n"

// ------------------------------------------------------------------ user mapper
// Without a mapper the assertion is accepted and the callback redirects normally, but NO Jahia
// session is created -- a silent no-op (SAML2Filter.handleCallback -> config.getMappers()).
// ssoLoginId is the only mandatory field.
//
// Felix .cfg ESCAPING: a value starting with [ is parsed as an array and " starts a quoted token,
// so the leading bracket and every inner quote are backslash-escaped.
def mappingJson = '[{"mapper":{"name":"ssoLoginId"},"connector":{"name":"username"}},' +
        '{"mapper":{"name":"j:email"},"connector":{"name":"email"}},' +
        '{"mapper":{"name":"j:firstName"},"connector":{"name":"firstName"}},' +
        '{"mapper":{"name":"j:lastName"},"connector":{"name":"lastName"}}]'
def mappingCfg = '\\' + mappingJson.replace('"', '\\"')

// ------------------------------------------------------------------ unchanged?
// java.util.Properties reads the same escapes Felix does, so the mapping compares unescaped.
def isUnchanged = false
if (target.exists()) {
    def current = new Properties()
    target.withInputStream { current.load(it) }
    isUnchanged = current.getProperty('siteKey') == siteKey &&
            current.getProperty('Saml.relyingPartyIdentifier') == spEntityId &&
            current.getProperty('Saml.serverLocation') == spBaseUrl &&
            current.getProperty('Saml.postLoginPath') == postLoginPath &&
            current.getProperty('Saml.incomingTargetUrl') == incomingTargetUrl &&
            current.getProperty('Saml.identityProviderMetadata') == metadataB64 &&
            current.getProperty('Saml.mappers.jcrOAuthProvider.mapping') == mappingJson &&
            current.getProperty('Saml.keyStore') != null
}

if (isUnchanged) {
    out << "unchanged: ${target} already matches, keystore kept\n"
} else {
    // -------------------------------------------------------------- SP keystore
    def keystorePass = UUID.randomUUID().toString()
    def keyPass = UUID.randomUUID().toString()
    def workDir = Files.createTempDirectory('slp-saml-sp').toFile()
    def keystoreFile = new File(workDir, 'sp-keystore.jks')
    try {
        def keytool = new File(System.getProperty('java.home'), 'bin/keytool').path
        def proc = [keytool, '-genkeypair', '-noprompt',
                    '-alias', spEntityId, '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650',
                    '-keystore', keystoreFile.path, '-storetype', 'JKS',
                    '-storepass', keystorePass, '-keypass', keyPass,
                    '-dname', "CN=${spEntityId}, OU=saml-login-provider tests, O=Jahia, C=FR".toString()].execute()
        def stderr = new StringBuilder()
        proc.consumeProcessErrorStream(stderr)
        proc.waitForOrKill(KEYTOOL_TIMEOUT_MS)
        if (proc.exitValue() != 0 || !keystoreFile.exists()) {
            throw new IllegalStateException("keytool failed (exit ${proc.exitValue()}): ${stderr}")
        }
        def keystoreB64 = keystoreFile.bytes.encodeBase64().toString()
        out << "generated a fresh SP keystore for ${spEntityId}\n"

        // ---------------------------------------------------------- the .cfg
        // Factory PID org.jahia.modules.auth, one instance per site, keyed by siteKey.
        def cfg = """\
siteKey = ${siteKey}
Saml.enabled = true
Saml.relyingPartyIdentifier = ${spEntityId}
Saml.serverLocation = ${spBaseUrl}
Saml.incomingTargetUrl = ${incomingTargetUrl}
Saml.postLoginPath = ${postLoginPath}
Saml.identityProviderMetadata = ${metadataB64}
Saml.keyStore = ${keystoreB64}
Saml.keyStoreType = JKS
Saml.keyStoreAlias = ${spEntityId}
Saml.keyStorePass = ${keystorePass}
Saml.privateKeyPass = ${keyPass}
Saml.maximumAuthenticationLifetime = 86400
Saml.forceAuth = false
Saml.passive = false
Saml.signAuthnRequest = false
Saml.requireSignedAssertions = true
Saml.requireSignedResponses = false
Saml.bindingType = urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST
Saml.mappers.jcrOAuthProvider.enabled = true
Saml.mappers.jcrOAuthProvider.mapping = ${mappingCfg}
"""

        // Write next to the target under a name FileInstall ignores, then move it into place in
        // one step, so Felix never reads a half-written configuration.
        def tmp = new File(etcDir, ".org.jahia.modules.auth-${siteKey}.cfg.tmp")
        tmp.setText(cfg, 'UTF-8')
        Files.move(tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        out << "wrote ${target} (ACS ${spBaseUrl}${incomingTargetUrl})\n"
    } finally {
        workDir.deleteDir()
    }
}

new File('/tmp/slp-saml-connector.txt').text = out.toString()
