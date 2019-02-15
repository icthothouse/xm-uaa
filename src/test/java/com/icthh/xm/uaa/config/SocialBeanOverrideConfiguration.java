package com.icthh.xm.uaa.config;

import static org.mockito.Mockito.mock;

import com.icthh.xm.uaa.social.ConnectSupport;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class SocialBeanOverrideConfiguration {


    @Bean
    @Primary
    public ConnectSupport connectSupport() {
        return mock(ConnectSupport.class);
    }

}
