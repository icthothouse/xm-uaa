package com.icthh.xm.uaa.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.icthh.xm.commons.security.XmAuthenticationContextHolder;
import com.icthh.xm.commons.tenant.TenantContextHolder;
import com.icthh.xm.commons.tenant.TenantContextUtils;
import com.icthh.xm.lep.api.LepManager;
import com.icthh.xm.uaa.UaaApp;
import com.icthh.xm.uaa.config.xm.XmOverrideConfiguration;
import com.icthh.xm.uaa.domain.User;
import com.icthh.xm.uaa.domain.properties.TenantProperties;
import com.icthh.xm.uaa.security.ldap.LdapAuthenticationProviderBuilder;
import com.icthh.xm.uaa.service.TenantPropertiesService;
import com.icthh.xm.uaa.service.UserService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.util.StreamUtils;
import org.zapodot.junit.ldap.EmbeddedLdapRule;
import org.zapodot.junit.ldap.EmbeddedLdapRuleBuilder;

import java.nio.charset.Charset;
import java.util.Optional;

import static com.icthh.xm.commons.lep.XmLepConstants.THREAD_CONTEXT_KEY_AUTH_CONTEXT;
import static com.icthh.xm.commons.lep.XmLepConstants.THREAD_CONTEXT_KEY_TENANT_CONTEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = {
    UaaApp.class,
    XmOverrideConfiguration.class
})
public class UaaAuthenticationProviderIntTest {

    private static final String TENANT = "XM";
    private static final String BASE_DN = "dc=xm,dc=com";
    private static final String TEST_LDIF = "config/test.ldif";
    private static final int LDAP_SERVER_PORT = 1389;

    private static final String TEST_PASSWORD = "test";
    private static final String TEST_USER = "test@xm.com";
    private static final String TEST_USER_UNKNOWN_ROLE = "test-unknown-role@xm.com";
    private static final String TEST_USER_MANY_ROLE = "test-two-roles@xm.com";

    private static final String DEFAULT_FIRSTNAME = "Test";
    private static final String DEFAULT_LASTNAME = "User";

    private static final String DEFAULT_ROLE_KEY = "ROLE-DEFAULT-USER";
    private static final String DEFAULT_USER_ROLE_KEY = "ROLE-USER";
    private static final String DEFAULT_ADMIN_ROLE_KEY = "SUPER-ADMIN";

    private ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    @Value("${:classpath:config/tenants/XM/uaa/uaa.yml}")
    private Resource spec;

    @Autowired
    private DaoAuthenticationProvider daoAuthenticationProvider;

    @Autowired
    private DomainUserDetailsService userDetailsService;

    @Autowired
    private UserService userService;

    @Autowired
    private LepManager lepManager;

    @Autowired
    private XmAuthenticationContextHolder authContextHolder;

    @Autowired
    private TenantContextHolder tenantContextHolder;

    private UaaAuthenticationProvider uaaAuthenticationProvider;

    @Rule
    public EmbeddedLdapRule embeddedLdapRule = EmbeddedLdapRuleBuilder
        .newInstance()
        .usingDomainDsn(BASE_DN)
        .importingLdifs(TEST_LDIF)
        .bindingToPort(LDAP_SERVER_PORT)
        .build();

    @BeforeTransaction
    public void beforeTransaction() {
        TenantContextUtils.setTenant(tenantContextHolder, TENANT);
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        TenantContextUtils.setTenant(tenantContextHolder, TENANT);

        TenantPropertiesService tenantPropertiesService = mock(TenantPropertiesService.class);
        String conf = StreamUtils.copyToString(spec.getInputStream(), Charset.defaultCharset());
        TenantProperties tenantProperties = mapper.readValue(conf, TenantProperties.class);
        when(tenantPropertiesService.getTenantProps()).thenReturn(tenantProperties);

        LdapAuthenticationProviderBuilder providerBuilder =
            new LdapAuthenticationProviderBuilder(tenantPropertiesService, userDetailsService, userService);

        uaaAuthenticationProvider = new UaaAuthenticationProvider(daoAuthenticationProvider,
                                                                  providerBuilder,
                                                                  userService,
                                                                  tenantPropertiesService);

        lepManager.beginThreadContext(ctx -> {
            ctx.setValue(THREAD_CONTEXT_KEY_TENANT_CONTEXT, tenantContextHolder.getContext());
            ctx.setValue(THREAD_CONTEXT_KEY_AUTH_CONTEXT, authContextHolder.getContext());
        });
    }

