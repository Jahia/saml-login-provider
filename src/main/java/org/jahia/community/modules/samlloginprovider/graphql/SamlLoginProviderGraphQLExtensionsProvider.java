package org.jahia.community.modules.samlloginprovider.graphql;

import org.jahia.modules.graphql.provider.dxm.DXGraphQLExtensionsProvider;
import org.osgi.service.component.annotations.Component;

/**
 * Publishes this package's {@code @GraphQLTypeExtension} classes to the
 * graphql-dxm-provider (same discovery mechanism as the securitytxt module).
 */
@Component(immediate = true)
public class SamlLoginProviderGraphQLExtensionsProvider implements DXGraphQLExtensionsProvider {
}
