package com.icthh.xm.uaa.web.rest;

import static com.icthh.xm.uaa.web.rest.util.VerificationUtils.assertNotSuperAdmin;
import static org.apache.commons.collections.CollectionUtils.isNotEmpty;

import com.codahale.metrics.annotation.Timed;
import com.google.common.base.Preconditions;
import com.icthh.xm.commons.permission.annotation.PrivilegeDescription;
import com.icthh.xm.uaa.config.Constants;
import com.icthh.xm.uaa.domain.OtpChannelType;
import com.icthh.xm.uaa.domain.User;
import com.icthh.xm.uaa.repository.kafka.ProfileEventProducer;
import com.icthh.xm.uaa.service.UserLoginService;
import com.icthh.xm.uaa.service.UserMailService;
import com.icthh.xm.uaa.service.UserService;
import com.icthh.xm.uaa.service.dto.TfaEnableRequest;
import com.icthh.xm.uaa.service.dto.TfaOtpChannelSpec;
import com.icthh.xm.uaa.service.dto.UserDTO;
import com.icthh.xm.uaa.service.dto.UserPublicDTO;
import com.icthh.xm.uaa.service.query.UserQueryService;
import com.icthh.xm.uaa.service.query.filter.SoftUserFilterQuery;
import com.icthh.xm.uaa.service.query.filter.StrictUserFilterQuery;
import com.icthh.xm.uaa.web.rest.util.HeaderUtil;
import com.icthh.xm.uaa.web.rest.util.PaginationUtil;
import io.github.jhipster.web.util.ResponseUtil;
import io.swagger.annotations.ApiParam;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


/**
 * REST controller for managing users.
 * <p>
 * <p>This class accesses the User entity, and needs to fetch its collection of authorities.
 * <p>
 * <p>For a normal use-case, it would be better to have an eager relationship between User and Authority,
 * and send everything to the client side: there would be no View Model and DTO, a lot less code, and an outer-join
 * which would be good for performance.
 * <p>
 * <p>We use a View Model and a DTO for 3 reasons:
 * <ul>
 * <li>We want to keep a lazy association between the user and the authorities, because people will
 * quite often do relationships with the user, and we don't want them to get the authorities all
 * the time for nothing (for performance reasons). This is the #1 goal: we should not impact our users'
 * application because of this use-case.</li>
 * <li> Not having an outer join causes n+1 requests to the database. This is not a real issue as
 * we have by default a second-level cache. This means on the first HTTP call we do the n+1 requests,
 * but then all authorities come from the cache, so in fact it's much better than doing an outer join
 * (which will get lots of data from the database, for each HTTP call).</li>
 * <li> As this manages users, for security reasons, we'd rather have a DTO layer.</li>
 * </ul>
 * <p>
 * <p>Another option would be to have a specific JPA entity graph to handle this case.
 */
@RestController
@RequestMapping("/api")
@Slf4j
@AllArgsConstructor
public class UserResource {

    private static final String ENTITY_NAME = "userManagement";

    private final UserLoginService userLoginService;

    private final UserMailService userMailService;

    private final UserService userService;

    private final ProfileEventProducer profileEventProducer;

    private final UserQueryService userQueryService;


    /**
     * POST /users : Creates a new user.
     * Creates a new user if the login and email are not already used, and sends an mail with an
     * activation link. The user needs to be activated on creation.
     *
     * @param user the user to create
     * @return the ResponseEntity with status 201 (Created) and with body the new user, or with
     * status 400 (Bad Request) if the login or email is already in use
     * @throws URISyntaxException if the Location URI syntax is incorrect
     */
    @PostMapping("/users")
    @Timed
    @PreAuthorize("hasPermission({'user': #user}, 'USER.CREATE')")
    @PrivilegeDescription("Privilege to create a new user")
    public ResponseEntity createUser(@Valid @RequestBody UserDTO user) throws URISyntaxException {
        if (user.getId() != null) {
            return ResponseEntity.badRequest()
                .headers(HeaderUtil.createFailureAlert(ENTITY_NAME, "idexists", "A new user cannot already have an ID"))
                .body(null);
        }
        assertNotSuperAdmin(user);
        userLoginService.normalizeLogins(user.getLogins());
        userLoginService.verifyLoginsNotExist(user.getLogins());

        User newUser = userService.createUser(user);
        produceEvent(new UserDTO(newUser), Constants.CREATE_PROFILE_EVENT_TYPE);
        userMailService.sendMailOnCreateUser(newUser);
        return ResponseEntity.created(new URI("/api/users/" + newUser.getUserKey()))
            .headers(HeaderUtil.createAlert("userManagement.created", newUser.getUserKey()))
            .body(new UserDTO(newUser));
    }

