package org.jahia.community.modules.samlloginprovider;

import org.jahia.osgi.BundleUtils;
import org.jahia.services.modulemanager.spi.Config;
import org.jahia.services.modulemanager.spi.ConfigService;

import java.io.IOException;

/**
 * Read/write access to the module's OSGi configuration
 * ({@link SamlLoginUrlProvider#CONFIG_PID}) through Jahia's
 * {@link ConfigService}, so the karaf/etc {@code .cfg} file and the running
 * provider ({@code @Modified}) stay in sync.
 */
public class ConfigStore {

    private final ConfigService configService;

    public ConfigStore(ConfigService configService) {
        this.configService = configService;
    }

    /**
     * Resolves the live {@link ConfigService} from OSGi and wraps it. Fails
     * fast with a clear message if the service is momentarily unavailable
     * (bundle restart/refresh race) rather than letting a {@code null} service
     * surface later as an opaque NPE.
     */
    public static ConfigStore fromOsgi() {
        ConfigService configService = BundleUtils.getOsgiService(ConfigService.class, null);
        if (configService == null) {
            throw new IllegalStateException("ConfigService is unavailable");
        }
        return new ConfigStore(configService);
    }

    /** Returns the stored comma-separated host list, or an empty string when unset. */
    public String read() throws IOException {
        String fqdn = configService.getConfig(SamlLoginUrlProvider.CONFIG_PID)
                .getValues()
                .getProperty(SamlLoginUrlProvider.FQDN_PROPERTY);
        return fqdn == null ? "" : fqdn;
    }

    /** Persists the canonical host list; an empty value removes the property (clean .cfg). */
    public void store(String canonical) throws IOException {
        Config config = configService.getConfig(SamlLoginUrlProvider.CONFIG_PID);
        if (canonical.isEmpty()) {
            config.getValues().removeProperty(SamlLoginUrlProvider.FQDN_PROPERTY);
        } else {
            config.getValues().setProperty(SamlLoginUrlProvider.FQDN_PROPERTY, canonical);
        }
        configService.storeConfig(config);
    }
}
