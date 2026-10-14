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

const parseHosts = value => value
    .split(',')
    .map(host => host.trim().toLowerCase())
    .filter(host => host !== '');

const isValidHostList = value => parseHosts(value)
    .every(host =>
        host.length <= MAX_HOSTNAME_LENGTH &&
        HOST_PATTERN.test(host) &&
        host.split('.').every(label => label.length <= MAX_LABEL_LENGTH));

// Must match ButtonLabel.MAX_LENGTH on the server (UTF-16 units, like String.length here).
const BUTTON_LABEL_MAX_LENGTH = 80;
// Control, format (zero-width, bidi overrides) and line/paragraph separators: the same Unicode
// categories ButtonLabel refuses on the server.
const FORBIDDEN_LABEL_CHARS = /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u;

const isValidButtonLabel = label => {
    const trimmed = label.trim();
    return trimmed.length <= BUTTON_LABEL_MAX_LENGTH && !FORBIDDEN_LABEL_CHARS.test(trimmed);
};

// Hosts that no site declares fall back to this site's settings (see LoginSite on the server).
const SYSTEMSITE = 'systemsite';

// Which site the setting belongs to, and whether that site can use it at all yet.
const SiteScope = ({siteKey, isSamlConfigured}) => {
    const {t} = useTranslation('saml-login-provider');
    return (
        <>
            <div className={styles.slp_scope_warning} role="note">
                <strong>{t('samlLoginProvider.scope', {siteKey})}</strong>
                {siteKey === SYSTEMSITE && <p>{t('samlLoginProvider.scopeSystemsite')}</p>}
            </div>
            {!isSamlConfigured && (
                <div id="slp-saml-missing" className={`${styles.slp_alert} ${styles['slp_alert--info']}`} role="note">
                    {t('samlLoginProvider.samlMissing', {siteKey})}
                </div>
            )}
        </>
    );
};

SiteScope.propTypes = {
    siteKey: PropTypes.string.isRequired,
    isSamlConfigured: PropTypes.bool.isRequired
};

// The text of the login page's SAML sign-in button; empty = the default, translated text.
const ButtonLabelField = ({value, error, onChange}) => {
    const {t} = useTranslation('saml-login-provider');
    return (
        <Field label={t('label.buttonLabel')} id="slp-button-label">
            <Input
                id="slp-button-label"
                value={value}
                placeholder={t('label.buttonLabel_default')}
                maxLength={BUTTON_LABEL_MAX_LENGTH}
                aria-invalid={error ? 'true' : undefined}
                aria-describedby={error ? 'slp-button-label-error' : 'slp-button-label-hint'}
                autoComplete="off"
                onChange={e => onChange(e.target.value)}
            />
            <span id="slp-button-label-hint" className={styles.slp_hint}>
                {t('label.buttonLabel_hint')}
            </span>
            {error && (
                <span id="slp-button-label-error" className={styles.slp_error_msg}>
                    {error}
                </span>
            )}
        </Field>
    );
};

ButtonLabelField.propTypes = {
    value: PropTypes.string.isRequired,
    error: PropTypes.string.isRequired,
    onChange: PropTypes.func.isRequired
};

