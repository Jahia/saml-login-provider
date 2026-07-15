import {registry} from '@jahia/ui-extender';
import React from 'react';
import SamlLoginProviderSettings from './SamlLoginProviderSettings';

export default () => {
    registry.add('adminRoute', 'samlLoginProvider', {
        icon: window.jahia.moonstone.toIconComponent('Lock'),
        targets: ['administration-server-configuration:10'],
        requiredPermission: 'samlLoginProviderAdmin',
        label: 'saml-login-provider:label.menu_entry',
        isSelectable: true,
        render: () => React.createElement(SamlLoginProviderSettings)
    });
};
