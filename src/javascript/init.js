import {registry} from '@jahia/ui-extender';
import register from './SamlLoginProvider/register';
import i18next from 'i18next';

export default function () {
    registry.add('callback', 'samlLoginProvider', {
        targets: ['jahiaApp-init:50'],
        callback: async () => {
            await i18next.loadNamespaces('saml-login-provider');
            register();
        }
    });
}
