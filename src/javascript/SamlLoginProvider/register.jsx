import {registry} from '@jahia/ui-extender';
import React from 'react';
import PropTypes from 'prop-types';
import SamlLoginProviderSettings from './SamlLoginProviderSettings';

const SamlLoginProviderSettingsRoute = props => {
    // The siteKey is provided by Jahia admin via match params or context
    const siteKey =
        (props.match && props.match.params && props.match.params.siteKey) ||
        window.contextJsParameters.siteKey;

    if (!siteKey) {
        return <div>No site selected.</div>;
    }

    return <SamlLoginProviderSettings siteKey={siteKey}/>;
};

SamlLoginProviderSettingsRoute.propTypes = {
    match: PropTypes.shape({
        params: PropTypes.shape({
            siteKey: PropTypes.string
        })
    })
};

SamlLoginProviderSettingsRoute.defaultProps = {
    match: undefined
};

export default () => {
    registry.add('adminRoute', 'samlLoginProvider', {
        icon: window.jahia.moonstone.toIconComponent('Lock'),
        targets: ['administration-sites:998'],
        requiredPermission: 'siteAdminSamlLoginProvider',
        label: 'saml-login-provider:label.menu_entry',
        isSelectable: true,
        render: () => React.createElement(SamlLoginProviderSettingsRoute)
    });
};
