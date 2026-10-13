package org.jahia.community.modules.samlloginprovider.graphql;

import org.jahia.community.modules.samlloginprovider.SamlSetup;
import org.jahia.community.modules.samlloginprovider.SiteHostsStore;
import org.jahia.community.modules.samlloginprovider.SiteResolver;
import org.jahia.osgi.BundleUtils;

/**
 * OSGi lookups for the GraphQL containers, which graphql-java instantiates itself and therefore
 * cannot receive injected services. Fails fast with a clear message if a service is momentarily
 * unavailable (bundle restart/refresh race) rather than surfacing later as an opaque NPE.
 */
final class SamlLoginProviderServices {

    private SamlLoginProviderServices() {
    }

    static SiteHostsStore siteHostsStore() {
        return require(BundleUtils.getOsgiService(SiteHostsStore.class, null), SiteHostsStore.class);
    }

    static SiteResolver siteResolver() {
        return require(BundleUtils.getOsgiService(SiteResolver.class, null), SiteResolver.class);
    }

    static SamlSetup samlSetup() {
        return require(BundleUtils.getOsgiService(SamlSetup.class, null), SamlSetup.class);
    }

    private static <T> T require(T service, Class<T> type) {
        if (service == null) {
            throw new IllegalStateException(type.getSimpleName() + " is unavailable");
        }
        return service;
    }
}
