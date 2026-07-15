import {gql} from '@apollo/client';

export const GET_SAML_LOGIN_PROVIDER_CONFIG = gql`
    query SamlLoginProviderConfig {
        samlLoginProvider {
            config {
                fqdn
            }
        }
    }
`;

export const UPDATE_SAML_LOGIN_PROVIDER_CONFIG = gql`
    mutation UpdateSamlLoginProviderConfig($fqdn: String) {
        samlLoginProvider {
            update(fqdn: $fqdn) {
                fqdn
            }
        }
    }
`;
