package com.icthh.xm.uaa.security.oauth2.idp;

import com.icthh.xm.commons.lep.LogicExtensionPoint;
import com.icthh.xm.commons.lep.spring.LepService;
import com.icthh.xm.commons.tenant.TenantContextUtils;
import com.icthh.xm.uaa.domain.User;
import com.icthh.xm.uaa.domain.UserLogin;
import com.icthh.xm.uaa.domain.UserLoginType;
import com.icthh.xm.uaa.domain.properties.TenantProperties;
import com.icthh.xm.uaa.security.DomainUserDetails;
import com.icthh.xm.uaa.security.DomainUserDetailsService;
import com.icthh.xm.uaa.security.TenantNotProvidedException;
import com.icthh.xm.uaa.security.oauth2.idp.source.model.IdpAuthenticationToken;
import com.icthh.xm.uaa.service.TenantPropertiesService;
import com.icthh.xm.uaa.service.UserLoginService;
import com.icthh.xm.uaa.service.UserService;
import com.icthh.xm.uaa.service.dto.UserDTO;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.core.authority.mapping.NullAuthoritiesMapper;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.security.oauth2.provider.ClientDetailsService;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.OAuth2RequestFactory;
import org.springframework.security.oauth2.provider.TokenRequest;
import org.springframework.security.oauth2.provider.token.AbstractTokenGranter;
import org.springframework.security.oauth2.provider.token.AuthorizationServerTokenServices;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@LepService(group = "security.idp")
public class IdpTokenGranter extends AbstractTokenGranter {

    private static final String GRANT_TYPE = "idp_token";
    public static final String GIVEN_NAME_ATTR = "given_name";
    public static final String FAMILY_NAME_ATTR = "family_name";

    private final XmJwkTokenStore jwkTokenStore;
    private final DomainUserDetailsService domainUserDetailsService;
    private final TenantPropertiesService tenantPropertiesService;
    private final UserService userService;
    private final UserLoginService userLoginService;
    private final GrantedAuthoritiesMapper authoritiesMapper = new NullAuthoritiesMapper();

    public IdpTokenGranter(AuthorizationServerTokenServices tokenServices,
                           ClientDetailsService clientDetailsService,
                           @Lazy OAuth2RequestFactory requestFactory,
                           XmJwkTokenStore jwkTokenStore,
                           DomainUserDetailsService domainUserDetailsService,
                           TenantPropertiesService tenantPropertiesService,
                           UserService userService,
                           UserLoginService userLoginService) {
        super(tokenServices, clientDetailsService, requestFactory, GRANT_TYPE);
        this.jwkTokenStore = jwkTokenStore;
        this.domainUserDetailsService = domainUserDetailsService;
        this.tenantPropertiesService = tenantPropertiesService;
        this.userService = userService;
        this.userLoginService = userLoginService;
    }

    @Override
    protected OAuth2AccessToken getAccessToken(ClientDetails client, TokenRequest tokenRequest) {
        return getTokenServices().createAccessToken(getOAuth2Authentication(client, tokenRequest));
    }

    @Override
    protected OAuth2Authentication getOAuth2Authentication(ClientDetails client, TokenRequest tokenRequest) {
        Map<String, String> parameters = new LinkedHashMap<>(tokenRequest.getRequestParameters());
        Authentication authentication = getOAuth2AuthenticationFromToken(parameters);

        return new OAuth2Authentication(tokenRequest.createOAuth2Request(client), authentication);
    }

    @SneakyThrows
    private Authentication getOAuth2AuthenticationFromToken(Map<String, String> parameters) {
        return getUserAuthenticationToken(parameters);
    }

    private IdpAuthenticationToken getUserAuthenticationToken(Map<String, String> parameters) {
        String idpToken = parameters.get("token");
        //TODO Also put all key, hardcoded names, etc to constant class (if more than one time it used) or to private static final variable

        // parse IDP id token
        OAuth2AccessToken idpOAuth2IdToken = jwkTokenStore.readAccessToken(idpToken);

        validateIdpAccessToken(idpOAuth2IdToken);

        //user + role section
        DomainUserDetails userDetails = retrieveDomainUserDetails(idpOAuth2IdToken);
        Collection<? extends GrantedAuthority> authorities =
            authoritiesMapper.mapAuthorities(userDetails.getAuthorities());

        //build container for user
        IdpAuthenticationToken userAuthenticationToken = new IdpAuthenticationToken(userDetails, authorities);
        userAuthenticationToken.setDetails(parameters);

        return userAuthenticationToken;
    }

