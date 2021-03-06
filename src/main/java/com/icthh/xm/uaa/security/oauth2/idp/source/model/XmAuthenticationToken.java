package com.icthh.xm.uaa.security.oauth2.idp.source.model;

import com.icthh.xm.uaa.security.DomainUserDetails;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Class for storing user details wrapped by {@link DomainUserDetails}.
 */
public class XmAuthenticationToken extends AbstractAuthenticationToken {

    private final DomainUserDetails principal;
    private Object credentials;


    public XmAuthenticationToken(DomainUserDetails principal,
                                 Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        super.setAuthenticated(true); // must use super, as we override
    }

    @Override
    public void setAuthenticated(boolean isAuthenticated) throws IllegalArgumentException {
        if (isAuthenticated) {
            throw new IllegalArgumentException(
                "Cannot set this token to trusted - use constructor which takes a GrantedAuthority list instead");
        }
        super.setAuthenticated(false);
    }


    @Override
    public Object getCredentials() {
        return this.credentials;
    }

    @Override
    public Object getPrincipal() {
        return this.principal;
    }

    @Override
    public void eraseCredentials() {
        super.eraseCredentials();
        credentials = null;
    }

}
