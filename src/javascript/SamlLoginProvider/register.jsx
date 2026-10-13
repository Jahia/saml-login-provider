import {registry} from '@jahia/ui-extender';
import React from 'react';
import SamlLoginProviderSettings from './SamlLoginProviderSettings';

export default () => {
    registry.add('adminRoute', 'samlLoginProvider', {
        icon: window.jahia.moonstone.toIconComponent('Lock'),
        targets: ['administration-sites:998'],
        requiredPermission: 'siteAdminSamlLoginProvider',
        label: 'saml-login-provider:label.menu_entry',
        isSelectable: true,
        // Site routes are mounted at /administration/:site/<route key>, and react-router's props are
        // passed through: the site comes from the URL, so it follows the administration's site
        // switcher. Keyed by site so switching sites starts from a fresh form.
        render: ({match}) => {
            const siteKey = match.params.site;
            return <SamlLoginProviderSettings key={siteKey} siteKey={siteKey}/>;
        }
    });
};