    /**
     * PUT  /users : Updates an existing User.
     *
     * @param user the user to update
     * @return the ResponseEntity with status 200 (OK) and with body the updated user
     */
    @PutMapping("/users")
    @Timed
    @PreAuthorize("hasPermission({'userKey': #user.userKey, 'newUser': #user}, 'user', 'USER.UPDATE')")
    @PrivilegeDescription("Privilege to updates an existing user")
    public ResponseEntity<UserDTO> updateUser(@Valid @RequestBody UserDTO user) {
        assertNotSuperAdmin(user);
        Optional<UserDTO> updatedUser = userService.updateUser(user);
        updatedUser.ifPresent(this::produceUpdateEvent);
        return ResponseUtil.wrapOrNotFound(updatedUser,
                                           HeaderUtil.createAlert("userManagement.updated", user.getUserKey()));
    }

    /**
     * PUT  /users/{userKey}/block : Blocks user account
     *
     * @param userKey the user to block
     * @return the ResponseEntity with status 200 (OK) and with body the updated user
     */
    @PutMapping("/users/{userKey}/block")
    @Timed
    @PreAuthorize("hasPermission(null, 'USER.BLOCK')")
    @PrivilegeDescription("Privilege to blocks user")
    public ResponseEntity<UserDTO> blockUser(@NotEmpty @PathVariable String userKey) {
        Optional<UserDTO> updatedUser = userService.blockUserAccount(userKey);
        updatedUser.ifPresent(this::produceUpdateEvent);
        return ResponseUtil.wrapOrNotFound(updatedUser,
            HeaderUtil.createAlert("userManagement.blocked", userKey));
    }

    /**
     * PUT  /users/{userKey}/unblock : Unblock user account
     *
     * @param userKey the user to unblock
     * @return the ResponseEntity with status 200 (OK) and with body the updated user
     */
    @PutMapping("/users/{userKey}/activate")
    @Timed
    @PreAuthorize("hasPermission(null, 'USER.UNBLOCK')")
    @PrivilegeDescription("Privilege to unblocks user")
    public ResponseEntity<UserDTO> unblockUser(@NotEmpty @PathVariable String userKey) {
        Optional<UserDTO> updatedUser = userService.activateUserAccount(userKey);
        updatedUser.ifPresent(this::produceUpdateEvent);
        return ResponseUtil.wrapOrNotFound(updatedUser,
            HeaderUtil.createAlert("userManagement.unblocked", userKey));
    }

    /**
     * PUT  /users/role : change role key for user account
     *
     * @param user container with new role
     * @return the ResponseEntity with status 200 (OK) and with body the updated user
     */
    @PutMapping("/users/role")
    @Timed
    @PreAuthorize("hasPermission({'id': #user.userKey, 'newUser': #user}, 'user', 'USER.CHANGE.ROLE')")
    @PrivilegeDescription("Privilege to change roleKey for user")
    public ResponseEntity<UserDTO> changeRoleKey(@Valid @RequestBody UserDTO user) {
        Preconditions.checkArgument(isNotEmpty(user.getAuthorities()));
        assertNotSuperAdmin(user.getAuthorities());
        Optional<UserDTO> updatedUser = userService.changeUserRole(user);
        updatedUser.ifPresent(this::produceUpdateEvent);
        return ResponseUtil.wrapOrNotFound(updatedUser,
            HeaderUtil.createAlert("userManagement.role.changed", user.getUserKey()));
    }

    /**
     * PUT /user/logins : Updates an existing User logins.
     *
     * @param user the user to update
     * @return the ResponseEntity with status 200 (OK) and with body the updated user, or with
     * status 400 (Bad Request) if the login or email is already in use, or with status 500
     * (Internal Server Error) if the user couldn't be updated
     */
    @PutMapping("/users/logins")
    @Timed
    @PreAuthorize("hasPermission({'userKey': #user.userKey, 'newUser': #user}, 'user', 'USER.LOGIN.UPDATE')")
    @PrivilegeDescription("Privilege to updates an existing User logins")
    public ResponseEntity<UserDTO> updateUserLogins(@Valid @RequestBody UserDTO user) {
        userLoginService.normalizeLogins(user.getLogins());
        userLoginService.verifyLoginsNotExist(user.getLogins(), user.getId());

        Optional<UserDTO> updatedUser = userService.updateUserLogins(user.getUserKey(), user.getLogins());
        updatedUser.ifPresent(userDTO -> produceEvent(userDTO, Constants.UPDATE_PROFILE_EVENT_TYPE));
        return ResponseUtil.wrapOrNotFound(updatedUser,
                                           HeaderUtil.createAlert("userManagement.updated", user.getUserKey()));
    }

