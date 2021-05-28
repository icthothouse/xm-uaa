package com.icthh.xm.uaa.web.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.icthh.xm.commons.i18n.error.web.ExceptionTranslator;
import com.icthh.xm.commons.permission.constants.RoleConstant;
import com.icthh.xm.commons.security.XmAuthenticationContextHolder;
import com.icthh.xm.commons.tenant.TenantContextHolder;
import com.icthh.xm.commons.tenant.TenantContextUtils;
import com.icthh.xm.lep.api.LepManager;
import com.icthh.xm.uaa.UaaApp;
import com.icthh.xm.uaa.commons.XmRequestContextHolder;
import com.icthh.xm.uaa.config.xm.XmOverrideConfiguration;
import com.icthh.xm.uaa.domain.User;
import com.icthh.xm.uaa.domain.UserLogin;
import com.icthh.xm.uaa.domain.UserLoginType;
import com.icthh.xm.uaa.domain.properties.TenantProperties;
import com.icthh.xm.uaa.repository.UserRepository;
import com.icthh.xm.uaa.repository.kafka.ProfileEventProducer;
import com.icthh.xm.uaa.service.TenantPropertiesService;
import com.icthh.xm.uaa.service.UserLoginService;
import com.icthh.xm.uaa.service.UserMailService;
import com.icthh.xm.uaa.service.UserService;
import com.icthh.xm.uaa.service.dto.UserDTO;
import com.icthh.xm.uaa.service.mapper.UserMapper;
import com.icthh.xm.uaa.service.query.UserQueryService;
import com.icthh.xm.uaa.web.rest.vm.ManagedUserVM;

import java.lang.reflect.Field;
import java.util.function.Consumer;
import lombok.SneakyThrows;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.authentication.OAuth2AuthenticationDetails;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ReflectionUtils;

import javax.persistence.EntityManager;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.icthh.xm.commons.lep.XmLepConstants.THREAD_CONTEXT_KEY_TENANT_CONTEXT;
import static com.icthh.xm.commons.lep.XmLepScriptConstants.BINDING_KEY_AUTH_CONTEXT;
import static com.icthh.xm.uaa.UaaTestConstants.DEFAULT_TENANT_KEY_VALUE;
import static com.icthh.xm.uaa.web.constant.ErrorConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test class for the UserResource REST controller.
 *
 * @see UserResource
 */
@RunWith(SpringRunner.class)
@SpringBootTest(classes = {
    UaaApp.class,
    XmOverrideConfiguration.class
})
@WithMockUser(authorities = {"SUPER-ADMIN"})
public class UserResourceIntTest {

    private static final Long DEFAULT_ID = 1L;

    private static final String DEFAULT_LOGIN = "johndoe";

    private static final String DEFAULT_PASSWORD = "passjohndoe";
    private static final String UPDATED_PASSWORD = "passjhipster";

    private static final String DEFAULT_FIRSTNAME = "john";
    private static final String UPDATED_FIRSTNAME = "jhipsterFirstName";

    private static final String DEFAULT_LASTNAME = "doe";
    private static final String UPDATED_LASTNAME = "jhipsterLastName";

    private static final String DEFAULT_IMAGEURL = "http://placehold.it/50x50";
    private static final String UPDATED_IMAGEURL = "http://placehold.it/40x40";

    private static final String DEFAULT_LANGKEY = "en";
    private static final String UPDATED_LANGKEY = "fr";

    private static final String ROLE_USER = "ROLE_USER";
    private static final String UPDATE_ROLE_USER = "UPDATED_ROLE_USER";

    private static final boolean AUTO_LOGOUT_ENABLED = true;
    private static final int AUTO_LOGOUT_TIME = 10;

    @Autowired
    private UserLoginService userLoginService;

    @Autowired
    private UserQueryService userQueryService;

    @Autowired
    private UserRepository userRepository;

    @Mock
    private UserMailService mailService;

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private MappingJackson2HttpMessageConverter jacksonMessageConverter;

    @Autowired
    private PageableHandlerMethodArgumentResolver pageableArgumentResolver;

    @Autowired
    private ExceptionTranslator exceptionTranslator;

    @Autowired
    private EntityManager em;

    @Autowired
    private TenantContextHolder tenantContextHolder;

    @Autowired
    private XmRequestContextHolder xmRequestContextHolder;

    @Autowired
    private TenantPropertiesService tenantPropertiesService;

    @Mock
    private ProfileEventProducer profileEventProducer;

    private MockMvc restUserMockMvc;

    private User user;

    private User superAdminUser;

    @Autowired
    private LepManager lepManager;

    @Autowired
    private XmAuthenticationContextHolder xmAuthenticationContextHolder;

    @BeforeTransaction
    public void beforeTransaction() {
        TenantContextUtils.setTenant(tenantContextHolder, DEFAULT_TENANT_KEY_VALUE);
        lepManager.beginThreadContext(scopedContext -> {
            scopedContext.setValue(THREAD_CONTEXT_KEY_TENANT_CONTEXT, tenantContextHolder.getContext());
            scopedContext.setValue(BINDING_KEY_AUTH_CONTEXT, xmAuthenticationContextHolder.getContext());
        });

    }

