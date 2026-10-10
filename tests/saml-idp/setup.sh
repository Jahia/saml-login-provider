#!/bin/bash
#
# Stand up a complete SAML round trip against the already-running tests/ stack, so that
# saml-login-provider can be exercised past the redirect it emits.
#
# Prerequisite: tests/ci.startup.sh has run and the `jahia` container is up on tests_stack.
#
# Idempotent: re-running re-deploys the modules, re-imports nothing (Keycloak keeps its realm
# unless you `docker compose down -v`), and rewrites the connector config.
set -euo pipefail

cd "$(dirname "$0")"

# Configuration comes from .env, or from an env file named by SAML_IDP_ENV_FILE, or from
# variables already exported in the shell -- whichever is present. The last form exists because
# some setups refuse to let tooling write a .env into the working tree.
ENV_FILE=${SAML_IDP_ENV_FILE:-.env}
if [[ -f $ENV_FILE ]]; then
    # shellcheck disable=SC1090
    set -a; source "$ENV_FILE"; set +a
fi

MISSING=()
for var in KEYCLOAK_ADMIN KEYCLOAK_ADMIN_PASSWORD SAML_TEST_USER SAML_TEST_USER_PASSWORD \
           SAML_KEYSTORE_PASS SAML_PRIVATE_KEY_PASS; do
    [[ -n ${!var:-} ]] || MISSING+=("$var")
