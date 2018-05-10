package com.icthh.xm.uaa.service;

import static com.icthh.xm.uaa.config.Constants.SUPER_TENANT;
import static org.springframework.boot.actuate.security.AuthenticationAuditListener.AUTHENTICATION_SUCCESS;

import com.icthh.xm.commons.tenant.TenantContextHolder;
import com.icthh.xm.commons.tenant.TenantContextUtils;
import com.icthh.xm.uaa.repository.CustomAuditEventRepository;
import com.icthh.xm.uaa.repository.projection.PrincipalProjection;
import com.icthh.xm.uaa.security.TokenConstraintsService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Service Implementation for managing online users.
 */
@Slf4j
@AllArgsConstructor
@Service
public class OnlineUsersService {

    private final TenantContextHolder tenantContextHolder;
    private final CustomAuditEventRepository auditEventRepository;
    private final TokenConstraintsService tokenConstraints;

    /**
     * Find online users for current tenant. But for super tenant it finds online users from all tenants.
     *
     * @return collection of online users
     */
    public Collection<String> find() {
        return auditEventRepository
            .findAfter(Instant.now().minus(tokenConstraints.getDefaultAccessTokenValiditySeconds(),
                ChronoUnit.SECONDS), AUTHENTICATION_SUCCESS)
            .stream()
            .map(PrincipalProjection::getPrincipal)
            .collect(Collectors.toList());
    }

    /**
     * Delete online user from repository by key.
     *
     * @param key key for search
     */
    public void delete(String key) {
        auditEventRepository.delete(key);
    }
}
