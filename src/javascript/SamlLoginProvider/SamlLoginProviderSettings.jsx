import React, {useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useMutation, useQuery} from '@apollo/client';
import {useTranslation} from 'react-i18next';
import {Button, Field, Input} from '@jahia/moonstone';
import styles from './SamlLoginProviderSettings.scss';
import {
    GET_SAML_LOGIN_PROVIDER_CONFIG,
    UPDATE_SAML_LOGIN_PROVIDER_CONFIG
} from './SamlLoginProviderSettings.gql';

// Mirrors the server-side validation (HostList): RFC 1123 labels, comma-separated
const HOST_PATTERN = /^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$/;
const MAX_HOSTNAME_LENGTH = 253;
const MAX_LABEL_LENGTH = 63;

const isValidHostList = value => value
    .split(',')
    .map(host => host.trim())
    .filter(host => host !== '')
    .every(host =>
        host.length <= MAX_HOSTNAME_LENGTH &&
        HOST_PATTERN.test(host) &&
        host.split('.').every(label => label.length <= MAX_LABEL_LENGTH));

export const SamlLoginProviderSettings = ({siteKey}) => {
    const {t} = useTranslation('saml-login-provider');

    const [fqdn, setFqdn] = useState('');
    const [fqdnError, setFqdnError] = useState('');
    // Status is one of: null, 'saving', 'success', 'error', 'cancel'
    const [saveStatus, setSaveStatus] = useState(null);

    // Update page title for the SPA route — siteKey disambiguates multiple open tabs
    useEffect(() => {
        const prev = document.title;
        document.title = `SAML auto-login — ${siteKey} — Jahia Administration`;
        return () => {
            document.title = prev;
        };
    }, [siteKey]);

    const {data: configData, loading: configLoading, error: configError} = useQuery(
        GET_SAML_LOGIN_PROVIDER_CONFIG,
        {variables: {siteKey}, fetchPolicy: 'network-only'}
    );

    const [updateConfig, {loading: saving}] = useMutation(UPDATE_SAML_LOGIN_PROVIDER_CONFIG, {
        refetchQueries: [{query: GET_SAML_LOGIN_PROVIDER_CONFIG, variables: {siteKey}}]
    });

    useEffect(() => {
        if (configData && configData.samlLoginProviderConfig) {
            setFqdn(configData.samlLoginProviderConfig.fqdn || '');
        }
    }, [configData]);

    const handleSubmit = async () => {
        if (!isValidHostList(fqdn)) {
            setFqdnError(t('samlLoginProvider.errors.fqdn.invalid'));
            document.getElementById('slp-fqdn')?.focus();
            return;
        }

        setFqdnError('');
        // 'saving' clears both live regions before the result lands, ensuring re-announcement on repeated saves
        setSaveStatus('saving');
        try {
            const result = await updateConfig({variables: {siteKey, fqdn}});
            if (result.data && result.data.updateSamlLoginProviderConfig) {
                setFqdn(result.data.updateSamlLoginProviderConfig.fqdn || '');
                setSaveStatus('success');
            } else {
                setSaveStatus('error');
            }
        } catch {
            setSaveStatus('error');
        }
    };

    const handleCancel = () => {
        if (configData && configData.samlLoginProviderConfig) {
            setFqdn(configData.samlLoginProviderConfig.fqdn || '');
        }

        setFqdnError('');
        setSaveStatus('cancel');
    };

    if (configLoading) {
        return <div className={styles.slp_loading} role="status">{t('label.loading')}</div>;
    }

    if (configError) {
        return (
            <div className={styles.slp_error} role="alert">
                {t('samlLoginProvider.errors.load.failed')}: {configError.message}
            </div>
        );
    }

    return (
        <div>
            {/* Two fixed-role live regions always in DOM — AT registers roles at mount, never mutate role post-mount */}
            <div role="status" aria-live="polite" aria-atomic="true" className={styles.slp_sr_only}>
                {saveStatus === 'success' ? t('samlLoginProvider.success.update') : saveStatus === 'cancel' ? t('samlLoginProvider.cancel') : ''}
            </div>
            <div role="alert" aria-live="assertive" aria-atomic="true" className={styles.slp_sr_only}>
                {saveStatus === 'error' ? t('samlLoginProvider.errors.update.failed') : ''}
            </div>

            <div className={styles.slp_page_header}>
                <h2>{t('label.menu_entry')} - {siteKey}</h2>
            </div>
            <div className={styles.slp_container}>
                <div className={styles.slp_intro}>
                    <p>{t('samlLoginProvider.description')}</p>
                    <p>{t('samlLoginProvider.disabled.hint')}</p>
                    <p><strong>{t('samlLoginProvider.scope')}</strong></p>
                </div>

                {saveStatus === 'success' && (
                    <div aria-hidden="true" className={`${styles.slp_alert} ${styles['slp_alert--success']}`}>
                        {t('samlLoginProvider.success.update')}
                    </div>
                )}
                {saveStatus === 'error' && (
                    <div aria-hidden="true" className={`${styles.slp_alert} ${styles['slp_alert--error']}`}>
                        {t('samlLoginProvider.errors.update.failed')}
                    </div>
                )}

                <div className={styles.slp_form}>
                    <Field label={t('label.fqdn')} id="slp-fqdn">
                        <Input
                            id="slp-fqdn"
                            value={fqdn}
                            placeholder="edit.jahia.com,editpp.jahia.com"
                            aria-invalid={fqdnError ? 'true' : undefined}
                            aria-describedby={fqdnError ? 'slp-fqdn-error' : 'slp-fqdn-hint'}
                            autoComplete="off"
                            onChange={e => {
                                setFqdn(e.target.value);
                                setFqdnError('');
                            }}
                        />
                        <span id="slp-fqdn-hint" className={styles.slp_hint}>
                            {t('label.fqdn_hint')}
                        </span>
                        {fqdnError && (
                            <span id="slp-fqdn-error" className={styles.slp_error_msg}>
                                {fqdnError}
                            </span>
                        )}
                    </Field>

                    <div className={styles.slp_actions}>
                        <Button
                            type="button"
                            label={saving ? t('label.saving') : t('label.update')}
                            variant="primary"
                            isDisabled={saving}
                            onClick={handleSubmit}
                        />
                        <Button
                            type="button"
                            label={t('label.cancel')}
                            variant="secondary"
                            isDisabled={saving}
                            onClick={handleCancel}
                        />
                    </div>
                </div>
            </div>
        </div>
    );
};

SamlLoginProviderSettings.propTypes = {
    siteKey: PropTypes.string.isRequired
};

export default SamlLoginProviderSettings;
