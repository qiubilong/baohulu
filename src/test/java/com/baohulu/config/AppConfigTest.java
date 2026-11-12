package com.baohulu.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * AppConfig 单元测试：默认值与系统属性覆盖
 *
 * @author qiubilong
 * @date 2026-11-12
 */
class AppConfigTest {

    @AfterEach
    void cleanProperties() {
        System.clearProperty("baohulu.port");
        System.clearProperty("baohulu.buffer.capacity");
        System.clearProperty("baohulu.body.max.kb");
        System.clearProperty("baohulu.auto.proxy");
    }

    @Test
    void should_load_defaults_when_no_properties() {
        AppConfig config = AppConfig.loadFromSystemProperties();
        assertEquals(AppConfig.DEFAULT_PORT, config.port());
        assertEquals(AppConfig.DEFAULT_BUFFER_CAPACITY, config.bufferCapacity());
        assertEquals(AppConfig.DEFAULT_BODY_MAX_KB, config.bodyMaxKb());
        assertTrue(config.autoProxy());
    }

    @Test
    void should_override_from_system_properties() {
        System.setProperty("baohulu.port", "18888");
        System.setProperty("baohulu.buffer.capacity", "20");
        System.setProperty("baohulu.body.max.kb", "64");
        System.setProperty("baohulu.auto.proxy", "false");

        AppConfig config = AppConfig.loadFromSystemProperties();
        assertEquals(18888, config.port());
        assertEquals(20, config.bufferCapacity());
        assertEquals(64, config.bodyMaxKb());
        assertFalse(config.autoProxy());
    }
}
