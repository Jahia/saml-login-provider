import {gql} from '@apollo/client';

export const GET_SAML_LOGIN_PROVIDER_CONFIG = gql`
    query SamlLoginProviderConfig($siteKey: String!) {
        samlLoginProviderConfig(siteKey: $siteKey) {
            fqdn
        }
    }
`;

export const UPDATE_SAML_LOGIN_PROVIDER_CONFIG = gql`
    mutation UpdateSamlLoginProviderConfig($siteKey: String!, $fqdn: String) {
        updateSamlLoginProviderConfig(siteKey: $siteKey, fqdn: $fqdn) {
            fqdn
        }
    }
`;