    private DomainUserDetails retrieveDomainUserDetails(OAuth2AccessToken idpOAuth2IdToken) {
        String userIdentity = extractUserIdentity(idpOAuth2IdToken);
        DomainUserDetails userDetails = domainUserDetailsService.retrieveUserByUsername(userIdentity);

        if (userDetails == null) {
            log.info("User not found by identity: {}, new user will be created", userIdentity);
            User newUser = createUser(userIdentity, idpOAuth2IdToken);
            userDetails = DomainUserDetailsService.buildDomainUserDetails(userIdentity, getTenantKey(), newUser);
        }
        log.info("Mapped user for identity:{} is {}", userIdentity, userDetails);
        return userDetails;
    }

    //TODO Think about name for "identity" , principal?
    @LogicExtensionPoint(value = "ExtractUserIdentity")
    public String extractUserIdentity(OAuth2AccessToken idpOAuth2IdToken) {
        Map<String, Object> additionalInformation = idpOAuth2IdToken.getAdditionalInformation();
        //TODO "email" should be taken from uaa.yml: security.idp.defaultLoginAttribute whith default
        // value: email if configuration not specified
        return (String) additionalInformation.get("email");
    }

    private User createUser(String userIdentity, OAuth2AccessToken idpOAuth2IdToken) {
        UserDTO userDTO = convertIdpClaimsToXmUser(userIdentity, idpOAuth2IdToken);

        userLoginService.normalizeLogins(userDTO.getLogins());
        userLoginService.verifyLoginsNotExist(userDTO.getLogins());

        userDTO.setRoleKey(mapIdpIdTokenToRole(userIdentity, idpOAuth2IdToken));

        return userService.createUser(userDTO);
    }

    //TODO add claim validation: audience and issuer
    // throw exception, define throw, javadoc
    @LogicExtensionPoint(value = "ValidateIdpAccessToken")
    public void validateIdpAccessToken(OAuth2AccessToken idpOAuth2IdToken) {
        //validate issuer and audience, etc, + LEP
    }


    @LogicExtensionPoint(value = "ConvertIdpClaimsToXmUser")
    public UserDTO convertIdpClaimsToXmUser(String userIdentity, OAuth2AccessToken idpOAuth2IdToken) {
        Map<String, Object> additionalInformation = idpOAuth2IdToken.getAdditionalInformation();
        UserDTO userDTO = new UserDTO();

        //TODO default configuration should be taken from uaa.yml with specified default values of some properties is missing
        /*
         * security:
         *   idp:
         *     defaultIdpClaimMapping:
         *       userIdentityAttribute: email
         *       userIdentityType:      LOGIN.EMAIL
         *       firstNameAttribute:    given_name
         *       lastNameAttribute:     family_name
         */

        //base info mapping
        userDTO.setFirstName((String) additionalInformation.get(GIVEN_NAME_ATTR));
        userDTO.setLastName((String) additionalInformation.get(FAMILY_NAME_ATTR));
        //login mapping
        UserLogin emailUserLogin = new UserLogin();
        emailUserLogin.setLogin(userIdentity);
        emailUserLogin.setTypeKey(UserLoginType.EMAIL.getValue());

        userDTO.setLogins(List.of(emailUserLogin));
        return userDTO;
    }

    //TODO think about name
    @LogicExtensionPoint(value = "MapIdpIdTokenToRole")
    public String mapIdpIdTokenToRole(String userIdentity, OAuth2AccessToken idpOAuth2IdToken) {
        TenantProperties tenantProps = tenantPropertiesService.getTenantProps();
        TenantProperties.Security security = tenantProps.getSecurity();

        if (security == null) {
            throw new TenantNotProvidedException("Default role for tenant " + getTenantKey() + " not specified.");
        }
        return security.getDefaultUserRole();
    }

    private String getTenantKey() {
        return TenantContextUtils.getRequiredTenantKeyValue(tenantPropertiesService.getTenantContextHolder());
    }

}
