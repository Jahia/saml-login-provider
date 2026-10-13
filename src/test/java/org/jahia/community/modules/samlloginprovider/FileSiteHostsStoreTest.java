package org.jahia.community.modules.samlloginprovider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.osgi.service.cm.ConfigurationException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSiteHostsStoreTest {

    @TempDir
    Path etcDir;

    private FileSiteHostsStore store;

    @BeforeEach
    void setUp() {
        store = new FileSiteHostsStore(etcDir);
    }

    @Test
    void save_writesOneFactoryCfgPerSite_readableAsProperties() throws IOException {
        // Act
        store.save("digitall", List.of("digitall.example.com", "www.digitall.example.com"));

        // Assert: <factoryPid>-<siteKey>.cfg, which Felix FileInstall turns into a factory config
        Path cfg = etcDir.resolve(FileSiteHostsStore.FACTORY_PID + "-digitall.cfg");
        assertThat(cfg).exists();
        Properties props = new Properties();
        try (Reader reader = Files.newBufferedReader(cfg)) {
            props.load(reader);
        }
        assertThat(props.getProperty("siteKey")).isEqualTo("digitall");
        assertThat(props.getProperty("fqdn")).isEqualTo("digitall.example.com,www.digitall.example.com");
    }

    @Test
    void save_isVisibleImmediately_withoutWaitingForConfigAdmin() throws IOException {
        // Act
        store.save("digitall", List.of("digitall.example.com"));

        // Assert: read-your-writes, before FileInstall has delivered the file to updated()
        assertThat(store.hosts("digitall")).containsExactly("digitall.example.com");
    }

    @Test
    void save_leavesNoTemporaryFileBehind() throws IOException {
        // Act
        store.save("digitall", List.of("digitall.example.com"));

        // Assert: only the target .cfg remains (the temp file is moved into place)
        try (Stream<Path> files = Files.list(etcDir)) {
            assertThat(files).extracting(p -> p.getFileName().toString())
                    .containsExactly(FileSiteHostsStore.FACTORY_PID + "-digitall.cfg");
        }
    }

    @Test
    void save_emptyList_deletesTheSiteConfiguration() throws IOException {
        // Arrange
        store.save("digitall", List.of("digitall.example.com"));

        // Act
        store.save("digitall", List.of());

        // Assert: the site is unconfigured again -- no file, no hosts
        assertThat(etcDir.resolve(FileSiteHostsStore.FACTORY_PID + "-digitall.cfg")).doesNotExist();
        assertThat(store.hosts("digitall")).isEmpty();
    }

    @Test
    void save_otherSite_isUntouched() throws IOException {
        // Arrange
        store.save("digitall", List.of("digitall.example.com"));

        // Act
        store.save("other", List.of("other.example.com"));

        // Assert: per-site isolation
        assertThat(store.hosts("digitall")).containsExactly("digitall.example.com");
        assertThat(store.hosts("other")).containsExactly("other.example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc", "a/b", "", "_hidden", "site key"})
    void save_unsafeSiteKey_isRejectedBeforeAnyFileIsBuilt(String siteKey) {
        // Arrange
        List<String> hosts = List.of("example.com");

        // Act / Assert: the site key becomes part of a file name in karaf/etc
        assertThatThrownBy(() -> store.save(siteKey, hosts))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void save_nullSiteKey_isRejected() {
        List<String> hosts = List.of("example.com");

        assertThatThrownBy(() -> store.save(null, hosts))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void save_invalidHost_isRejected() {
        List<String> hosts = List.of("-bad.example.com");

        assertThatThrownBy(() -> store.save("digitall", hosts))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hosts_unconfiguredSite_isEmpty() {
        assertThat(store.hosts("digitall")).isEmpty();
        assertThat(store.hosts(null)).isEmpty();
    }

    @Test
    void updated_loadsTheSiteFromConfigAdmin() throws ConfigurationException {
        // Act: what Felix FileInstall delivers for an existing .cfg at startup
        store.updated("pid.1", props("digitall", " Digitall.Example.com , www.digitall.example.com "));

        // Assert: parsed like any host list -- trimmed and lower-cased
        assertThat(store.hosts("digitall")).containsExactly("digitall.example.com", "www.digitall.example.com");
    }

    @Test
    void updated_emptyHostList_unconfiguresTheSite() throws ConfigurationException {
        // Arrange
        store.updated("pid.1", props("digitall", "digitall.example.com"));

        // Act
        store.updated("pid.1", props("digitall", ""));

        // Assert
        assertThat(store.hosts("digitall")).isEmpty();
    }

    @Test
    void updated_siteKeyChangedForSamePid_dropsTheOldSite() throws ConfigurationException {
        // Arrange
        store.updated("pid.1", props("digitall", "digitall.example.com"));

        // Act: someone edited the siteKey inside the same file
        store.updated("pid.1", props("other", "other.example.com"));

        // Assert
        assertThat(store.hosts("digitall")).isEmpty();
        assertThat(store.hosts("other")).containsExactly("other.example.com");
    }

    @Test
    void deleted_unconfiguresTheSiteOfThatPid() throws ConfigurationException {
        // Arrange
        store.updated("pid.1", props("digitall", "digitall.example.com"));
        store.updated("pid.2", props("other", "other.example.com"));

        // Act
        store.deleted("pid.1");

        // Assert
        assertThat(store.hosts("digitall")).isEmpty();
        assertThat(store.hosts("other")).containsExactly("other.example.com");
    }

    @Test
    void deleted_unknownPid_isIgnored() {
        store.deleted("never-seen");

        assertThat(store.hosts("digitall")).isEmpty();
    }

    @Test
    void updated_missingOrUnsafeSiteKey_isRejected() {
        Hashtable<String, Object> noSiteKey = new Hashtable<>();
        noSiteKey.put("fqdn", "example.com");

        assertThatThrownBy(() -> store.updated("pid.1", noSiteKey)).isInstanceOf(ConfigurationException.class);
        assertThatThrownBy(() -> store.updated("pid.1", props("../x", "example.com")))
                .isInstanceOf(ConfigurationException.class);
    }

    @Test
    void updated_invalidHostList_isRejectedAndLeavesTheSiteUnchanged() throws ConfigurationException {
        // Arrange
        store.updated("pid.1", props("digitall", "digitall.example.com"));

        // Act / Assert: a hand-edited .cfg with a bad host must not half-apply
        assertThatThrownBy(() -> store.updated("pid.1", props("digitall", "ok.example.com,-bad")))
                .isInstanceOf(ConfigurationException.class);
        assertThat(store.hosts("digitall")).containsExactly("digitall.example.com");
    }

    @Test
    void updated_fileNameOfAnotherSite_isRejected() {
        // A file whose name does not match the siteKey it holds (hand-edited, or two site keys that
        // differ only by case colliding on a case-insensitive filesystem) must not configure a site.
        Hashtable<String, Object> props = new Hashtable<>(Map.of("siteKey", "ABC", "fqdn", "abc.example.com",
                "felix.fileinstall.filename", "file:/var/jahia/karaf/etc/" + FileSiteHostsStore.FACTORY_PID + "-abc.cfg"));

        assertThatThrownBy(() -> store.updated("pid.1", props)).isInstanceOf(ConfigurationException.class);
        assertThat(store.hosts("ABC")).isEmpty();
    }

    @Test
    void updated_matchingFileName_isAccepted() throws ConfigurationException {
        Hashtable<String, Object> props = new Hashtable<>(Map.of("siteKey", "digitall", "fqdn", "digitall.example.com",
                "felix.fileinstall.filename", "file:/var/jahia/karaf/etc/" + FileSiteHostsStore.FACTORY_PID + "-digitall.cfg"));

        store.updated("pid.1", props);

        assertThat(store.hosts("digitall")).containsExactly("digitall.example.com");
    }

    @Test
    void save_targetFileHoldingAnotherSiteKey_isNeitherOverwrittenNorDeleted() throws IOException {
        // Arrange: what site "ABC" would find on a case-insensitive filesystem where site "abc" saved first
        Path target = etcDir.resolve(FileSiteHostsStore.FACTORY_PID + "-abc.cfg");
        Files.writeString(target, "siteKey = ABC\nfqdn = other.example.com\n");

        // Act / Assert
        List<String> someHosts = List.of("abc.example.com");
        List<String> noHosts = List.of();
        assertThatThrownBy(() -> store.save("abc", someHosts)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> store.save("abc", noHosts)).isInstanceOf(IOException.class);
        assertThat(Files.readString(target)).contains("siteKey = ABC");
    }

    @Test
    void getName_isDescriptive() {
        assertThat(store.getName()).isNotBlank();
    }

    private static Dictionary<String, Object> props(String siteKey, String fqdn) {
        Hashtable<String, Object> props = new Hashtable<>();
        props.put("siteKey", siteKey);
        props.put("fqdn", fqdn);
        return props;
    }
}
