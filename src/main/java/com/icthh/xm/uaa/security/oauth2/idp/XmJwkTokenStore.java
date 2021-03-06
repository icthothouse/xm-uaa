package com.icthh.xm.uaa.security.oauth2.idp;

import com.icthh.xm.uaa.security.oauth2.idp.converter.XmJwkVerifyingJwtAccessTokenConverter;
import lombok.Getter;
import lombok.Setter;
import org.springframework.security.oauth2.provider.token.TokenEnhancer;
import org.springframework.security.oauth2.provider.token.store.JwtTokenStore;

/**
 * Custom implementation of JwtTokenStore for idp tokens storing and processing.
 */
@Getter
@Setter
public class XmJwkTokenStore extends JwtTokenStore {

    private TokenEnhancer jwtTokenEnhancer;

    /**
     * Create a JwtTokenStore with this token enhancer (should be shared with the DefaultTokenServices if used).
     *
     * @param jwtTokenEnhancer
     */
    public XmJwkTokenStore(XmJwkVerifyingJwtAccessTokenConverter jwtTokenEnhancer) {
        super(jwtTokenEnhancer);
        this.jwtTokenEnhancer = jwtTokenEnhancer;
    }
}