done
if (( ${#MISSING[@]} )); then
    echo "ERROR: missing configuration: ${MISSING[*]}" >&2
    echo "       Copy .env.example to .env and fill it in, or export the variables," >&2
    echo "       or point SAML_IDP_ENV_FILE at a file that defines them." >&2
    exit 1
fi

# The connector is bound to digitall, and the stack is made to reach it.
#
# SAML2Util.findSiteKeyForRequest resolves the site with
# getSiteByServerName(request.getServerName()), and saml-login-provider redirects to
# /home.connect.saml with no siteKey parameter. On a stock tests/ stack BOTH systemsite and
# digitall carry j:serverName = localhost, systemsite wins, and every other host resolves to
# null -- there is no fallback to the default site. So digitall is unreachable by any hostname
# until one is mapped to it, and a connector bound to it would never be consulted.
#
# digitall is not missing a server name -- it ships with the SAME one systemsite has, so the
# lookup has two candidates, systemsite wins, and digitall's server name is dead config.
# configure-site-host.groovy gives digitall its own distinct j:serverName (= SITE_HOST), which is
# the field behind Administration -> Sites -> digitall -> Web project settings -> Server name.
# systemsite is left alone, so localhost -> systemsite still holds. You then browse the SP at
# SITE_HOST.
#
# SITE_HOST must be a REAL hostname, never 'localhost' or '127.0.0.1'. Jahia rewrites site URLs
# to ABSOLUTE ones built from j:serverName, so a loopback literal is baked into every link on the
# site (href="http://127.0.0.1:8080/home.html"), which is meaningless to any client that is not
# this machine. 'digitall.localhost' is a real name that still needs no hosts-file entry, since
# browsers and glibc/systemd-resolved both map *.localhost to loopback.
: "${SITE_KEY:=digitall}"
: "${SITE_HOST:=digitall.localhost}"
: "${POST_LOGIN_PATH:=/home.html}"
: "${SUPER_USER_PASSWORD:=root1234}"
: "${JAHIA_ADMIN_USER:=root}"
# The SP base URL. Must use SITE_HOST, because that is the host that resolves to SITE_KEY.
: "${JAHIA_PUBLIC_URL:=http://$SITE_HOST:8080}"
# Jahia answers on localhost regardless; used for health checks and the provisioning API.
JAHIA_LOCAL_URL=http://localhost:8080
REALM=jahia-saml
SP_ENTITY_ID=jahia-digitall
KC_URL=http://localhost:8180

# These three must come from the SAME generation. They wire to each other through
# org.jahia.modules.jahiaauth.service, and the ranges are narrow:
#
#   jahia-authentication 2.1.0  exports  2.1.0
#   saml-authentication-valve   imports  [2.0,3)
#   jcr-auth-provider           imports  [2.0,3)
#
# Mixing generations leaves a bundle unresolved with no visible error beyond
# /home.connect.saml continuing to answer 400 -- e.g. the previous generation's valve 3.1.1
# imports [1.7,2) and cannot wire to 2.x at all. Jahia-Required-Version does NOT capture this;
# it is about the platform, not the package wiring. Check both ends before bumping any of them.
M2=~/.m2/repository/org/jahia/modules
MODULES=(
    "$M2/jahia-authentication/2.1.0/jahia-authentication-2.1.0.jar"
    "$M2/jcr-auth-provider/4.2.0/jcr-auth-provider-4.2.0.jar"
    "$M2/saml-authentication-valve/4.1.1/saml-authentication-valve-4.1.1.jar"
)

log() { echo "== $*"; }

# ---------------------------------------------------------------- preconditions
if ! docker ps --format '{{.Names}}' | grep -qx jahia; then
    echo "ERROR: the 'jahia' container is not running. Run ../ci.startup.sh first." >&2
    exit 1
fi
for jar in "${MODULES[@]}"; do
    [[ -f "$jar" ]] || { echo "ERROR: missing module jar $jar" >&2; exit 1; }
done

# ---------------------------------------------------------------- 1. auth modules
log "Deploying the authentication modules into Jahia"
for jar in "${MODULES[@]}"; do
    echo "   $(basename "$jar")"
    docker cp "$jar" jahia:/var/jahia/modules/
done

log "Waiting for saml-authentication-valve to serve *.saml (up to 180s)"
for _ in $(seq 1 60); do
    # Until the valve starts, /home.connect.saml is just an unknown page: 404. Once the filter
    # is wired it answers 400 (no connector for this site), 200 (the HTTP-POST auto-submit form)
    # or 302 (redirect binding). Test for "not 404" rather than allowlisting codes -- an
    # allowlist of 400/302 spins for the full timeout the moment a connector is actually working.
    code=$(curl -s -o /dev/null -w '%{http_code}' "$JAHIA_LOCAL_URL/home.connect.saml" || true)
    if [[ -n "$code" && "$code" != "404" && "$code" != "000" ]]; then
        echo "   valve is serving (HTTP $code)"
        break
    fi
    sleep 3
done

# ---------------------------------------------------------------- 1a. enable on the site
# Dropping a jar in /var/jahia/modules INSTALLS and STARTS the bundle, but does not enable the
# module on any site -- j:installedModules on /sites/<site> is untouched. The SAML2Filter is a
# global servlet filter and works either way, which is exactly what makes this easy to miss: the
# login flow succeeds while the module appears "not enabled on the websites".
#
# What does need site enablement is the per-site settings UI. jahia-authentication contributes
# the Authentication section, and saml-authentication-valve / jcr-auth-provider contribute the
# jmix:authConnectorSettingView views (saml2nt:samlAuthView, joant:jcrOAuthView) where the
# connector and its mapper are wired. Without this step that screen is simply absent.
#
# saml-login-provider itself is deliberately NOT enabled here: it is a server-level module whose
# admin route lives under Server settings and whose permission is checked on the JCR root node.
log "Enabling the authentication modules on site $SITE_KEY"
cat > .enable.run.json <<EOF
[
  {"enable":"jahia-authentication","site":"$SITE_KEY"},
  {"enable":"jcr-auth-provider","site":"$SITE_KEY"},
  {"enable":"saml-authentication-valve","site":"$SITE_KEY"}
]
EOF
curl -sf -u "$JAHIA_ADMIN_USER:$SUPER_USER_PASSWORD" \
    -F script=@.enable.run.json "$JAHIA_LOCAL_URL/modules/api/provisioning" -o /dev/null
rm -f .enable.run.json
echo "   enabled on $SITE_KEY"

# ---------------------------------------------------------------- 1b. site hostname
# Without this the connector below is bound to a site no request can resolve to. See the long
# comment on SITE_KEY above and the header of configure-site-host.groovy.
log "Setting server name $SITE_HOST on site $SITE_KEY"
sed -e "s/@@HOST@@/$SITE_HOST/g" -e "s/@@SITE@@/$SITE_KEY/g" \
    configure-site-host.groovy > .configure-site-host.run.groovy
echo '[{"executeScript":".configure-site-host.run.groovy"}]' > .provisioning.run.json
curl -sf -u "$JAHIA_ADMIN_USER:$SUPER_USER_PASSWORD" \
    -F script=@.provisioning.run.json \
    -F file=@.configure-site-host.run.groovy \
    "$JAHIA_LOCAL_URL/modules/api/provisioning" -o /dev/null
rm -f .provisioning.run.json .configure-site-host.run.groovy
docker exec jahia cat /tmp/slp-site-host.txt | sed 's/^/   /'

# ---------------------------------------------------------------- 2. Keycloak
log "Starting the Keycloak IdP"
docker compose up -d

log "Waiting for Keycloak to answer on $KC_URL"
KC_READY=
for _ in $(seq 1 60); do
    if curl -sf -o /dev/null "$KC_URL/realms/$REALM/protocol/saml/descriptor"; then
        KC_READY=1
        echo "   realm $REALM is up"
        break
    fi
    # Fail fast on a container that died rather than burning the full timeout. The realm
    # importer uses a strict deserializer: one unknown field anywhere in the realm JSON (an
    # attempted "_comment" key, say -- JSON has no comments) aborts startup with exit 1.
    if [[ "$(docker inspect -f '{{.State.Running}}' slp-saml-idp-keycloak 2>/dev/null)" == "false" ]]; then
        break
    fi
    sleep 3
done
if [[ -z $KC_READY ]]; then
    echo "ERROR: Keycloak did not come up. Last lines:" >&2
    docker logs --tail 15 slp-saml-idp-keycloak 2>&1 | sed 's/^/   /' >&2
    exit 1
fi

log "Setting the test user's password"
docker exec slp-saml-idp-keycloak /opt/keycloak/bin/kcadm.sh config credentials \
    --server "$KC_URL" --realm master \
    --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null
docker exec slp-saml-idp-keycloak /opt/keycloak/bin/kcadm.sh set-password \
    -r "$REALM" --username "$SAML_TEST_USER" --new-password "$SAML_TEST_USER_PASSWORD" >/dev/null
echo "   $SAML_TEST_USER is ready"

# ---------------------------------------------------------------- 3. connector config
log "Fetching the IdP metadata and building the connector configuration"
curl -sf "$KC_URL/realms/$REALM/protocol/saml/descriptor" -o idp-metadata.xml
IDP_B64=$(base64 -w0 < idp-metadata.xml)

# The SP keystore has to be supplied, not left to the module to generate.
#
# SAML2Util.validateSettings() does create one when keyStore is absent, but it does so by
# mutating the in-memory ConnectorConfig. A .cfg dropped in karaf/etc is read-only (Felix logs
# "Adding READ_ONLY attribute to configuration" / "File is not writeable"), so that generated
# keystore is never persisted; the next request re-reads the config without it and pac4j dies
# with "keystoreResource cannot be null". Auto-generation only works for connectors created
# through the admin UI, which are JCR-backed and writable.
if [[ ! -f sp-keystore.jks ]]; then
    log "Generating the SP keystore"
    keytool -genkeypair -noprompt \
        -alias "$SP_ENTITY_ID" -keyalg RSA -keysize 2048 -validity 3650 \
        -keystore sp-keystore.jks -storetype JKS \
        -storepass "$SAML_KEYSTORE_PASS" -keypass "$SAML_PRIVATE_KEY_PASS" \
        -dname "CN=$SP_ENTITY_ID, OU=saml-login-provider tests, O=Jahia, C=FR" 2>/dev/null
fi
KS_B64=$(base64 -w0 < sp-keystore.jks)

# Factory PID org.jahia.modules.auth, one instance per site, keyed by the siteKey property.
# jahia-authentication's SettingsServiceImpl is the ManagedServiceFactory behind it, and its
# updated() calls SAML2Util.validateSettings(), which GENERATES the SP keystore when absent --
# so there is deliberately no keytool step here.
#
# identityProviderMetadata and keyStore are binary properties: ConnectorConfig reads them with
# Base64.getDecoder().decode(getProperty(...)), so base64 in a flat .cfg is exactly right.
#
# incomingTargetUrl must end in callback.saml: SAML2Filter matches *.saml and dispatches on the
# suffix (connect/callback/metadata). The sample .cfg shipped inside the module still carries the
# pre-3.x /cms/login.SAML.incoming value, which this filter would never see.
CFG=org.jahia.modules.auth-$SITE_KEY.cfg
cat > "$CFG" <<EOF
siteKey = $SITE_KEY
Saml.enabled = true
Saml.relyingPartyIdentifier = $SP_ENTITY_ID
Saml.serverLocation = $JAHIA_PUBLIC_URL
Saml.incomingTargetUrl = /home.callback.saml
Saml.postLoginPath = $POST_LOGIN_PATH
Saml.identityProviderMetadata = $IDP_B64
Saml.keyStore = $KS_B64
Saml.keyStoreType = JKS
Saml.keyStoreAlias = $SP_ENTITY_ID
Saml.keyStorePass = $SAML_KEYSTORE_PASS
Saml.privateKeyPass = $SAML_PRIVATE_KEY_PASS
Saml.maximumAuthenticationLifetime = 86400
Saml.forceAuth = false
Saml.passive = false
Saml.signAuthnRequest = false
Saml.requireSignedAssertions = true
# New in the 4.x valve: also demand a signature on the response envelope, not just the
# assertion. Our Keycloak client signs both (saml.server.signature + saml.assertion.signature),
# so flipping this to true is a valid stricter test; false is the module's own default.
Saml.requireSignedResponses = false
Saml.bindingType = urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST
EOF

docker cp "$CFG" jahia:/var/jahia/karaf/etc/
echo "   installed $CFG (Felix FileInstall picks it up within a few seconds)"

cat <<EOF

== Done. What is wired:

   Keycloak IdP     $KC_URL/admin   ($KEYCLOAK_ADMIN)
   Realm            $REALM
   SAML end user    $SAML_TEST_USER
   Jahia SP         $SP_ENTITY_ID  ->  $JAHIA_PUBLIC_URL/home.callback.saml
   Connector        site '$SITE_KEY', from $CFG
   Site host        $SITE_HOST resolves to $SITE_KEY (localhost still resolves to systemsite)

   Browse the SP at $JAHIA_PUBLIC_URL -- NOT at http://localhost:8080, which resolves to
   systemsite and has no SAML connector.

== One manual step remains -- the user mapper.

   The valve hands the SAML profile to whichever mappers the connector has configured
   (SAML2Filter.handleCallback -> config.getMappers()). With no mapper, login silently
   completes without creating a Jahia session. Wire it once in the UI:

     $JAHIA_PUBLIC_URL/jahia/administration  ->  $SITE_KEY  ->  Authentication  ->  SAML
       -> 'JCR Auth provider' -> map the connector's 'username' field to 'Login username'
          (ssoLoginId, the only mandatory one) -> Save

== Then, to make saml-login-provider actually redirect you:

   $JAHIA_PUBLIC_URL/jahia/administration/samlLoginProvider  ->  host list: $SITE_HOST

   The module matches request.getServerName(), so it must be the host you browse with
   ($SITE_HOST) -- not 'jahia', which is only correct from inside the container network, and
   not 'localhost' unless you also want systemsite enforced. Log out, hit a protected page,
   and you should land on Keycloak.

== If you lock yourself out (host configured but SAML broken):

   docker exec jahia sh -c 'echo "fqdn=" > /var/jahia/karaf/etc/org.jahia.community.samlloginprovider.cfg'
EOF
