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
for var in KEYCLOAK_ADMIN KEYCLOAK_ADMIN_PASSWORD SAML_TEST_USER SAML_TEST_USER_PASSWORD; do
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
# ../cypress/fixtures/groovy/configureSiteHost.groovy gives digitall its own distinct j:serverName (= SITE_HOST), which is
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
: "${JAHIA_PORT:=8080}"
# Jahia answers on localhost regardless; used for health checks and the provisioning API.
JAHIA_LOCAL_URL=http://localhost:$JAHIA_PORT
REALM=jahia-saml
KC_URL=http://localhost:8180

# Every site that gets a SAML connector, as siteKey=host. The connector is reached at whatever host
# resolves to its site, and its SP URL (hence the ACS URL Keycloak posts the assertion back to) is
# built from that same host -- so each visitor returns to the host they started on.
#
#   SITE_KEY=SITE_HOST   the content site: given its own server name (above), and the host that goes
#                        into that site's saml-login-provider host list, i.e. redirected to SAML
#                        outright.
#   systemsite=localhost systemsite keeps its stock server name and gets NO host list: the list is
#                        per site, so an unconfigured systemsite keeps Jahia's stock login on
#                        localhost. The connector is there so that SAML works on localhost the moment
#                        systemsite is given a list (Administration -> systemsite -> SAML auto-login),
#                        and for /home.connect.saml requested directly.
#
# The SP entity ID is jahia-<siteKey>; realm/jahia-saml-realm.json declares one client per site.
SYSTEMSITE_HOST=localhost
SAML_SITES=("$SITE_KEY=$SITE_HOST" "systemsite=$SYSTEMSITE_HOST")
site_url() { echo "http://$1:$JAHIA_PORT"; }
# Where JAHIA fetches the IdP metadata: the Keycloak container over tests_stack. The metadata
# itself still advertises KC_URL, which is what the browser on this machine follows.
KC_INTERNAL_URL=http://slp-saml-idp-keycloak:8180
# Shared with the Cypress specs, which drive the same setup in CI.
GROOVY_DIR=../cypress/fixtures/groovy

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
# it is about the platform, not the package wiring. Check both ends before bumping any of them,
# and keep ../assets/provisioning.yml (which installs the same three for CI) in step.
M2=~/.m2/repository/org/jahia/modules
MODULES=(
    "$M2/jahia-authentication/2.1.0/jahia-authentication-2.1.0.jar"
    "$M2/jcr-auth-provider/4.2.0/jcr-auth-provider-4.2.0.jar"
    "$M2/saml-authentication-valve/4.1.1/saml-authentication-valve-4.1.1.jar"
)

log() { echo "== $*"; }

