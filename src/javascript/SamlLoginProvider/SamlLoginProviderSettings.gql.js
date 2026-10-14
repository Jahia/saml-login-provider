import {gql} from '@apollo/client';

export const GET_SAML_LOGIN_PROVIDER_CONFIG = gql`
    query SamlLoginProviderConfig($siteKey: String!) {
        samlLoginProvider {
            config(siteKey: $siteKey) {
                siteKey
                fqdn
                serverNames
                samlConfigured
                buttonLabel
            }
        }
    }
`;

export const UPDATE_SAML_LOGIN_PROVIDER_CONFIG = gql`
    mutation UpdateSamlLoginProviderConfig($siteKey: String!, $fqdn: String, $buttonLabel: String) {
        samlLoginProvider {
            update(siteKey: $siteKey, fqdn: $fqdn, buttonLabel: $buttonLabel) {
                siteKey
                fqdn
                serverNames
                samlConfigured
                buttonLabel
            }
        }
    }
`;