    /**
     * GET  /users : get all users.
     *
     * @param pageable the pagination information
     * @return the ResponseEntity with status 200 (OK) and with body all users
     */
    @GetMapping("/users")
    @Timed
    public ResponseEntity<List<UserDTO>> getAllUsers(
        @ApiParam Pageable pageable,
        @RequestParam(required = false) String roleKey) {
        final Page<UserDTO> page = userService.getAllManagedUsers(pageable, roleKey, null);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(page, "/api/users");
        return new ResponseEntity<>(page.getContent(), headers, HttpStatus.OK);
    }

    @GetMapping("/users/filter")
    @PostAuthorize("hasPermission({'returnObject': returnObject.body}, 'USER.GET_BY_FILTER.LIST')")
    @Timed
    public ResponseEntity<List<UserDTO>> getAllByStrictFilters(@ApiParam Pageable pageable, StrictUserFilterQuery strictUserFilterQuery){
        final Page<UserDTO> page = userQueryService.findAllUsersByStrictMatch(strictUserFilterQuery,pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(page, "/api/users/filter");
        return new ResponseEntity<>(page.getContent(), headers, HttpStatus.OK);
    }

    /**
     * Search user by occurrence a char sequence in firstname or lastname or login
     *
     * Doesn't support sort by logins.login
     *
     * @param pageable
     * @param query - search char sequence
     * @return
     */
    @GetMapping("/users/filter-soft")
    @PostAuthorize("hasPermission({'returnObject': returnObject.body}, 'USER.GET_BY_FILTER.LIST')")
    @Timed
    public ResponseEntity<List<UserDTO>> getAllBySoftFilters(@ApiParam Pageable pageable, SoftUserFilterQuery query) {
        Page<UserDTO> page = userQueryService.findAllUsersBySoftMatch(query, pageable);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(page, "/api/users/filter-soft");
        return new ResponseEntity<>(page.getContent(), headers, HttpStatus.OK);
    }

    /**
     * GET  /users/:userKey : get the "userKey" user.
     *
     * @param userKey the userKey of the user to find
     * @return the ResponseEntity with status 200 (OK) and with body the "userKey" user, or with status 404 (Not Found)
     */
    @GetMapping("/users/{userKey}")
    @Timed
    @PostAuthorize("hasPermission({'returnObject': returnObject.body}, 'USER.GET_LIST.ITEM')")
    @PrivilegeDescription("Privilege to get the user by userKey")
    public ResponseEntity<UserDTO> getUser(@PathVariable String userKey) {
        return ResponseUtil.wrapOrNotFound(
            userService.findOneWithLoginsByUserKey(userKey).map(UserDTO::new));
    }

    /**
     * GET  /users/:userKey/public : get public user info for "userKey"
     *
     * @param userKey the userKey of the user to find
     * @return the ResponseEntity with status 200 (OK) and with body the "userKey" user, or with status 404 (Not Found)
     */
    @GetMapping("/users/{userKey}/public")
    @Timed
    @PostAuthorize("hasPermission({'returnObject': returnObject.body}, 'USER.GET_LIST.ITEM.PUBLIC')")
    @PrivilegeDescription("Privilege to get public user info for userKey")
    public ResponseEntity<UserPublicDTO> getPublicUser(@PathVariable String userKey) {
        return ResponseUtil.wrapOrNotFound(
            userService.findOneWithLoginsByUserKey(userKey).map(UserPublicDTO::new));
    }

    /**
     * GET  /users/logins/:login : get the "login" user.
     *
     * @param login the login of the user to find
     * @return the ResponseEntity with status 200 (OK) and with body the "login" user, or with status 404 (Not Found)
     */
    @GetMapping("/users/logins")
    @Timed
    @PostAuthorize("hasPermission({'returnObject': returnObject.body}, 'USER.GET_USER_BY_LOGIN')")
    @PrivilegeDescription("Privilege to get all users by login")
    public ResponseEntity<UserDTO> getUserByLogin(@RequestParam String login) {
        return ResponseUtil.wrapOrNotFound(userService.findOneByLogin(login).map(UserDTO::new));
    }

    /**
     * GET  /users/logins-contains/:login : get the users.
     *
     * @param login part of the login of the users to find
     * @param pageable the pagination information
     * @return the ResponseEntity with status 200 (OK) and body with list of users, or the empty list
     */
    @GetMapping("/users/logins-contains")
    @Timed
    @PostAuthorize("hasPermission({'returnObject': returnObject.body}, 'USER.GET_USER_BY_LOGIN')")
    @PrivilegeDescription("Privilege to get all users by login")
    public ResponseEntity<List<UserDTO>> getAllUsersByLoginContains(@RequestParam String login, Pageable pageable) {
        Page<UserDTO> page = userService.findAllByLoginContains(login, pageable).map(UserDTO::new);
        HttpHeaders headers = PaginationUtil.generatePaginationHttpHeaders(page, "/api/users/logins-contains");
        return new ResponseEntity<>(page.getContent(), headers, HttpStatus.OK);
    }

    /**
     * DELETE /users/:userKey : delete the "userKey" User.
     *
     * @param userKey the user key of the user to delete
     * @return the ResponseEntity with status 200 (OK)
     */
    @DeleteMapping("/users/{userKey}")
    @Timed
    @PreAuthorize("hasPermission({'userKey':#userKey}, 'user', 'USER.DELETE')")
    @PrivilegeDescription("Privilege to delete the user by userKey")
    public ResponseEntity<Void> deleteUser(@PathVariable String userKey) {
        userService.deleteUser(userKey, userDto -> produceEvent(userDto, Constants.DELETE_PROFILE_EVENT_TYPE));
        return ResponseEntity.ok().headers(HeaderUtil.createAlert("userManagement.deleted", userKey)).build();
    }

    /**
     * POST /users/:userKey/tfa_enable : enable TFA for User with "userKey".
     *
     * @param userKey the user key to enable TFA
     * @param request TFE enable request
     * @return the ResponseEntity with status 200 (OK)
     */
    @PostMapping(path = "/users/{userKey}/tfa_enable")
    @Timed
    @PreAuthorize("hasPermission({'userKey':#userKey}, 'user', 'USER.TFA.ENABLE')")
    @PrivilegeDescription("Privilege to enable TFA for user with userKey")
    public ResponseEntity<Void> enableTwoFactorAuth(@PathVariable String userKey,
                                                    @Valid @NotNull @RequestBody TfaEnableRequest request) {
        userService.enableTwoFactorAuth(userKey, request.getOtpChannelSpec());
        return new ResponseEntity<>(HttpStatus.OK);
    }

    /**
     * POST /users/:userKey/tfa_disable : disable TFA for User with "userKey".
     *
     * @return the ResponseEntity with status 200 (OK)
     */
    @PostMapping(path = "/users/{userKey}/tfa_disable")
    @Timed
    @PreAuthorize("hasPermission({'userKey':#userKey}, 'user', 'USER.TFA.DISABLE')")
    @PrivilegeDescription("Privilege to disable TFA for user with userKey")
    public ResponseEntity<Void> disableTwoFactorAuth(@PathVariable String userKey) {
        userService.disableTwoFactorAuth(userKey);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    @GetMapping(path = "/users/{userKey}/tfa_available_otp_channel_specs")
    @Timed
    @PreAuthorize("hasPermission(null, 'USER.TFA.AVAILABLE_OTP_CHANNEL_SPECS')")
    @PrivilegeDescription("Privilege to get tfaOtpChannelSpecs for user with userKey")
    public ResponseEntity<Map<OtpChannelType, List<TfaOtpChannelSpec>>> getTfaAvailableOtpChannelSpecs(@PathVariable String userKey) {
        Map<OtpChannelType, List<TfaOtpChannelSpec>> channelSpecs = userService.getTfaAvailableOtpChannelSpecs(userKey);
        if (CollectionUtils.isEmpty(channelSpecs)) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }

        return ResponseEntity.ok().body(channelSpecs);
    }

    @PostMapping(path = "/users/accept-terms-of-conditions/{tocOneTimeToken}")
    @Timed
    public ResponseEntity<Void> acceptTermsOfConditions(@PathVariable String tocOneTimeToken) {
        userService.acceptTermsOfConditions(tocOneTimeToken);
        return new ResponseEntity<>(HttpStatus.OK);
    }

    private void produceEvent(UserDTO userDto, String eventType) {
        String content = profileEventProducer.createEventJson(userDto, eventType);
        profileEventProducer.send(content);
    }

    private void produceUpdateEvent(UserDTO userDTO) {
        produceEvent(userDTO, Constants.UPDATE_PROFILE_EVENT_TYPE);
    }
}
