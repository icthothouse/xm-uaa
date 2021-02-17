package com.icthh.xm.uaa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.icthh.xm.commons.config.client.api.RefreshableConfiguration;
import com.icthh.xm.commons.config.client.repository.TenantConfigRepository;
import com.icthh.xm.commons.logging.aop.IgnoreLogginAspect;
import com.icthh.xm.commons.tenant.TenantContextHolder;
import com.icthh.xm.commons.tenant.TenantContextUtils;
import com.icthh.xm.uaa.config.ApplicationProperties;
import com.icthh.xm.uaa.domain.properties.TenantProperties;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
@IgnoreLogginAspect
public class TenantPropertiesService implements RefreshableConfiguration {

    private static final String TENANT_NAME = "tenantName";

    private ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    private final ConcurrentHashMap<String, TenantProperties> tenantProps = new ConcurrentHashMap<>();

    private final AntPathMatcher matcher = new AntPathMatcher();

    private final ApplicationProperties applicationProperties;

    private final TenantConfigRepository tenantConfigRepository;

    private final TenantContextHolder tenantContextHolder;

    public TenantProperties getTenantProps() {
        String tenantKey = TenantContextUtils.getRequiredTenantKeyValue(tenantContextHolder);
        String cfgTenantKey = tenantKey.toUpperCase();
        if (!tenantProps.containsKey(cfgTenantKey)) {
            throw new IllegalArgumentException("Tenant '" + cfgTenantKey + "' - configuration is empty");
        }
        return tenantProps.get(cfgTenantKey);
    }

    public TenantContextHolder getTenantContextHolder(){
        return tenantContextHolder;
    }

    @SneakyThrows
    public void updateTenantProps(String tenentPropertiesYml) {
        String tenantKey = TenantContextUtils.getRequiredTenantKeyValue(tenantContextHolder);
        String cfgTenantKey = tenantKey.toUpperCase();

        String configName = applicationProperties.getTenantPropertiesName();

        // Simple validation correct structure
        mapper.readValue(tenentPropertiesYml, TenantProperties.class);

        tenantConfigRepository.updateConfig(cfgTenantKey, "/" + configName, tenentPropertiesYml);
    }

    @Override
    @SneakyThrows
    public void onRefresh(String updatedKey, String config) {
        String specificationPathPattern = applicationProperties.getTenantPropertiesPathPattern();
        try {
            // tenant key in upper case
            String tenant = matcher.extractUriTemplateVariables(specificationPathPattern, updatedKey).get(TENANT_NAME);
            if (StringUtils.isBlank(config)) {
                tenantProps.remove(tenant);
                log.info("Specification for tenant {} was removed: {}", tenant, updatedKey);
            } else {
                TenantProperties spec = mapper.readValue(config, TenantProperties.class);
                tenantProps.put(tenant, spec);
                log.info("Specification for tenant {} was updated: {}", tenant, updatedKey);
            }
        } catch (Exception e) {
            log.error("Error read xm specification from path {}", updatedKey, e);
        }
    }

    @Override
    public boolean isListeningConfiguration(String updatedKey) {
        String specificationPathPattern = applicationProperties.getTenantPropertiesPathPattern();
        return matcher.match(specificationPathPattern, updatedKey);
    }

    @Override
    public void onInit(String key, String config) {
        if (isListeningConfiguration(key)) {
            onRefresh(key, config);
        }
    }

    public ApplicationProperties getApplicationProperties() {
        return applicationProperties;
    }

}
