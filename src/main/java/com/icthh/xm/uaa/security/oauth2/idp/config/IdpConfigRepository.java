package com.icthh.xm.uaa.security.oauth2.idp.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.icthh.xm.commons.config.client.api.RefreshableConfiguration;
import com.icthh.xm.commons.domain.idp.IdpConfigUtils;
import com.icthh.xm.commons.domain.idp.model.IdpPublicConfig;
import com.icthh.xm.commons.tenant.TenantContextHolder;
import com.icthh.xm.uaa.security.oauth2.idp.validation.verifiers.AudienceClaimVerifier;
import javassist.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.provider.token.store.IssuerClaimVerifier;
import org.springframework.security.oauth2.provider.token.store.JwtClaimsSetVerifier;
import com.icthh.xm.commons.domain.idp.model.IdpPublicConfig.IdpConfigContainer.IdpPublicClientConfig;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.CollectionUtils;

import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.icthh.xm.commons.domain.idp.IdpConstants.IDP_PUBLIC_SETTINGS_CONFIG_PATH_PATTERN;

/**
 * This class reads and process IDP clients public configuration for each tenant.
 * Tenant IDP clients created for each successfully loaded config. If config not loaded or invalid it skipped.
 * TODO add unit test
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdpConfigRepository implements RefreshableConfiguration {

    private static final String KEY_TENANT = "tenant";

    private final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());
    private final AntPathMatcher matcher = new AntPathMatcher();

    /**
     * In memory storage for storing information tenant IDP clients public.
     */
    // FIXME: describe what keys represent explicitly
    private final Map<String, Map<String, IdpPublicClientConfig>> idpClientConfigs = new ConcurrentHashMap<>();

    /**
     * In memory storage.
     * Stores information about tenant IDP clients public configuration that currently in process.
     * <p/>
     * We need to store this information in memory to avoid corruption previously registered in-memory tenant clients config
     */
    // FIXME: describe what keys represent explicitly
    private final Map<String, Map<String, IdpPublicClientConfig>> tmpIdpClientPublicConfigs = new ConcurrentHashMap<>();

    /**
     * In memory storage.
     * Stores information about tenant IDP jwt claims verifiers.
     * <p/>
     * We need to store this information in memory to avoid initialization on each token claims verification
     * and store actual verifiers.
     */
    // FIXME: describe what keys represent explicitly
    private final Map<String, Map<String, List<JwtClaimsSetVerifier>>> jwtClaimsSetVerifiersHolder = new ConcurrentHashMap<>();

    private final TenantContextHolder tenantContextHolder;

    @Override
    public void onRefresh(String updatedKey, String config) {
        updateIdpConfigs(updatedKey, config);
    }

    @Override
    public boolean isListeningConfiguration(String updatedKey) {
        return matcher.match(IDP_PUBLIC_SETTINGS_CONFIG_PATH_PATTERN, updatedKey);
    }

    @Override
    public void onInit(String configKey, String configValue) {
        updateIdpConfigs(configKey, configValue);
    }

    private void updateIdpConfigs(String configKey, String config) {
        String tenantKey = extractTenantKeyFromPath(configKey);

        processPublicConfiguration(tenantKey, configKey, config);

        boolean isClientConfigurationEmpty = CollectionUtils.isEmpty(tmpIdpClientPublicConfigs.get(tenantKey));

        if (isClientConfigurationEmpty) {
            log.info("For tenant [{}] provided IDP public client configs not applied.", tenantKey);
            return;
        }

        updateInMemoryConfig(tenantKey);
        buildJwtClaimsSetVerifiers(tenantKey);
    }

    private void processPublicConfiguration(String tenantKey, String configKey, String config) {
        if (!matcher.match(IDP_PUBLIC_SETTINGS_CONFIG_PATH_PATTERN, configKey)) {
            return;
        }
        Optional.ofNullable(parseConfig(tenantKey, config, IdpPublicConfig.class))
            .map(IdpPublicConfig::getConfig)
            .map(IdpPublicConfig.IdpConfigContainer::getClients)
            .orElseGet(Collections::emptyList)
            .stream()
            .filter(conf -> IdpConfigUtils.isPublicConfigValid(tenantKey, conf))
            .forEach(publicIdpConf -> putTmpIdpPublicConfig(tenantKey, publicIdpConf));
    }

    private void putTmpIdpPublicConfig(final String tenantKey, final IdpPublicClientConfig publicIdpConf) {
        tmpIdpClientPublicConfigs
            .computeIfAbsent(tenantKey, key -> new HashMap<>())
            .put(publicIdpConf.getKey(), publicIdpConf);
    }

    private <T> T parseConfig(String tenantKey, String config, Class<T> configType) {
        T parsedConfig = null;
        try {
            parsedConfig = objectMapper.readValue(config, configType);
        } catch (JsonProcessingException e) {
            log.error("Error occurred during attempt to read {} for tenant:{}", config.getClass(), tenantKey, e);
        }
        return parsedConfig;
    }

    /**
     * <p>
     * Basing on input configuration method removes all previously registered clients for specified tenant
     * to avoid redundant clients registration presence
     * </p>
     *
     * @param tenantKey tenant key
     */
    private void updateInMemoryConfig(String tenantKey) {
        idpClientConfigs.put(tenantKey, tmpIdpClientPublicConfigs.get(tenantKey));
        tmpIdpClientPublicConfigs.remove(tenantKey);
    }

    private String extractTenantKeyFromPath(String configKey) {
        Map<String, String> configKeyParams =
            matcher.extractUriTemplateVariables(IDP_PUBLIC_SETTINGS_CONFIG_PATH_PATTERN, configKey);

        return configKeyParams.get(KEY_TENANT);
    }

    private Map<String, IdpPublicClientConfig> getIdpClientConfigsByTenantKey(String tenantKey) {
        return idpClientConfigs.get(tenantKey);
    }

    @SneakyThrows
    public List<JwtClaimsSetVerifier> getJwtClaimsSetVerifiers(String clientId) {
        String tenantKey = tenantContextHolder.getTenantKey();

        List<JwtClaimsSetVerifier> jwtClaimsSetVerifiers =
            jwtClaimsSetVerifiersHolder.getOrDefault(tenantKey, new HashMap<>()).get(clientId);

        if (CollectionUtils.isEmpty(jwtClaimsSetVerifiers)) {
            throw new NotFoundException("Jwt claims verifiers for tenant: [" + tenantKey
                + "] not found. Check tenant idp configuration.");
        }
        return jwtClaimsSetVerifiers;
    }

    private void buildJwtClaimsSetVerifiers(String tenantKey) {
        Map<String, IdpPublicClientConfig> configs = getIdpClientConfigsByTenantKey(tenantKey);

        if (CollectionUtils.isEmpty(configs)) {
            return;
        }

        Map<String, List<JwtClaimsSetVerifier>> verifiers =
            configs.values()
                .stream()
                .map(this::getJwtClaimsSetVerifiers)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        jwtClaimsSetVerifiersHolder.put(tenantKey, verifiers);
    }

    @SneakyThrows
    private Map.Entry<String, List<JwtClaimsSetVerifier>> getJwtClaimsSetVerifiers(
        IdpPublicClientConfig idpPublicClientConfig) {
        URL issuerUrl = new URL(idpPublicClientConfig.getOpenIdConfig().getIssuer());
        IssuerClaimVerifier issuerClaimVerifier = new IssuerClaimVerifier(issuerUrl);

        String clientId = idpPublicClientConfig.getClientId();
        return Map.entry(clientId, List.of(new AudienceClaimVerifier(clientId), issuerClaimVerifier));
    }
}