# Run one of the shared Groovy scripts inside Jahia through the provisioning API, then print the
# result file it writes inside the container.
#
#   run_groovy <script> <result file in the jahia container> NAME=value ...
#
# Each NAME=value substitutes @@NAME@@. Values are escaped for sed, and a single quote is refused
# because the scripts embed the values in single-quoted Groovy literals.
#
# The uploaded copy must keep its .groovy extension: executeScript picks the script engine from it.
# The result file is deleted first and required afterwards -- the provisioning call itself can
# succeed without the script having run, and a leftover file would otherwise pass for this run's.
run_groovy() {
    local script=$1 result=$2; shift 2
    local sed_args=() pair name value
    for pair in "$@"; do
        name=${pair%%=*}; value=${pair#*=}
        if [[ $value == *"'"* ]]; then
            echo "ERROR: $name must not contain a single quote: $value" >&2
            exit 1
        fi
        value=$(printf '%s' "$value" | sed -e 's/[\\&|]/\\&/g')
        sed_args+=(-e "s|@@${name}@@|${value}|g")
    done
    local upload=".run.$script"
    sed "${sed_args[@]}" "$GROOVY_DIR/$script" > "$upload"
    echo "[{\"executeScript\":\"$upload\"}]" > .provisioning.run.json
    docker exec jahia rm -f "$result"
    curl -sf -u "$JAHIA_ADMIN_USER:$SUPER_USER_PASSWORD" \
        -F script=@.provisioning.run.json \
        -F file=@"$upload" \
        "$JAHIA_LOCAL_URL/modules/api/provisioning" -o /dev/null
    rm -f .provisioning.run.json "$upload"
    if ! docker exec jahia cat "$result" 2>/dev/null | sed 's/^/   /'; then
        echo "ERROR: $script did not run (no $result); see 'docker logs jahia'." >&2
        exit 1
    fi
}

# ---------------------------------------------------------------- preconditions
if ! docker ps --format '{{.Names}}' | grep -x jahia > /dev/null; then
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
ENABLE_OPS=()
for entry in "${SAML_SITES[@]}"; do
    site=${entry%%=*}
    log "Enabling the authentication modules on site $site"
    for module in jahia-authentication jcr-auth-provider saml-authentication-valve; do
        ENABLE_OPS+=("{\"enable\":\"$module\",\"site\":\"$site\"}")
    done
done
(IFS=,; echo "[${ENABLE_OPS[*]}]") > .enable.run.json
curl -sf -u "$JAHIA_ADMIN_USER:$SUPER_USER_PASSWORD" \
    -F script=@.enable.run.json "$JAHIA_LOCAL_URL/modules/api/provisioning" -o /dev/null
rm -f .enable.run.json
echo "   enabled on ${SAML_SITES[*]%%=*}"

# ---------------------------------------------------------------- 1b. site hostname
# Without this the connector below is bound to a site no request can resolve to. See the long
# comment on SITE_KEY above and the header of configureSiteHost.groovy.
log "Setting server name $SITE_HOST on site $SITE_KEY"
run_groovy configureSiteHost.groovy /tmp/slp-site-host.txt HOST="$SITE_HOST" SITE="$SITE_KEY"

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
kcadm() { docker exec slp-saml-idp-keycloak /opt/keycloak/bin/kcadm.sh "$@"; }
kcadm config credentials --server "$KC_URL" --realm master \
    --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null
kcadm set-password -r "$REALM" --username "$SAML_TEST_USER" --new-password "$SAML_TEST_USER_PASSWORD" >/dev/null
echo "   $SAML_TEST_USER is ready"

# Keycloak imports the realm file only into an EMPTY realm (--import-realm skips one that exists),
# so a Keycloak created before a client was added to realm/jahia-saml-realm.json lacks it, and
# every login through that site's connector would fail at the IdP with "Invalid request".
log "Checking that the realm declares one SP client per site"
for entry in "${SAML_SITES[@]}"; do
    client="jahia-${entry%%=*}"
    if ! kcadm get clients -r "$REALM" -q "clientId=$client" --fields clientId 2>/dev/null | grep -F "\"$client\"" > /dev/null; then
        echo "ERROR: Keycloak has no client $client -- it predates the current realm file." >&2
        echo "       Re-import the realm (it holds nothing else; this script resets the user):" >&2
        echo "         docker compose up -d --force-recreate && ./setup.sh" >&2
        exit 1
    fi
    echo "   $client"
done

# ---------------------------------------------------------------- 3. connector config
# configureSamlConnector.groovy runs INSIDE Jahia: it fetches the IdP metadata over tests_stack,
# generates a fresh SP keystore with Jahia's own keytool (random passphrases, never leaving the
# container), wires the user mapper, and writes org.jahia.modules.auth-<site>.cfg atomically.
# See its header for why the keystore must be supplied and how the mapper value is escaped.
for entry in "${SAML_SITES[@]}"; do
    site=${entry%%=*} host=${entry#*=}
    log "Writing the SAML connector configuration for $site ($(site_url "$host"))"
    run_groovy configureSamlConnector.groovy /tmp/slp-saml-connector.txt \
        SITE="$site" \
        SP_ENTITY_ID="jahia-$site" \
        SP_BASE_URL="$(site_url "$host")" \
        IDP_METADATA_URL="$KC_INTERNAL_URL/realms/$REALM/protocol/saml/descriptor" \
        POST_LOGIN_PATH="$POST_LOGIN_PATH"
done

# Felix FileInstall picks each .cfg up asynchronously. The valve answers 400 for a host until it
# has, then serves the HTTP-POST auto-submit form aimed at the IdP. --resolve pins the host to
# loopback for curl builds that do not map *.localhost themselves.
wait_for_connector() {
    local host=$1 _
    for _ in $(seq 1 20); do
        # The valve HTML-escapes the form action (http&#x3a;&#x2f;&#x2f;...): decode before matching.
        # The IdP URL is checked, not just the form, because while Felix reloads the .cfg a
        # previous connector (e.g. the CI one) still answers with a form aimed at another IdP.
        # Not grep -q: under pipefail its early exit SIGPIPEs sed/curl and turns a match into a
        # failure.
        if curl -s --resolve "$host:$JAHIA_PORT:127.0.0.1" "$(site_url "$host")/home.connect.saml" \
                | sed -e 's/&#x3a;/:/g' -e 's|&#x2f;|/|g' | grep -F "$KC_URL/realms/$REALM/" > /dev/null; then
            echo "   /home.connect.saml on $host posts to the IdP"
            return 0
        fi
        sleep 3
    done
    return 1
}
for entry in "${SAML_SITES[@]}"; do
    host=${entry#*=}
    log "Waiting for the connector to serve $host (up to 60s)"
    if ! wait_for_connector "$host"; then
        echo "ERROR: the connector never came up on $host; NOT enabling the redirect." >&2
        exit 1
    fi
done

# ---------------------------------------------------------------- 4. saml-login-provider
# Only now that the connectors answer: a host in this list without a working connector locks
# every unauthenticated visitor out (see AGENTS.md, Gotchas). The module matches
# request.getServerName(), so this must be the host you browse with.
#
# Through a login session plus a same-host Origin header, not basic auth: Jahia's API security
# filter only lets a hosted-origin session reach GraphQL, and answers anything else with a 200
# "Permission denied" body.
log "Setting the saml-login-provider host list of site $SITE_KEY to $SITE_HOST"
COOKIES=$(mktemp)
trap 'rm -f "$COOKIES"' EXIT
LOGIN_RESULT=$(printf '%s' "$SUPER_USER_PASSWORD" | curl -s -c "$COOKIES" \
    --data-urlencode "username=$JAHIA_ADMIN_USER" --data-urlencode "password@-" --data restMode=true \
    "$JAHIA_LOCAL_URL/cms/login")
if [[ $LOGIN_RESULT != OK ]]; then
    echo "ERROR: could not log in to Jahia as $JAHIA_ADMIN_USER: $LOGIN_RESULT" >&2
    exit 1
fi
FQDN_RESULT=$(curl -sf -b "$COOKIES" \
    -H 'Content-Type: application/json' -H "Origin: $JAHIA_LOCAL_URL" \
    -d "{\"query\":\"mutation { samlLoginProvider { update(siteKey: \\\"$SITE_KEY\\\", fqdn: \\\"$SITE_HOST\\\") { fqdn } } }\"}" \
    "$JAHIA_LOCAL_URL/modules/graphql")
curl -s -o /dev/null -b "$COOKIES" -X POST "$JAHIA_LOCAL_URL/cms/logout"
if ! grep -q "\"fqdn\":\"$SITE_HOST\"" <<<"$FQDN_RESULT"; then
    echo "ERROR: could not set the host list: $FQDN_RESULT" >&2
    exit 1
fi
echo "   host list of $SITE_KEY: $SITE_HOST"

cat <<EOF

== Done. What is wired:

   Keycloak IdP     $KC_URL/admin   ($KEYCLOAK_ADMIN)
   Realm            $REALM
   SAML end user    $SAML_TEST_USER
EOF
for entry in "${SAML_SITES[@]}"; do
    site=${entry%%=*} host=${entry#*=}
    printf '   %-16s %s\n' "Site $site" "$host -> SP jahia-$site, ACS $(site_url "$host")/home.callback.saml"
done
cat <<EOF
   Host list        site $SITE_KEY: $SITE_HOST  ($JAHIA_LOCAL_URL/jahia/administration/$SITE_KEY/samlLoginProvider)
                    other sites: none, so their login is untouched

   Try it, each in a private window, as $SAML_TEST_USER:
     $(site_url "$SITE_HOST")/start   -> straight to Keycloak, back logged in ($SITE_HOST is in the list)
     $(site_url "$SYSTEMSITE_HOST")/start   -> stock Jahia login (systemsite has no host list)

== If you lock yourself out (host configured but SAML broken):

   docker exec jahia sh -c 'echo "fqdn=" > /var/jahia/karaf/etc/org.jahia.community.samlloginprovider.cfg'
EOF