export const SamlLoginProviderSettings = ({siteKey}) => {
    const {t} = useTranslation('saml-login-provider');

    const [fqdn, setFqdn] = useState('');
    const [fqdnError, setFqdnError] = useState('');
    const [buttonLabel, setButtonLabel] = useState('');
    const [buttonLabelError, setButtonLabelError] = useState('');
    // Status is one of: null, 'saving', 'success', 'error', 'cancel'
    const [saveStatus, setSaveStatus] = useState(null);
    // Becomes true once the config has loaded successfully at least once — used to tell an
    // initial-load failure (full-page error state) apart from a later background refetch
    // failure (which must not tear down an already-mounted, working form)
    const [hasLoadedOnce, setHasLoadedOnce] = useState(false);
    const [refetchFailed, setRefetchFailed] = useState(false);

    // Update page title for the SPA route — the setting is per site, so the site tells
    // multiple open tabs apart.
    useEffect(() => {
        const prev = document.title;
        document.title = `SAML auto-login — ${siteKey} — Jahia Administration`;
        return () => {
            document.title = prev;
        };
    }, [siteKey]);

    const {data: configData, loading: configLoading, error: configError, refetch: refetchConfig} = useQuery(
        GET_SAML_LOGIN_PROVIDER_CONFIG,
        {variables: {siteKey}, fetchPolicy: 'network-only'}
    );

    const [updateConfig, {loading: saving}] = useMutation(UPDATE_SAML_LOGIN_PROVIDER_CONFIG, {
        refetchQueries: [{query: GET_SAML_LOGIN_PROVIDER_CONFIG, variables: {siteKey}}]
    });

    // The hosts this site answers on: the only values the server accepts in the list.
    const serverNames = configData?.samlLoginProvider?.config?.serverNames || [];
    // Without an enabled SAML connector the site keeps the standard login, whatever is saved here.
    const samlConfigured = configData?.samlLoginProvider?.config?.samlConfigured !== false;

    useEffect(() => {
        if (configData && configData.samlLoginProvider) {
            setFqdn(configData.samlLoginProvider.config.fqdn || '');
            setButtonLabel(configData.samlLoginProvider.config.buttonLabel || '');
            setHasLoadedOnce(true);
            setRefetchFailed(false);
        }
    }, [configData]);

    // A background refetch (triggered by the mutation's refetchQueries) failing after the form
    // is already mounted must not replace the whole form with the full-page error state — only
    // an error on the very first load does that (see the early-return branches below).
    useEffect(() => {
        if (configError && hasLoadedOnce) {
            setRefetchFailed(true);
        }
    }, [configError, hasLoadedOnce]);

    const handleSubmit = async () => {
        if (!isValidHostList(fqdn)) {
            setFqdnError(t('samlLoginProvider.errors.fqdn.invalid'));
            document.getElementById('slp-fqdn')?.focus();
            return;
        }

        // Mirrors the server-side check: only this site's own names can be routed here. Systemsite
        // may also list hosts that no site declares, which only the server can tell.
        if (siteKey !== SYSTEMSITE && !parseHosts(fqdn).every(host => serverNames.includes(host))) {
            setFqdnError(t('samlLoginProvider.errors.fqdn.notSiteHost', {serverNames: serverNames.join(', ')}));
            document.getElementById('slp-fqdn')?.focus();
            return;
        }

        setFqdnError('');

        if (!isValidButtonLabel(buttonLabel)) {
            setButtonLabelError(t('samlLoginProvider.errors.buttonLabel.invalid', {max: BUTTON_LABEL_MAX_LENGTH}));
            document.getElementById('slp-button-label')?.focus();
            return;
        }

        setButtonLabelError('');
        // 'saving' clears both live regions before the result lands, ensuring re-announcement on repeated saves
        setSaveStatus('saving');
        try {
            const result = await updateConfig({variables: {siteKey, fqdn, buttonLabel: buttonLabel.trim()}});
            if (result.data && result.data.samlLoginProvider) {
                setFqdn(result.data.samlLoginProvider.update.fqdn || '');
                setButtonLabel(result.data.samlLoginProvider.update.buttonLabel || '');
                setSaveStatus('success');
            } else {
                setSaveStatus('error');
            }
        } catch {
            setSaveStatus('error');
        }
    };

    const handleCancel = () => {
        if (configData && configData.samlLoginProvider) {
            setFqdn(configData.samlLoginProvider.config.fqdn || '');
            setButtonLabel(configData.samlLoginProvider.config.buttonLabel || '');
        }

        setFqdnError('');
        setButtonLabelError('');
        setSaveStatus('cancel');
    };

    if (configLoading && !hasLoadedOnce) {
        return <div className={styles.slp_loading} role="status">{t('label.loading')}</div>;
    }

    if (configError && !hasLoadedOnce) {
        return (
            <div className={styles.slp_error} role="alert">
                <p>{t('samlLoginProvider.errors.load.failed')}: {configError.message}</p>
                <Button
                    type="button"
                    label={t('label.retry')}
                    variant="secondary"
                    onClick={() => refetchConfig()}
                />
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
                {saveStatus === 'error' ?
                    t('samlLoginProvider.errors.update.failed') :
                    refetchFailed ? t('samlLoginProvider.errors.refetch.failed') : ''}
            </div>

            <div className={styles.slp_page_header}>
                <h2>{t('label.menu_entry')}</h2>
            </div>
            <div className={styles.slp_container}>
                <div className={styles.slp_intro}>
                    <p>{t('samlLoginProvider.description')}</p>
                    <p>{t('samlLoginProvider.disabled.hint')}</p>
                </div>

                <SiteScope siteKey={siteKey} isSamlConfigured={samlConfigured}/>

                {refetchFailed && (
                    <div aria-hidden="true" className={`${styles.slp_alert} ${styles['slp_alert--error']}`}>
                        {t('samlLoginProvider.errors.refetch.failed')}
                    </div>
                )}
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
                {saveStatus === 'cancel' && (
                    <div aria-hidden="true" className={`${styles.slp_alert} ${styles['slp_alert--info']}`}>
                        {t('samlLoginProvider.cancel')}
                    </div>
                )}

                <div className={styles.slp_form}>
                    <Field label={t('label.fqdn')} id="slp-fqdn">
                        <Input
                            id="slp-fqdn"
                            value={fqdn}
                            placeholder={serverNames.join(',') || 'edit.jahia.com'}
                            aria-invalid={fqdnError ? 'true' : undefined}
                            aria-describedby={fqdnError ? 'slp-fqdn-error' : 'slp-fqdn-hint'}
                            autoComplete="off"
                            onChange={e => {
                                setFqdn(e.target.value);
                                setFqdnError('');
                            }}
                        />
                        <span id="slp-fqdn-hint" className={styles.slp_hint}>
                            {serverNames.length > 0 ?
                                t('label.fqdn_hint', {serverNames: serverNames.join(', ')}) :
                                t('label.fqdn_hint_no_server_names')}
                        </span>
                        {fqdnError && (
                            <span id="slp-fqdn-error" className={styles.slp_error_msg}>
                                {fqdnError}
                            </span>
                        )}
                    </Field>

                    <ButtonLabelField
                        value={buttonLabel}
                        error={buttonLabelError}
                        onChange={value => {
                            setButtonLabel(value);
                            setButtonLabelError('');
                        }}
                    />

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
