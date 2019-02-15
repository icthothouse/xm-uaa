package com.icthh.xm.uaa.security;

import com.icthh.xm.commons.lep.LogicExtensionPoint;
import com.icthh.xm.commons.lep.spring.LepService;
import com.icthh.xm.commons.logging.aop.IgnoreLogginAspect;
import com.icthh.xm.uaa.security.ldap.LdapAuthenticationProviderBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Optional;

import static com.icthh.xm.uaa.config.Constants.AUTH_USERNAME_DOMAIN_SEPARATOR;

@Slf4j
@IgnoreLogginAspect
@LepService(group = "security.provider")
public class UaaAuthenticationProvider implements AuthenticationProvider {

    private final AuthenticationProvider defaultProvider;
    private final LdapAuthenticationProviderBuilder providerBuilder;

    public UaaAuthenticationProvider(@Qualifier("daoAuthenticationProvider")
                                     @Lazy AuthenticationProvider defaultProvider,
                                     LdapAuthenticationProviderBuilder providerBuilder) {
        this.defaultProvider = defaultProvider;
        this.providerBuilder = providerBuilder;
    }

    private AuthenticationProvider getProvider(Authentication authentication) {
        AuthenticationProvider provider = defaultProvider;
        String principal = authentication.getPrincipal().toString();
        LinkedList<String> parts = new LinkedList<>(Arrays.asList(principal.split(AUTH_USERNAME_DOMAIN_SEPARATOR)));

        if (parts.size() > BigInteger.ONE.intValue()) {
            String domain = parts.getLast();
            log.info("Ldap domain @{} for user {}", domain, principal);

            Optional<AuthenticationProvider> providerOpt = providerBuilder.build(domain);
            if (providerOpt.isPresent()) {
                provider = providerOpt.get();
            }
        }

        return provider;
    }

    /**
     * {@inheritDoc}
     */
    @LogicExtensionPoint(value = "Authenticate")
    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Authentication result = getProvider(authentication).authenticate(authentication);
        log.info("authenticated: {}, role: {}, {}",result.isAuthenticated(), result.getAuthorities(), result.getPrincipal());
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supports(Class<?> authentication) {
        return Boolean.TRUE;
    }

}
