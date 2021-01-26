package com.icthh.xm.uaa.domain.converter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.icthh.xm.uaa.domain.properties.TenantProperties;
import com.icthh.xm.uaa.service.TenantPropertiesService;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class RoleKeyConverterTest {


    @Mock
    TenantPropertiesService tenantPropertiesService;

    @InjectMocks
    RoleKeyConverter roleKeyConverter;

    @Test
    public void testOnlyOneRoleReturnsIfFeatureDisabled() {
        when(tenantPropertiesService.getTenantProps()).thenReturn(new TenantProperties(){{
            setMultiRoleEnabled(false);
        }});
        List<String> roles = roleKeyConverter.convertToEntityAttribute("[\"ROLE_ADMIN\",\"ROLE_SUPER_ADMIN\"]");
        assertEquals(roles.get(0), "ROLE_SUPER_ADMIN");
        assertEquals(roles.size(), 1);

    }
}