    @SneakyThrows
    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        TenantContextUtils.setTenant(tenantContextHolder, DEFAULT_TENANT_KEY_VALUE);
        setTenantProps(tenantProperties -> {});
        doNothing().when(profileEventProducer).send(any());
        UserResource userResource = new UserResource(userLoginService,
            mailService,
            userService,
            profileEventProducer,
            userQueryService);
        this.restUserMockMvc = MockMvcBuilders.standaloneSetup(userResource)
            .setCustomArgumentResolvers(pageableArgumentResolver)
            .setControllerAdvice(exceptionTranslator)
            .setMessageConverters(jacksonMessageConverter)
            .build();
        userRepository.deleteAll();
        user = createEntity(ROLE_USER);
        superAdminUser = createEntity(RoleConstant.SUPER_ADMIN);
    }

    @After
    public void destroy() {
        tenantContextHolder.getPrivilegedContext().destroyCurrentContext();
        lepManager.endThreadContext();
    }

    /**
     * Create a User.
     * <p>
     * This is a static method, as tests for other entities might also need it,
     * if they test an entity which has a required relationship to the User entity.
     */
    public static User createEntity(String userRoleKey) {
        User user = new User();
        user.setPassword(RandomStringUtils.random(60));
        user.setActivated(true);
        user.setFirstName(DEFAULT_FIRSTNAME);
        user.setLastName(DEFAULT_LASTNAME);
        user.setImageUrl(DEFAULT_IMAGEURL);
        user.setLangKey(DEFAULT_LANGKEY);
        user.setUserKey(UUID.randomUUID().toString());
        user.setRoleKey(userRoleKey);

        UserLogin userLogin = new UserLogin();
        userLogin.setUser(user);
        userLogin.setLogin("test");
        userLogin.setTypeKey(UserLoginType.EMAIL.getValue());
        user.getLogins().add(userLogin);
        return user;
    }

    @Before
    public void initTest() {
        user = createEntity(ROLE_USER);
    }

    @Test
    @Transactional
    public void createUser() throws Exception {
        int databaseSizeBeforeCreate = userRepository.findAll().size();

        // Create the User
        UserLogin userLogin = new UserLogin();
        userLogin.setLogin("test");
        userLogin.setTypeKey(UserLoginType.EMAIL.getValue());
        ManagedUserVM managedUserVM = new ManagedUserVM(
            null,
            DEFAULT_PASSWORD,
            DEFAULT_FIRSTNAME,
            DEFAULT_LASTNAME,
            true,
            false,
            null,
            null,
            DEFAULT_IMAGEURL,
            DEFAULT_LANGKEY,
            null,
            null,
            null,
            null,
            ROLE_USER, "test", null, null, null, null, Collections.singletonList(userLogin),
            AUTO_LOGOUT_ENABLED,
            AUTO_LOGOUT_TIME,
            null, List.of("test"));

        restUserMockMvc.perform(post("/api/users")
            .contentType(TestUtil.APPLICATION_JSON_UTF8)
            .content(TestUtil.convertObjectToJsonBytes(managedUserVM)))
            .andExpect(status().isCreated());

        // Validate the User in the database
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeCreate + 1);
        User testUser = userList.get(userList.size() - 1);
        assertThat(testUser.getFirstName()).isEqualTo(DEFAULT_FIRSTNAME);
        assertThat(testUser.getLastName()).isEqualTo(DEFAULT_LASTNAME);
        assertThat(testUser.getImageUrl()).isEqualTo(DEFAULT_IMAGEURL);
        assertThat(testUser.getLangKey()).isEqualTo(DEFAULT_LANGKEY);
        assertThat(testUser.isAutoLogoutEnabled()).isEqualTo(AUTO_LOGOUT_ENABLED);
        assertThat(testUser.getAutoLogoutTimeoutSeconds()).isEqualTo(AUTO_LOGOUT_TIME);
    }

    @Test
    @Transactional
    public void createUserWithExistingId() throws Exception {
        int databaseSizeBeforeCreate = userRepository.findAll().size();

        UserLogin userLogin = new UserLogin();
        userLogin.setLogin("test");
        userLogin.setTypeKey(UserLoginType.EMAIL.getValue());
        ManagedUserVM managedUserVM = new ManagedUserVM(
            1L,
            DEFAULT_PASSWORD,
            DEFAULT_FIRSTNAME,
            DEFAULT_LASTNAME,
            true,
            false,
            null,
            null,
            DEFAULT_IMAGEURL,
            DEFAULT_LANGKEY,
            null,
            null,
            null,
            null,
            ROLE_USER, "test", null, null,
            null, null, Collections.singletonList(userLogin),
            false,
            null, null, List.of("test"));

        // An entity with an existing ID cannot be created, so this API call must fail
        restUserMockMvc.perform(post("/api/users")
            .contentType(TestUtil.APPLICATION_JSON_UTF8)
            .content(TestUtil.convertObjectToJsonBytes(managedUserVM)))
            .andExpect(status().isBadRequest())
            .andExpect(MockMvcResultMatchers.header().string("X-uaaApp-error", "error.idexists"));

        // Validate the User in the database
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeCreate);
    }

    @Test
    @Transactional
    public void createSuperAdminUser() throws Exception {
        initSecurityContextWithUserKey("test");

        int databaseSizeBeforeCreate = userRepository.findAll().size();

        UserLogin userLogin = new UserLogin();
        userLogin.setLogin("test");
        userLogin.setTypeKey(UserLoginType.EMAIL.getValue());
        ManagedUserVM managedUserVM = new ManagedUserVM(
            null,
            DEFAULT_PASSWORD,
            DEFAULT_FIRSTNAME,
            DEFAULT_LASTNAME,
            true,
            false,
            null,
            null,
            DEFAULT_IMAGEURL,
            DEFAULT_LANGKEY,
            null,
            null,
            null,
            null,
            "test", RoleConstant.SUPER_ADMIN, null, null, null, null,
            Collections.singletonList(userLogin), false, null, null, List.of(RoleConstant.SUPER_ADMIN));


        // SUPER-ADMIN entity cannot be created, so this API call must fail
        restUserMockMvc.perform(post("/api/users")
            .contentType(TestUtil.APPLICATION_JSON_UTF8)
            .content(TestUtil.convertObjectToJsonBytes(managedUserVM)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(ERROR_SUPER_ADMIN_FORBIDDEN_OPERATION));

        // Validate that the new SUPER-ADMIN wasn't created
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeCreate);
    }

    @Test
    @Transactional
    public void createUserWithExistingEmail() throws Exception {
        // Initialize the database
        userRepository.saveAndFlush(user);
        int databaseSizeBeforeCreate = userRepository.findAll().size();

        UserLogin userLogin = new UserLogin();
        userLogin.setLogin("test");
        userLogin.setTypeKey(UserLoginType.EMAIL.getValue());
        ManagedUserVM managedUserVM = new ManagedUserVM(
            null,
            DEFAULT_PASSWORD,
            DEFAULT_FIRSTNAME,
            DEFAULT_LASTNAME,
            true,
            false,
            null,
            null,
            DEFAULT_IMAGEURL,
            DEFAULT_LANGKEY,
            null,
            null,
            null,
            null,
            ROLE_USER, "test", null, null, null, null,
            Collections.singletonList(userLogin), false, null, null, List.of("test"));

        // Create the User
        restUserMockMvc.perform(post("/api/users")
            .contentType(TestUtil.APPLICATION_JSON_UTF8)
            .content(TestUtil.convertObjectToJsonBytes(managedUserVM)))
            .andExpect(status().isBadRequest());

        // Validate the User in the database
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeCreate);
    }

    @Test
    @Transactional
    public void getAllUsers() throws Exception {
        // Initialize the database

        String beforeKey = user.getUserKey();

        userRepository.saveAndFlush(user);

        // Get all the users
        restUserMockMvc.perform(get("/api/users?sort=id,desc")
            .accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$.[*].userKey").value(hasItem(beforeKey)))
            .andExpect(jsonPath("$.[*].firstName").value(hasItem(DEFAULT_FIRSTNAME)))
            .andExpect(jsonPath("$.[*].lastName").value(hasItem(DEFAULT_LASTNAME)))
            .andExpect(jsonPath("$.[*].logins[0].login").value("test"))
            .andExpect(jsonPath("$.[*].imageUrl").value(hasItem(DEFAULT_IMAGEURL)))
            .andExpect(jsonPath("$.[*].langKey").value(hasItem(DEFAULT_LANGKEY)));
    }

    @Test
    @Transactional
    public void getUser() throws Exception {
        // Initialize the database
        userRepository.saveAndFlush(user);

        // Get the user
        restUserMockMvc.perform(get("/api/users/{userKey}", user.getUserKey()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$.userKey").value(user.getUserKey()))
            .andExpect(jsonPath("$.firstName").value(DEFAULT_FIRSTNAME))
            .andExpect(jsonPath("$.lastName").value(DEFAULT_LASTNAME))
            .andExpect(jsonPath("$.imageUrl").value(DEFAULT_IMAGEURL))
            .andExpect(jsonPath("$.langKey").value(DEFAULT_LANGKEY))
            .andExpect(jsonPath("$.logins[0].login").value("test"));
    }

    @Test
    @Transactional
    public void findUserByFilter() throws Exception {
        String firstName = "homer";
        String lastName = "simpson";
        String login = "al-homero";
        userRepository.saveAndFlush(user);
        User userHomer = createEntity("ROLE_ADMIN");
        userHomer.setFirstName(firstName);
        userHomer.setLastName(lastName);
        userHomer.getLogins().get(0).setLogin(login);
        userHomer.getLogins().add(new UserLogin() {{
            setLogin("donutEater");
            setTypeKey(UserLoginType.EMAIL.getValue());
            setUser(userHomer);
        }});
        userRepository.saveAndFlush(userHomer);

        restUserMockMvc.perform(get("/api/users/filter?firstName.contains=er", userHomer.getUserKey()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$[0].userKey").value(userHomer.getUserKey()))
            .andExpect(jsonPath("$[0].firstName").value(firstName))
            .andExpect(jsonPath("$[0].lastName").value(lastName))
            .andExpect(jsonPath("$[0].imageUrl").value(DEFAULT_IMAGEURL))
            .andExpect(jsonPath("$[0].langKey").value(DEFAULT_LANGKEY))
            .andExpect(jsonPath("$[0].logins[0].login").value(login));

        restUserMockMvc.perform(get("/api/users/filter?lastName.equals=simpson", userHomer.getUserKey()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$[0].userKey").value(userHomer.getUserKey()))
            .andExpect(jsonPath("$[0].firstName").value(firstName))
            .andExpect(jsonPath("$[0].lastName").value(lastName))
            .andExpect(jsonPath("$[0].imageUrl").value(DEFAULT_IMAGEURL))
            .andExpect(jsonPath("$[0].langKey").value(DEFAULT_LANGKEY))
            .andExpect(jsonPath("$[0].logins[0].login").value(login));


        restUserMockMvc.perform(get("/api/users/filter?login.contains=e&sort=logins.login", userHomer.getUserKey()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$[0].firstName").value(firstName))
            .andExpect(jsonPath("$", hasSize(2)));

        restUserMockMvc.perform(get("/api/users/filter?roleKey.contains=ADMIN", userHomer.getUserKey()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$[0].firstName").value(firstName))
            .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @Transactional
    public void findUserBySoftFilter() throws Exception {
        String firstName = "homer";
        String lastName = "simpson";
        String login = "al-homero";
        userRepository.saveAndFlush(user);
        User userHomer = createEntity("ROLE_ADMIN");
        userHomer.setFirstName(firstName);
        userHomer.setLastName(lastName);
        userHomer.getLogins().get(0).setLogin(login);
        userHomer.getLogins().add(new UserLogin() {{
            setLogin("donutEater");
            setTypeKey(UserLoginType.EMAIL.getValue());
            setUser(userHomer);
        }});
        userRepository.saveAndFlush(userHomer);

        restUserMockMvc.perform(get("/api/users/filter-soft?query.contains=simps"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$[0].userKey").value(userHomer.getUserKey()))
            .andExpect(jsonPath("$[0].firstName").value(firstName))
            .andExpect(jsonPath("$[0].lastName").value(lastName))
            .andExpect(jsonPath("$[0].imageUrl").value(DEFAULT_IMAGEURL))
            .andExpect(jsonPath("$[0].langKey").value(DEFAULT_LANGKEY))
            .andExpect(jsonPath("$[0].logins[0].login").value(login));

        // search by LastName case insensitive
        restUserMockMvc.perform(get("/api/users/filter-soft?query.contains=SiMpS"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$[0].userKey").value(userHomer.getUserKey()));

        // search by FirstName case insensitive
        restUserMockMvc.perform(get("/api/users/filter-soft?query.contains=hOm"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$[0].userKey").value(userHomer.getUserKey()));

        // search by Login case insensitive
        restUserMockMvc.perform(get("/api/users/filter-soft?query.contains=NuT"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$[0].userKey").value(userHomer.getUserKey()));

        // search by Name and Role SUCCESS
        restUserMockMvc.perform(get("/api/users/filter-soft?query.contains=nut&roleKey.equals=ROLE_ADMIN"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$[0].userKey").value(userHomer.getUserKey()));

        // search by Name and Role FAILED
        restUserMockMvc.perform(get("/api/users/filter-soft?query.contains=nut&roleKey.equals=ROLE_UNKNOWN"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(content().string("[]"));

        // search by Name and Role and Activated SUCCESS
        restUserMockMvc.perform(get("/api/users/filter-soft?query.contains=nut&roleKey.equals=ROLE_ADMIN&activated.equals=true"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$[0].userKey").value(userHomer.getUserKey()));

        // search by Name and Role and Activated FAILED
        restUserMockMvc.perform(get("/api/users/filter-soft?query.contains=nut&roleKey.equals=ROLE_ADMIN&activated.equals=false"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(content().string("[]"));
    }

    @Test
    @Transactional
    public void getUserByLoginContains() throws Exception {
        userRepository.saveAndFlush(user);
        getUsersByLoginContainsMatcher("test");
        getUsersByLoginContainsMatcher("tE");
        getUsersByLoginContainsMatcher("St");

        restUserMockMvc.perform(get("/api/users/logins-contains?login=wrong-login"))
              .andDo(print())
              .andExpect(status().isOk())
              .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
              .andExpect(content().json("[]"));
    }

    private void getUsersByLoginContainsMatcher(String login) throws Exception {
        restUserMockMvc.perform(get("/api/users/logins-contains?login={login}", login))
              .andDo(print())
              .andExpect(status().isOk())
              .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
              .andExpect(jsonPath("$[0].userKey").value(user.getUserKey()))
              .andExpect(jsonPath("$[0].firstName").value(DEFAULT_FIRSTNAME))
              .andExpect(jsonPath("$[0].lastName").value(DEFAULT_LASTNAME))
              .andExpect(jsonPath("$[0].imageUrl").value(DEFAULT_IMAGEURL))
              .andExpect(jsonPath("$[0].langKey").value(DEFAULT_LANGKEY))
              .andExpect(jsonPath("$[0].logins[0].login").value("test"));
    }

    @Test
    @Transactional
    public void getPublicUser() throws Exception {
        // Initialize the database
        userRepository.saveAndFlush(user);

        // Get the user
        restUserMockMvc.perform(get("/api/users/{userKey}/public", user.getUserKey()))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON_UTF8_VALUE))
            .andExpect(jsonPath("$.userKey").value(user.getUserKey()))
            .andExpect(jsonPath("$.firstName").value(DEFAULT_FIRSTNAME))
            .andExpect(jsonPath("$.lastName").value(DEFAULT_LASTNAME))
            .andExpect(jsonPath("$.imageUrl").value(DEFAULT_IMAGEURL));
    }

    @Test
    @Transactional
    public void getNonExistingUser() throws Exception {
        restUserMockMvc.perform(get("/api/users/unknown"))
            .andExpect(status().isNotFound());
    }

    @Test
    @Transactional
    public void updateUser() throws Exception {
        // Initialize the database
        userRepository.saveAndFlush(user);
        int databaseSizeBeforeUpdate = userRepository.findAll().size();

        // Update the user
        Optional<User> updatedUserOpt = userRepository.findById(user.getId());
        assertTrue(updatedUserOpt.isPresent());
        User updatedUser = updatedUserOpt.get();

        UserLogin userLogin = new UserLogin();
        userLogin.setLogin("test");
        userLogin.setTypeKey(UserLoginType.EMAIL.getValue());
        ManagedUserVM managedUserVM = new ManagedUserVM(
            updatedUser.getId(),
            UPDATED_PASSWORD,
            UPDATED_FIRSTNAME,
            UPDATED_LASTNAME,
            //invert user state
            !updatedUser.isActivated(),
            updatedUser.isTfaEnabled(),
            updatedUser.getTfaOtpChannelType(),
            null,
            UPDATED_IMAGEURL,
            UPDATED_LANGKEY,
            updatedUser.getCreatedBy(),
            updatedUser.getCreatedDate(),
            updatedUser.getLastModifiedBy(),
            updatedUser.getLastModifiedDate(),
            "testUserKey",
            //change user role
            ROLE_USER + "XXX", null, null, null, null, Collections.singletonList(userLogin),
            AUTO_LOGOUT_ENABLED,
            AUTO_LOGOUT_TIME,
            null, List.of(ROLE_USER + "XXX"));

        restUserMockMvc.perform(put("/api/users")
            .contentType(TestUtil.APPLICATION_JSON_UTF8)
            .content(TestUtil.convertObjectToJsonBytes(managedUserVM)))
            .andExpect(status().isOk());

        // Validate the User in the database
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeUpdate);
        User testUser = userList.get(userList.size() - 1);
        assertThat(testUser.getFirstName()).isEqualTo(UPDATED_FIRSTNAME);
        assertThat(testUser.getLastName()).isEqualTo(UPDATED_LASTNAME);
        assertThat(testUser.getImageUrl()).isEqualTo(UPDATED_IMAGEURL);
        assertThat(testUser.getLangKey()).isEqualTo(UPDATED_LANGKEY);
        assertThat(testUser.isAutoLogoutEnabled()).isEqualTo(AUTO_LOGOUT_ENABLED);
        assertThat(testUser.getAutoLogoutTimeoutSeconds()).isEqualTo(AUTO_LOGOUT_TIME);
        //ASSERT THAT STATE IS CHANGED
        assertThat(testUser.isActivated()).isEqualTo(managedUserVM.isActivated());
        //ASSERT THAT ROLE IS CHANGED
        assertThat(testUser.getAuthorities()).isEqualTo(managedUserVM.getAuthorities());
    }

    @Test
    @Transactional
    public void updateUserRole() throws Exception {
        // Initialize the database
        userRepository.saveAndFlush(user);
        int databaseSizeBeforeUpdate = userRepository.findAll().size();

        // Update the user
        Optional<User> userForUpdateOpt = userRepository.findById(user.getId());
        assertTrue(userForUpdateOpt.isPresent());
        User userForUpdate = userForUpdateOpt.get();

        UserDTO dto = new UserDTO(userForUpdate);
        Field roleKey = dto.getClass().getDeclaredField("roleKey");
        roleKey.setAccessible(true);
        roleKey.set(dto, UPDATE_ROLE_USER);
        assertEquals(List.of(ROLE_USER), dto.getAuthorities());
        assertEquals(UPDATE_ROLE_USER, dto.getRoleKey());

        restUserMockMvc.perform(put("/api/users")
                .contentType(TestUtil.APPLICATION_JSON_UTF8)
                .content(TestUtil.convertObjectToJsonBytes(dto)))
                .andExpect(status().isOk());

        // Validate the User in the database
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeUpdate);
        User testUser = userList.get(userList.size() - 1);

        assertThat(new UserDTO(testUser).getRoleKey()).isEqualTo(UPDATE_ROLE_USER);
    }

    @Test
    @Transactional
    public void updateUserLogin() throws Exception {
        // Initialize the database
        userRepository.saveAndFlush(user);
        int databaseSizeBeforeUpdate = userRepository.findAll().size();

        // Update the user
        Optional<User> updatedUserOpt = userRepository.findById(user.getId());
        assertTrue(updatedUserOpt.isPresent());
        User updatedUser = updatedUserOpt.get();

        UserLogin userLoginNew = new UserLogin();
        userLoginNew.setLogin("testMail3");
        userLoginNew.setTypeKey(UserLoginType.EMAIL.getValue());
        ManagedUserVM managedUserVM = new ManagedUserVM(
            updatedUser.getId(),
            UPDATED_PASSWORD,
            UPDATED_FIRSTNAME,
            UPDATED_LASTNAME,
            updatedUser.isActivated(),
            updatedUser.isTfaEnabled(),
            updatedUser.getTfaOtpChannelType(),
            null,
            UPDATED_IMAGEURL,
            UPDATED_LANGKEY,
            updatedUser.getCreatedBy(),
            updatedUser.getCreatedDate(),
            updatedUser.getLastModifiedBy(),
            updatedUser.getLastModifiedDate(),
            ROLE_USER, "test", null, null, null, null,
            Collections.singletonList(userLoginNew), false, null, null, List.of("test"));

        restUserMockMvc.perform(put("/api/users")
            .contentType(TestUtil.APPLICATION_JSON_UTF8)
            .content(TestUtil.convertObjectToJsonBytes(managedUserVM)))
            .andExpect(status().isOk());

        // Validate the User in the database
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeUpdate);
        User testUser = userList.get(userList.size() - 1);
        assertThat(testUser.getFirstName()).isEqualTo(UPDATED_FIRSTNAME);
        assertThat(testUser.getLastName()).isEqualTo(UPDATED_LASTNAME);
        assertThat(testUser.getImageUrl()).isEqualTo(UPDATED_IMAGEURL);
        assertThat(testUser.getLangKey()).isEqualTo(UPDATED_LANGKEY);
        assertThat(testUser.getLogins().size()).isEqualTo(1);
        assertThat(testUser.getLogins().get(0).getLogin()).isEqualTo("test");

    }

    @Test
    @Transactional
    public void updateUserExistingEmail() throws Exception {
        // Initialize the database with 2 users
        userRepository.saveAndFlush(user);

        User anotherUser = new User();
        anotherUser.setPassword(RandomStringUtils.random(60));
        anotherUser.setActivated(true);
        anotherUser.setFirstName("java");
        anotherUser.setLastName("hipster");
        anotherUser.setImageUrl("");
        anotherUser.setLangKey("en");
        anotherUser.setUserKey("test");
        UserLogin userLogin = new UserLogin();
        userLogin.setUser(anotherUser);
        userLogin.setLogin("testMail");
        userLogin.setTypeKey(UserLoginType.EMAIL.getValue());
        anotherUser.getLogins().add(userLogin);
        anotherUser.setRoleKey(ROLE_USER);
        userRepository.saveAndFlush(anotherUser);

        // Update the user
        Optional<User> updatedUserOpt = userRepository.findById(user.getId());
        assertTrue(updatedUserOpt.isPresent());
        User updatedUser = updatedUserOpt.get();

        UserLogin userLoginNew = new UserLogin();
        userLoginNew.setLogin("testMail");
        userLoginNew.setTypeKey(UserLoginType.EMAIL.getValue());
        ManagedUserVM managedUserVM = new ManagedUserVM(
            user.getId(),
            updatedUser.getPassword(),
            updatedUser.getFirstName(),
            updatedUser.getLastName(),
            updatedUser.isActivated(),
            updatedUser.isTfaEnabled(),
            updatedUser.getTfaOtpChannelType(),
            null,
            updatedUser.getImageUrl(),
            updatedUser.getLangKey(),
            updatedUser.getCreatedBy(),
            updatedUser.getCreatedDate(),
            updatedUser.getLastModifiedBy(),
            updatedUser.getLastModifiedDate(),
            ROLE_USER, "testNew", null, null, null, null,
            Collections.singletonList(userLoginNew), false, null, null, List.of("testNew"));

        restUserMockMvc.perform(put("/api/users/logins")
            .contentType(TestUtil.APPLICATION_JSON_UTF8)
            .content(TestUtil.convertObjectToJsonBytes(managedUserVM)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @Transactional
    public void updateSuperAdminUser() throws Exception {
        UserLogin userLogin = new UserLogin();
        userLogin.setLogin("testMail");
        userLogin.setTypeKey(UserLoginType.EMAIL.getValue());
        ManagedUserVM managedUserVM = new ManagedUserVM(
            null,
            DEFAULT_PASSWORD,
            DEFAULT_FIRSTNAME,
            DEFAULT_LASTNAME,
            true,
            false,
            null,
            null,
            DEFAULT_IMAGEURL,
            DEFAULT_LANGKEY,
            null,
            null,
            null,
            null,
            "test", RoleConstant.SUPER_ADMIN, null, null, null,
            null, Collections.singletonList(userLogin), false, null,
            null, List.of(RoleConstant.SUPER_ADMIN));

        restUserMockMvc.perform(put("/api/users")
            .contentType(TestUtil.APPLICATION_JSON_UTF8)
            .content(TestUtil.convertObjectToJsonBytes(managedUserVM)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(ERROR_SUPER_ADMIN_FORBIDDEN_OPERATION));
    }


    @Test
    @Transactional
    public void updateUserLogins() throws Exception {
        // Initialize the database with 2 users
        userRepository.saveAndFlush(user);

        // Update the user
        Optional<User> updatedUserOpt = userRepository.findById(user.getId());
        assertTrue(updatedUserOpt.isPresent());
        User updatedUser = updatedUserOpt.get();

        UserLogin userLoginNew = new UserLogin();
        userLoginNew.setLogin("testMail3");
        userLoginNew.setTypeKey(UserLoginType.EMAIL.getValue());
        ManagedUserVM managedUserVM = new ManagedUserVM(
            updatedUser.getId(),
            "newPassword",
            "newFirstName",
            "newLastName",
            updatedUser.isActivated(),
            updatedUser.isTfaEnabled(),
            updatedUser.getTfaOtpChannelType(),
            null,
            "newImageUrl",
            "fr",
            updatedUser.getCreatedBy(),
            updatedUser.getCreatedDate(),
            updatedUser.getLastModifiedBy(),
            updatedUser.getLastModifiedDate(),
            updatedUser.getUserKey(), RoleConstant.SUPER_ADMIN, null, null, null, null, Collections.singletonList(userLoginNew), false, null,
            null, List.of(RoleConstant.SUPER_ADMIN));

        restUserMockMvc.perform(put("/api/users/logins")
            .contentType(TestUtil.APPLICATION_JSON_UTF8)
            .content(TestUtil.convertObjectToJsonBytes(managedUserVM)))
            .andExpect(status().isOk());

        // Validate the User in the database
        Optional<User> resultOpt = userRepository.findById(user.getId());
        assertTrue(resultOpt.isPresent());
        User result = resultOpt.get();
        assertThat(result.getFirstName()).isEqualTo(DEFAULT_FIRSTNAME);
        assertThat(result.getLastName()).isEqualTo(DEFAULT_LASTNAME);
        assertThat(result.getImageUrl()).isEqualTo(DEFAULT_IMAGEURL);
        assertThat(result.getLangKey()).isEqualTo(DEFAULT_LANGKEY);
        assertThat(result.getLogins().size()).isEqualTo(1);
        assertThat(result.getLogins().get(0).getLogin()).isEqualTo("testmail3");
    }


    @Test
    @Transactional
    public void deleteUser() throws Exception {
        initSecurityContextWithUserKey("test");

        // Initialize the database
        userRepository.saveAndFlush(user);
        int databaseSizeBeforeDelete = userRepository.findAll().size();

        // Delete the user
        restUserMockMvc.perform(delete("/api/users/{userKey}", user.getUserKey())
            .accept(TestUtil.APPLICATION_JSON_UTF8))
            .andExpect(status().isOk());

        // Validate the database is empty
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeDelete - 1);
    }

    @Test
    @Transactional
    public void forbidDeleteHimself() throws Exception {
        initSecurityContextWithUserKey(user.getUserKey());

        // Initialize the database
        userRepository.saveAndFlush(user);
        int databaseSizeBeforeDelete = userRepository.findAll().size();

        // Delete the user
        restUserMockMvc.perform(delete("/api/users/{userKey}", user.getUserKey())
            .accept(TestUtil.APPLICATION_JSON_UTF8))
            .andExpect(status().isBadRequest());

        // Validate the database is empty
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeDelete);
    }

    @Test
    @Transactional
    public void forbidDeleteSuperAdmin() throws Exception {
        initSecurityContextWithUserKey(user.getUserKey());

        // Initialize the database
        userRepository.saveAndFlush(superAdminUser);
        int databaseSizeBeforeDelete = userRepository.findAll().size();

        // Delete the user
        restUserMockMvc.perform(delete("/api/users/{userKey}", superAdminUser.getUserKey())
            .accept(TestUtil.APPLICATION_JSON_UTF8))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(ERROR_SUPER_ADMIN_FORBIDDEN_OPERATION));

        // Validate that super-admin wasn't deleted
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeDelete);
    }

    @Test
    @Transactional
    public void shouldForbidBlockHimself() throws Exception {
        initSecurityContextWithUserKey(superAdminUser.getUserKey());

        // Initialize the database
        userRepository.saveAndFlush(superAdminUser);
        int databaseSizeBeforeDelete = userRepository.findAll().size();

        // Delete the user
        restUserMockMvc.perform(put("/api/users/{userKey}/block", superAdminUser.getUserKey())
            .accept(TestUtil.APPLICATION_JSON_UTF8))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(ERROR_USER_BLOCK_HIMSELF));

        // Validate that super-admin wasn't deleted
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeDelete);
    }

    @Test
    @Transactional
    public void shouldAllowBlockOther() throws Exception {
        initSecurityContextWithUserKey(superAdminUser.getUserKey());

        // Initialize the database
        userRepository.saveAndFlush(user);

        Optional<User> resultOpt = userRepository.findById(user.getId());
        assertTrue(resultOpt.isPresent());
        User result = resultOpt.get();
        assertThat(result.isActivated()).isEqualTo(Boolean.TRUE);

        // Delete the user
        restUserMockMvc.perform(put("/api/users/{userKey}/block", user.getUserKey())
            .accept(TestUtil.APPLICATION_JSON_UTF8))
            .andExpect(status().isOk());

        resultOpt = userRepository.findById(user.getId());
        assertTrue(resultOpt.isPresent());
        result = resultOpt.get();
        assertThat(result.isActivated()).isEqualTo(Boolean.FALSE);
    }

    @Test
    @Transactional
    public void forbidActivateHimself() throws Exception {
        initSecurityContextWithUserKey(superAdminUser.getUserKey());

        // Initialize the database
        userRepository.saveAndFlush(superAdminUser);
        int databaseSizeBeforeDelete = userRepository.findAll().size();

        // Delete the user
        restUserMockMvc.perform(put("/api/users/{userKey}/activate", superAdminUser.getUserKey())
            .accept(TestUtil.APPLICATION_JSON_UTF8))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(ERROR_USER_ACTIVATES_HIMSELF));

        // Validate that super-admin wasn't deleted
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeDelete);
    }

    @Test
    @Transactional
    public void shouldAllowActivateOther() throws Exception {
        initSecurityContextWithUserKey(superAdminUser.getUserKey());

        // Initialize the database
        user.setActivated(false);
        userRepository.saveAndFlush(user);

        Optional<User> resultOpt = userRepository.findById(user.getId());
        assertTrue(resultOpt.isPresent());
        User result = resultOpt.get();
        assertThat(result.isActivated()).isEqualTo(Boolean.FALSE);

        // Delete the user
        restUserMockMvc.perform(put("/api/users/{userKey}/activate", user.getUserKey())
            .accept(TestUtil.APPLICATION_JSON_UTF8))
            .andExpect(status().isOk());

        resultOpt = userRepository.findById(user.getId());
        assertTrue(resultOpt.isPresent());
        result = resultOpt.get();
        assertThat(result.isActivated()).isEqualTo(Boolean.TRUE);
    }

    @Test
    @Transactional
    public void testUserEquals() throws Exception {
        TestUtil.equalsVerifier(User.class);
        User user1 = new User();
        user1.setId(1L);
        User user2 = new User();
        user2.setId(user1.getId());
        assertThat(user1).isEqualTo(user2);
        user2.setId(2L);
        assertThat(user1).isNotEqualTo(user2);
        user1.setId(null);
        assertThat(user1).isNotEqualTo(user2);
    }

    @Test
    public void testUserDTOtoUser() {
        UserDTO userDTO = new UserDTO(
            DEFAULT_ID,
            DEFAULT_FIRSTNAME,
            DEFAULT_LASTNAME,
            DEFAULT_IMAGEURL,
            true,
            false,
            null,
            null,
            DEFAULT_LANGKEY,
            DEFAULT_LOGIN,
            null,
            DEFAULT_LOGIN,
            null,
            "test", "testRoleKey", List.of("testRoleKey"),
            null, null, null, null,
            null, null, false, null,
            null);
        User user = userMapper.userDTOToUser(userDTO);
        assertThat(user.getId()).isEqualTo(DEFAULT_ID);
        assertThat(user.getFirstName()).isEqualTo(DEFAULT_FIRSTNAME);
        assertThat(user.getLastName()).isEqualTo(DEFAULT_LASTNAME);
        assertThat(user.isActivated()).isEqualTo(true);
        assertThat(user.getImageUrl()).isEqualTo(DEFAULT_IMAGEURL);
        assertThat(user.getLangKey()).isEqualTo(DEFAULT_LANGKEY);
        assertThat(user.getCreatedBy()).isNull();
        assertThat(user.getCreatedDate()).isNotNull();
        assertThat(user.getLastModifiedBy()).isNull();
        assertThat(user.getLastModifiedDate()).isNotNull();
        assertThat(user.getAuthorities()).isEqualTo(List.of("testRoleKey"));
    }

    @Test
    public void testUserToUserDTO() {
        user.setId(DEFAULT_ID);
        user.setCreatedBy(DEFAULT_LOGIN);
        user.setCreatedDate(Instant.now());
        user.setLastModifiedBy(DEFAULT_LOGIN);
        user.setLastModifiedDate(Instant.now());
        user.setRoleKey(ROLE_USER);

        UserDTO userDTO = userMapper.userToUserDTO(user);

        assertThat(userDTO.getId()).isEqualTo(DEFAULT_ID);
        assertThat(userDTO.getFirstName()).isEqualTo(DEFAULT_FIRSTNAME);
        assertThat(userDTO.getLastName()).isEqualTo(DEFAULT_LASTNAME);
        assertThat(userDTO.isActivated()).isEqualTo(true);
        assertThat(userDTO.getImageUrl()).isEqualTo(DEFAULT_IMAGEURL);
        assertThat(userDTO.getLangKey()).isEqualTo(DEFAULT_LANGKEY);
        assertThat(userDTO.getCreatedBy()).isEqualTo(DEFAULT_LOGIN);
        assertThat(userDTO.getCreatedDate()).isEqualTo(user.getCreatedDate());
        assertThat(userDTO.getLastModifiedBy()).isEqualTo(DEFAULT_LOGIN);
        assertThat(userDTO.getLastModifiedDate()).isEqualTo(user.getLastModifiedDate());
        assertThat(userDTO.getAuthorities()).isEqualTo(List.of(ROLE_USER));
        assertThat(userDTO.toString()).isNotNull();
    }

    @Test
    @Transactional
    public void createUserWithMultipleRoles() throws Exception {

        setTenantProps(tenantProperties -> tenantProperties
            .setSecurity(new TenantProperties.Security() {{
                setMultiRoleEnabled(true);
            }}));
        int databaseSizeBeforeCreate = userRepository.findAll().size();

        // Create the User
        UserLogin userLogin = new UserLogin();
        userLogin.setLogin("test");
        userLogin.setTypeKey(UserLoginType.EMAIL.getValue());
        ManagedUserVM managedUserVM = new ManagedUserVM(
            null,
            DEFAULT_PASSWORD,
            DEFAULT_FIRSTNAME,
            DEFAULT_LASTNAME,
            true,
            false,
            null,
            null,
            DEFAULT_IMAGEURL,
            DEFAULT_LANGKEY,
            null,
            null,
            null,
            null,
            ROLE_USER, "test", null, null,
            null, null, Collections.singletonList(userLogin),
            AUTO_LOGOUT_ENABLED,
            AUTO_LOGOUT_TIME,
            null, List.of("test", "ROLE_ADMIN"));

        restUserMockMvc.perform(post("/api/users")
            .contentType(TestUtil.APPLICATION_JSON_UTF8)
            .content(TestUtil.convertObjectToJsonBytes(managedUserVM)))
            .andExpect(status().isCreated());

        // Validate the User in the database
        List<User> userList = userRepository.findAll();
        assertThat(userList).hasSize(databaseSizeBeforeCreate + 1);
        User testUser = userList.get(userList.size() - 1);
        assertThat(testUser.getFirstName()).isEqualTo(DEFAULT_FIRSTNAME);
        assertThat(testUser.getLastName()).isEqualTo(DEFAULT_LASTNAME);
        assertThat(testUser.getImageUrl()).isEqualTo(DEFAULT_IMAGEURL);
        assertThat(testUser.getLangKey()).isEqualTo(DEFAULT_LANGKEY);
        assertThat(testUser.isAutoLogoutEnabled()).isEqualTo(AUTO_LOGOUT_ENABLED);
        assertThat(testUser.getAutoLogoutTimeoutSeconds()).isEqualTo(AUTO_LOGOUT_TIME);
        assertThat(testUser.getAuthorities()).contains("test", "ROLE_ADMIN");
    }

    private void initSecurityContextWithUserKey(String userKey) {
        Map<String, String> detailsMap = new HashMap<>();
        detailsMap.put("user_key", userKey);

        OAuth2AuthenticationDetails details = mock(OAuth2AuthenticationDetails.class);
        when(details.getDecodedDetails()).thenReturn(detailsMap);
        OAuth2Authentication authentication = mock(OAuth2Authentication.class);
        when(authentication.getDetails()).thenReturn(details);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @SneakyThrows
    private void setTenantProps(Consumer<TenantProperties> consumer){
        TenantProperties properties = new TenantProperties();
        TenantProperties.Security security = new TenantProperties.Security();
        security.setDefaultUserRole(ROLE_USER);
        properties.setSecurity(security);
        consumer.accept(properties);
        tenantPropertiesService.onRefresh("/config/tenants/" + DEFAULT_TENANT_KEY_VALUE + "/uaa/uaa.yml",
            new ObjectMapper(new YAMLFactory()).writeValueAsString(properties));
    }
}
