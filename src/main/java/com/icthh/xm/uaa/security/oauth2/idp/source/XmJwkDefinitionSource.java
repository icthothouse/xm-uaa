package com.icthh.xm.uaa.security.oauth2.idp.source;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.icthh.xm.uaa.security.oauth2.idp.config.IdpConfigRepository;
import com.icthh.xm.uaa.security.oauth2.idp.converter.XmJwkSetConverter;
import com.icthh.xm.uaa.security.oauth2.idp.source.loaders.LocalStorageDefinitionSourceLoader;
import com.icthh.xm.uaa.security.oauth2.idp.source.loaders.RemoteDefinitionSourceLoader;
import com.icthh.xm.uaa.security.oauth2.idp.source.model.XmJwkDefinition;
import com.icthh.xm.uaa.security.oauth2.idp.source.model.XmRsaJwkDefinition;
import com.icthh.xm.uaa.service.TenantPropertiesService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.jwt.codec.Codecs;
import org.springframework.security.jwt.crypto.sign.RsaVerifier;
import org.springframework.security.jwt.crypto.sign.SignatureVerifier;
import org.springframework.security.oauth2.provider.token.store.jwk.JwkException;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This class copied from org.springframework.security.oauth2.provider.token.store.jwk.JwkDefinitionSource.
 * Reason: we need custom implementation of JwkDefinitionSource class which impossible to import and override
 * cause it has package private access.
 * <p>
 * What was changed:
 * <ul>
 * <li/>Added RestTemplate, IdpConfigRepository, TenantPropertiesService properties.
 * <li/>Original property jwkSetUrls was removed as redundant.
 * <li/>Original property JwkSetConverter has custom implementation.
 * <li/>Original constructors was removed as redundant.
 * <li/>Original method loadJwkDefinitions was removed as redundant.
 * <li/>Method {@link XmJwkDefinitionSource#getDefinitionLoadIfNecessary(String)} was changed.
 * <li/>Added retrieving {@link DefinitionSourceLoader} implementation depended from tenant configuration
 * in method {@link XmJwkDefinitionSource#getOrCreateDefinitionSourceLoader(String, SourceDefinitionType)}.
 * </ul>
 */

@Data
@Slf4j
public class XmJwkDefinitionSource {
    private final RestTemplate loadBalancedRestTemplate;
    private final IdpConfigRepository idpConfigRepository;
    private final TenantPropertiesService tenantPropertiesService;

    private final Map<String, XmJwkDefinitionHolder> jwkDefinitions = new ConcurrentHashMap<>();
    private static final XmJwkSetConverter xmJwkSetConverter = new XmJwkSetConverter();
    private Map<String, Map<String, DefinitionSourceLoader>> definitionSourceLoaderContainer = new ConcurrentHashMap<>();

    public XmJwkDefinitionSource(RestTemplate loadBalancedRestTemplate,
                                 IdpConfigRepository idpConfigRepository,
                                 TenantPropertiesService tenantPropertiesService) {
        this.loadBalancedRestTemplate = loadBalancedRestTemplate;
        this.idpConfigRepository = idpConfigRepository;
        this.tenantPropertiesService = tenantPropertiesService;
    }

    /**
     * Returns the JWK definition matching the provided keyId (&quot;kid&quot;).
     * If the JWK definition is not available in the internal cache
     * then {@link DefinitionSourceLoader#retrieveRawPublicKeysDefinition(Map)} }
     * will be called (to re-load the cache) and then followed-up with a second attempt to locate the JWK definition.
     *
     * @param keyId the Key ID (&quot;kid&quot;)
     * @return the matching {@link XmJwkDefinitionHolder} or null if not found
     */
    public XmJwkDefinitionHolder getDefinitionLoadIfNecessary(String keyId) {
        XmJwkDefinitionHolder result = this.getDefinition(keyId);
        if (result != null) {
            return result;
        }
        synchronized (this.jwkDefinitions) {
            result = this.getDefinition(keyId);
            if (result != null) {
                return result;
            }

            Map<String, XmJwkDefinitionHolder> newJwkDefinitions = updateJwkDefinitionHolders();

            this.jwkDefinitions.clear();
            this.jwkDefinitions.putAll(newJwkDefinitions);
            return this.getDefinition(keyId);
        }
    }

    private Map<String, XmJwkDefinitionHolder> updateJwkDefinitionHolders() {
        DefinitionSourceLoader definitionSourceLoader;

        Map<String, Object> params = new HashMap<>();

        String tenantKey = getTenantKey();
        SourceDefinitionType sourceDefinitionType = getDefinitionSourceType();

        definitionSourceLoader = getOrCreateDefinitionSourceLoader(tenantKey, sourceDefinitionType);

        if (SourceDefinitionType.REMOTE.equals(sourceDefinitionType)) {
            params.put("clientConfigs", idpConfigRepository.getIdpClientConfigsByTenantKey(tenantKey));
        }

        List<InputStream> publicKeysRawDefinition = definitionSourceLoader.retrieveRawPublicKeysDefinition(params);
        Map<String, XmJwkDefinitionHolder> newJwkDefinitions = new LinkedHashMap<>();
        publicKeysRawDefinition.forEach(rawDefinition -> newJwkDefinitions.putAll(buildJwkDefinitions(rawDefinition)));

        return newJwkDefinitions;
    }

    private String getTenantKey() {
        return tenantPropertiesService.getTenantContextHolder().getTenantKey();
    }

    private SourceDefinitionType getDefinitionSourceType() {
        Map<String, Object> idpPublicConfig = idpConfigRepository.getIdpPublicConfigByTenantKey(getTenantKey());
        SourceDefinitionType jwksSourceType = SourceDefinitionType.fromValue(String.valueOf(idpPublicConfig.get("jwksSourceType")));

        log.debug("jwks source definition type: {}", jwksSourceType);

        return jwksSourceType;
    }

    //FIXME do not do this on each request
    private DefinitionSourceLoader getOrCreateDefinitionSourceLoader(String tenantKey, SourceDefinitionType type) {
        if (type == null) {
            throw new IllegalArgumentException("Definition loader type not specified " +
                "in configuration for tenant [" + tenantKey + "]");
        }

        Map<String, DefinitionSourceLoader> loader = definitionSourceLoaderContainer.getOrDefault(tenantKey, new HashMap<>());

        DefinitionSourceLoader definitionSourceLoader;
        DefinitionSourceLoader existSourceLoader = loader.get(type.value());

        if (existSourceLoader == null) {
            definitionSourceLoader = buildDefinitionSourceLoader(type);
            loader.put(type.value(), definitionSourceLoader);
            definitionSourceLoaderContainer.put(tenantKey, loader);
            return definitionSourceLoader;
        } else {
            return existSourceLoader;
        }
    }

    private DefinitionSourceLoader buildDefinitionSourceLoader(SourceDefinitionType type) {
        DefinitionSourceLoader definitionSourceLoader;
        switch (type) {
            case REMOTE:
                definitionSourceLoader = new RemoteDefinitionSourceLoader();
                break;
            case STORAGE:
                definitionSourceLoader = new LocalStorageDefinitionSourceLoader(loadBalancedRestTemplate);
                break;
            default:
                throw new UnsupportedOperationException("Unknown definition loader type: " + type);
        }
        return definitionSourceLoader;
    }

    /**
     * Returns the JWK definition matching the provided keyId (&quot;kid&quot;).
     *
     * @param keyId the Key ID (&quot;kid&quot;)
     * @return the matching {@link XmJwkDefinition} or null if not found
     */
    private XmJwkDefinitionHolder getDefinition(String keyId) {
        return this.jwkDefinitions.get(keyId);
    }

    private static Map<String, XmJwkDefinitionHolder> buildJwkDefinitions(InputStream jwkSetSource) {
        Set<XmJwkDefinition> jwkDefinitionSet = xmJwkSetConverter.convert(jwkSetSource);

        Map<String, XmJwkDefinitionHolder> jwkDefinitions = new LinkedHashMap<>();

        for (XmJwkDefinition jwkDefinition : jwkDefinitionSet) {
            if (XmJwkDefinition.KeyType.RSA.equals(jwkDefinition.getKeyType())) {
                jwkDefinitions.put(jwkDefinition.getKeyId(),
                    new XmJwkDefinitionHolder(jwkDefinition, createRsaVerifier((XmRsaJwkDefinition) jwkDefinition)));
            }
        }

        return jwkDefinitions;
    }

    private static RsaVerifier createRsaVerifier(XmRsaJwkDefinition rsaDefinition) {
        RsaVerifier result;
        try {
            BigInteger modulus = new BigInteger(1, Codecs.b64UrlDecode(rsaDefinition.getModulus()));
            BigInteger exponent = new BigInteger(1, Codecs.b64UrlDecode(rsaDefinition.getExponent()));

            RSAPublicKey rsaPublicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(modulus, exponent));

            if (rsaDefinition.getAlgorithm() != null) {
                result = new RsaVerifier(rsaPublicKey, rsaDefinition.getAlgorithm().standardName());
            } else {
                result = new RsaVerifier(rsaPublicKey);
            }

        } catch (Exception ex) {
            throw new JwkException("An error occurred while creating a RSA Public Key Verifier for " +
                rsaDefinition.getKeyId() + " : " + ex.getMessage(), ex);
        }
        return result;
    }

    public static class XmJwkDefinitionHolder {
        private final XmJwkDefinition jwkDefinition;
        private final SignatureVerifier signatureVerifier;

        private XmJwkDefinitionHolder(XmJwkDefinition jwkDefinition, SignatureVerifier signatureVerifier) {
            this.jwkDefinition = jwkDefinition;
            this.signatureVerifier = signatureVerifier;
        }

        public XmJwkDefinition getJwkDefinition() {
            return jwkDefinition;
        }

        public SignatureVerifier getSignatureVerifier() {
            return signatureVerifier;
        }
    }

    public enum SourceDefinitionType {
        REMOTE("remote"),
        STORAGE("storage");

        private final String value;

        SourceDefinitionType(String value) {
            this.value = value;
        }

        public String value() {
            return this.value;
        }

        @JsonCreator
        public static SourceDefinitionType fromValue(String value) {
            SourceDefinitionType result = null;
            for (SourceDefinitionType type : values()) {
                if (type.value().equalsIgnoreCase(value)) {
                    result = type;
                    break;
                }
            }
            return result;
        }
    }

}
