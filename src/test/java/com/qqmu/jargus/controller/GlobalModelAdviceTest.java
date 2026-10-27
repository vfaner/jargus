package com.qqmu.jargus.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 页脚版本号展示：配置带 v 前缀时不能显示成 vv2.0.7。 */
class GlobalModelAdviceTest {

    private final GlobalModelAdvice advice = new GlobalModelAdvice();

    @Test
    void versionPrefixIsNotDoubled() {
        ReflectionTestUtils.setField(advice, "appVersion", "v2.0.7");
        ReflectionTestUtils.setField(advice, "assetVersion", "v2.0.12");

        assertEquals("v2.0.7", advice.appVersion());
        assertEquals("v2.0.12", advice.assetVersion());
    }

    @Test
    void blankAssetVersionFallsBackToAppVersion() {
        ReflectionTestUtils.setField(advice, "appVersion", "2.0.7");
        ReflectionTestUtils.setField(advice, "assetVersion", "");

        assertEquals("v2.0.7", advice.assetVersion());
    }

    @Test
    void blankVersionsRenderNothing() {
        ReflectionTestUtils.setField(advice, "appVersion", " ");
        ReflectionTestUtils.setField(advice, "assetVersion", "");

        assertNull(advice.appVersion());
        assertNull(advice.assetVersion());
    }
}
