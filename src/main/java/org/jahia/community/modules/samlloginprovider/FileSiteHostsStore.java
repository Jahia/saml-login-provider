package org.jahia.community.modules.samlloginprovider;

import org.osgi.framework.Constants;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedServiceFactory;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Dictionary;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link SiteHostsStore} backed by one file-based OSGi factory configuration per site:
 * {@code <karaf.etc>/org.jahia.community.samlloginprovider.site-<siteKey>.cfg}.
 *
 * <p>Felix FileInstall turns each file into a factory configuration and ConfigurationAdmin hands
 * it to {@link #updated} (startup, manual edits) or {@link #deleted}; {@link #save} writes or
 * removes the file. A file in {@code karaf/etc} survives restarts and bundle reinstalls, unlike a
 * factory configuration created through the ConfigurationAdmin API, which is bundle-scoped and
 * dropped with the bundle.
 *
 * <p>{@link #save} also updates the in-memory view immediately, so a saved list applies to the
 * very next request instead of whenever FileInstall polls; FileInstall's later {@link #updated}
 * call then delivers the same values. Writes and ConfigurationAdmin callbacks are serialized, so
 * the file and the in-memory view cannot drift apart under concurrent saves.
 *
 * <p>A file only ever configures the site its name says: {@link #updated} rejects a file whose
 * {@code siteKey} does not match its name, and {@link #save} refuses to overwrite or delete a file
 * that holds another site's key. Two site keys differing only by case (both valid in Jahia) map to
 * the same file on a case-insensitive filesystem; without these checks one site's admin could
 * silently overwrite or remove the other site's configuration.
 */
@Component(service = {SiteHostsStore.class, ManagedServiceFactory.class}, immediate = true,
        property = Constants.SERVICE_PID + "=" + FileSiteHostsStore.FACTORY_PID)
public class FileSiteHostsStore implements SiteHostsStore, ManagedServiceFactory {

    /** Factory PID; each site's file is {@code <FACTORY_PID>-<siteKey>.cfg}. */
    public static final String FACTORY_PID = "org.jahia.community.samlloginprovider.site";

    static final String SITE_KEY_PROPERTY = "siteKey";
    static final String HOSTS_PROPERTY = "fqdn";
    /** URL-encoded, see {@link ButtonLabel}. */
    static final String BUTTON_LABEL_PROPERTY = "buttonLabel";
    /** Set by Felix FileInstall on configurations it creates from a file. */
    private static final String FILEINSTALL_FILENAME_PROPERTY = "felix.fileinstall.filename";

    private static final Logger LOGGER = LoggerFactory.getLogger(FileSiteHostsStore.class);
    private static final String KARAF_ETC_PROPERTY = "karaf.etc";

    private final Path etcDir;
    /** ConfigurationAdmin PID -> site key, to know which site a {@link #deleted} call removes. */
    private final Map<String, String> siteKeyByPid = new ConcurrentHashMap<>();
    /** Site key -> immutable host list. Absent = no host. */
    private final Map<String, List<String>> hostsBySite = new ConcurrentHashMap<>();
    /** Site key -> custom sign-in button text. Absent = the default label. */
    private final Map<String, String> buttonLabelBySite = new ConcurrentHashMap<>();

    public FileSiteHostsStore() {
        this(Paths.get(System.getProperty(KARAF_ETC_PROPERTY, "etc")));
    }

    FileSiteHostsStore(Path etcDir) {
        this.etcDir = etcDir;
    }

    @Override
    public List<String> hosts(String siteKey) {
        return siteKey == null ? List.of() : hostsBySite.getOrDefault(siteKey, List.of());
    }

    @Override
    public String buttonLabel(String siteKey) {
        return siteKey == null ? "" : buttonLabelBySite.getOrDefault(siteKey, "");
    }

    @Override
    public synchronized void save(String siteKey, List<String> hosts, String buttonLabel) throws IOException {
        requireValidSiteKey(siteKey);
        if (!HostList.isValid(hosts)) {
            throw new IllegalArgumentException("Invalid host list");
        }
        // "Keep" is resolved here, under the same lock as the write: never a stale read by the caller
        String label = buttonLabel == null ? buttonLabel(siteKey) : ButtonLabel.normalize(buttonLabel);
        if (!ButtonLabel.isValid(label)) {
            throw new IllegalArgumentException("Invalid button label");
        }
        Path target = etcDir.resolve(fileNameOf(siteKey));
        requireOwnedBy(target, siteKey);
        if (hosts.isEmpty() && label.isEmpty()) {
            Files.deleteIfExists(target);
        } else {
            writeAtomically(target, siteKey, HostList.canonical(hosts), label);
        }
        apply(siteKey, hosts, label);
    }

    @Override
    public String getName() {
        return "saml-login-provider per-site hosts";
    }

    @Override
    public synchronized void updated(String pid, Dictionary<String, ?> properties) throws ConfigurationException {
        Object siteKeyValue = properties.get(SITE_KEY_PROPERTY);
        String siteKey = siteKeyValue == null ? null : siteKeyValue.toString();
        if (!WebUtils.isValidSiteKey(siteKey)) {
            throw new ConfigurationException(SITE_KEY_PROPERTY, "missing or invalid site key");
        }
        Object fileName = properties.get(FILEINSTALL_FILENAME_PROPERTY);
        if (fileName != null && !fileName.toString().endsWith("/" + fileNameOf(siteKey))) {
            throw new ConfigurationException(SITE_KEY_PROPERTY,
                    "siteKey " + siteKey + " does not match its file " + fileName + "; ignored");
        }
        Object hostsValue = properties.get(HOSTS_PROPERTY);
        List<String> hosts = HostList.parse(hostsValue == null ? "" : hostsValue.toString());
        if (!HostList.isValid(hosts)) {
            // Reject the whole update: a hand-edited file with one bad host must not half-apply.
            throw new ConfigurationException(HOSTS_PROPERTY, "invalid host list for site " + siteKey);
        }
        String label = readButtonLabel(siteKey, properties.get(BUTTON_LABEL_PROPERTY));

        String previousSiteKey = siteKeyByPid.put(pid, siteKey);
        if (previousSiteKey != null && !previousSiteKey.equals(siteKey)) {
            apply(previousSiteKey, List.of(), "");
        }
        apply(siteKey, hosts, label);
        LOGGER.info("SAML login hosts for site {}: {}; button label: {}", siteKey,
                hosts.isEmpty() ? "(none)" : hosts, label.isEmpty() ? "(default)" : label);
    }

    @Override
    public synchronized void deleted(String pid) {
        String siteKey = siteKeyByPid.remove(pid);
        if (siteKey != null) {
            apply(siteKey, List.of(), "");
            LOGGER.info("SAML login configuration of site {} removed", siteKey);
        }
    }

    /** The in-memory view of one site: absent entries mean no host and the default label. */
    private void apply(String siteKey, List<String> hosts, String label) {
        if (hosts.isEmpty()) {
            hostsBySite.remove(siteKey);
        } else {
            hostsBySite.put(siteKey, List.copyOf(hosts));
        }
        if (label.isEmpty()) {
            buttonLabelBySite.remove(siteKey);
        } else {
            buttonLabelBySite.put(siteKey, label);
        }
    }

    /** Rejects the whole update on a bad label, like on a bad host: a file must not half-apply. */
    private static String readButtonLabel(String siteKey, Object stored) throws ConfigurationException {
        if (stored == null) {
            return "";
        }
        if (!(stored instanceof String)) {
            // e.g. a hand-written "[a,b]", which FileInstall's typed parsing turns into an array
            throw new ConfigurationException(BUTTON_LABEL_PROPERTY, "not a text value for site " + siteKey);
        }
        String label;
        try {
            label = ButtonLabel.normalize(ButtonLabel.decode((String) stored));
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(BUTTON_LABEL_PROPERTY, "invalid URL encoding for site " + siteKey, e);
        }
        if (!ButtonLabel.isValid(label)) {
            throw new ConfigurationException(BUTTON_LABEL_PROPERTY, "invalid button label for site " + siteKey);
        }
        return label;
    }

    /**
     * Writes next to the target under a name FileInstall ignores, then moves it into place in one
     * step, so FileInstall never reads a half-written file.
     */
    private void writeAtomically(Path target, String siteKey, String canonicalHosts, String label)
            throws IOException {
        // A plain site key and hosts made of [a-z0-9.-,] need no escaping; the label is URL-encoded.
        String content = "# saml-login-provider: hosts of site " + siteKey + " sent straight to SAML login\n"
                + SITE_KEY_PROPERTY + " = " + siteKey + "\n"
                + HOSTS_PROPERTY + " = " + canonicalHosts + "\n"
                + (label.isEmpty() ? "" : "# sign-in button text, URL-encoded (UTF-8)\n"
                + BUTTON_LABEL_PROPERTY + " = " + ButtonLabel.encode(label) + "\n");
        Files.createDirectories(etcDir);
        Path temp = Files.createTempFile(etcDir, "." + FACTORY_PID + "-" + siteKey + "-", ".tmp");
        try {
            Files.write(temp, content.getBytes(StandardCharsets.UTF_8));
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String fileNameOf(String siteKey) {
        return FACTORY_PID + "-" + siteKey + ".cfg";
    }

    /** Refuses to touch a file that configures a different site (case-only collision, hand edit). */
    private static void requireOwnedBy(Path target, String siteKey) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        Properties existing = new Properties();
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            existing.load(reader);
        }
        String owner = existing.getProperty(SITE_KEY_PROPERTY);
        if (owner != null && !owner.equals(siteKey)) {
            throw new IOException("The configuration file of site " + siteKey + " belongs to another site");
        }
    }

    private static void requireValidSiteKey(String siteKey) {
        // The site key becomes part of a file name in karaf/etc: a single, plain path segment only.
        if (!WebUtils.isValidSiteKey(siteKey)) {
            throw new IllegalArgumentException("Invalid site key");
        }
    }
}
