package com.qqmu.jargus.controller;

import com.qqmu.jargus.dto.Result;
import com.qqmu.jargus.security.RequiresRole;
import com.qqmu.jargus.service.version.VersionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** 检查更新接口：Result 信封与角色注解（类级 VIEWER / 忽略 ADMIN）。 */
@ExtendWith(MockitoExtension.class)
class VersionApiControllerTest {

    @Mock
    VersionService versionService;

    @InjectMocks
    VersionApiController controller;

    @Test
    void checkReturnsUpdateCheckInResultEnvelope() {
        VersionService.UpdateCheck check = new VersionService.UpdateCheck(
                true, true, false, "2.0.7", "2.0.8", "v2.0.8",
                "https://github.com/vfaner/jargus/releases/tag/v2.0.8");
        when(versionService.checkNow()).thenReturn(check);

        Result<VersionService.UpdateCheck> result = controller.check();

        assertEquals(200, result.getCode());
        assertEquals(check, result.getData());
    }

    @Test
    void dismissReturnsDismissedFlag() {
        when(versionService.dismissArtifact()).thenReturn(true);

        Result<Map<String, Object>> result = controller.dismissArtifact();

        assertEquals(200, result.getCode());
        assertEquals(Boolean.TRUE, result.getData().get("dismissed"));
    }

    @Test
    void classLevelViewerAndMethodLevelAdmin() throws Exception {
        RequiresRole classLevel = VersionApiController.class.getAnnotation(RequiresRole.class);
        assertNotNull(classLevel);
        assertEquals("VIEWER", classLevel.value());

        Method dismiss = VersionApiController.class.getMethod("dismissArtifact");
        RequiresRole methodLevel = dismiss.getAnnotation(RequiresRole.class);
        assertNotNull(methodLevel);
        assertEquals("ADMIN", methodLevel.value());

        Method check = VersionApiController.class.getMethod("check");
        assertTrue(check.getAnnotation(RequiresRole.class) == null,
                "check 继承类级 VIEWER 即可");
    }
}