    @After
    @Override
    public void finalize() {
        lepManager.endThreadContext();
    }

    @Test
    public void testFirstLdapAuth() {
        Optional<User> userOpt = userService.findOneByLogin(TEST_USER);
        assertFalse(userOpt.isPresent());

        Authentication authentication = uaaAuthenticationProvider.authenticate(
            new UsernamePasswordAuthenticationToken(TEST_USER, TEST_PASSWORD));

        commonAsserts(TEST_USER, DEFAULT_USER_ROLE_KEY, authentication);
    }

    @Test
    public void testCheckPasswordExpirationSuccess() {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(TEST_USER, TEST_PASSWORD);
        Authentication authentication = uaaAuthenticationProvider.authenticate(token);

        DomainUserDetails domainUserDetails = (DomainUserDetails) authentication.getPrincipal();
        User user = userService.getUser(domainUserDetails.getUserKey());
        //passwordExpirationPeriod = 90 days
        user.setUpdatePasswordDate(Instant.now().minus(89, ChronoUnit.DAYS));
        userService.saveUser(user);

        uaaAuthenticationProvider.authenticate(token);
    }

    @Test
    public void testLdapAuthUnknownRole() {
        Optional<User> userOpt = userService.findOneByLogin(TEST_USER_UNKNOWN_ROLE);
        assertFalse(userOpt.isPresent());

        Authentication authentication = uaaAuthenticationProvider.authenticate(
            new UsernamePasswordAuthenticationToken(TEST_USER_UNKNOWN_ROLE, TEST_PASSWORD));

        commonAsserts(TEST_USER_UNKNOWN_ROLE, DEFAULT_ROLE_KEY, authentication);
    }

    @Test
    public void testLdapAuthManyRoles() {
        Optional<User> userOpt = userService.findOneByLogin(TEST_USER_MANY_ROLE);
        assertFalse(userOpt.isPresent());

        Authentication authentication = uaaAuthenticationProvider.authenticate(
            new UsernamePasswordAuthenticationToken(TEST_USER_MANY_ROLE, TEST_PASSWORD));

        //the last one must be assigned
        commonAsserts(TEST_USER_MANY_ROLE, DEFAULT_ADMIN_ROLE_KEY, authentication);
    }

    private void commonAsserts(String login, String role, Authentication authentication) {
        //check authentication
        assertTrue(authentication.isAuthenticated());
        assertFalse(authentication.getAuthorities().isEmpty());
        assertEquals(authentication.getAuthorities().iterator().next().getAuthority(), role);
        assertTrue(authentication.getPrincipal() instanceof DomainUserDetails);

        //check principals
        DomainUserDetails details = DomainUserDetails.class.cast(authentication.getPrincipal());
        assertTrue(details.isAccountNonExpired());
        assertTrue(details.isAccountNonLocked());
        assertTrue(details.isCredentialsNonExpired());

        //check db user
        Optional<User> newUserOpt = userService.findOneByLogin(login);
        assertTrue(newUserOpt.isPresent());

        User newUser = newUserOpt.get();

        assertEquals(newUser.getFirstName(), DEFAULT_FIRSTNAME);
        assertEquals(newUser.getLastName(), DEFAULT_LASTNAME);
        assertEquals(newUser.getRoleKey(), role);
        assertFalse(newUser.getLogins().isEmpty());
        assertEquals(newUser.getLogins().iterator().next().getLogin(), login);
    }
}
