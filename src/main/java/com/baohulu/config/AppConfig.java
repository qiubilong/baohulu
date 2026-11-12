package com.baohulu.config;

/**
 * 应用配置
 * 从系统属性读取运行参数，均提供默认值兜底
 *
 * @author qiubilong
 * @date 2026-11-12
 */
public record AppConfig(int port, int bufferCapacity, int bodyMaxKb, boolean autoProxy) {

    /** 默认监听端口 */
    public static final int DEFAULT_PORT = 8899;

    /** 默认流量环形缓冲容量（条） */
    public static final int DEFAULT_BUFFER_CAPACITY = 1000;

    /** 默认请求/响应体最大保留长度（KB），超出截断 */
    public static final int DEFAULT_BODY_MAX_KB = 1024;

    /** 默认是否自动接管系统代理 */
    public static final boolean DEFAULT_AUTO_PROXY = true;

    /**
     * 从系统属性构建配置
     * 属性键：baohulu.port / baohulu.buffer.capacity / baohulu.body.max.kb / baohulu.auto.proxy
     *
     * @return 应用配置
     */
    public static AppConfig loadFromSystemProperties() {
        int port = Integer.getInteger("baohulu.port", DEFAULT_PORT);
        int capacity = Integer.getInteger("baohulu.buffer.capacity", DEFAULT_BUFFER_CAPACITY);
        int bodyMaxKb = Integer.getInteger("baohulu.body.max.kb", DEFAULT_BODY_MAX_KB);
        boolean autoProxy = Boolean.parseBoolean(
                System.getProperty("baohulu.auto.proxy", String.valueOf(DEFAULT_AUTO_PROXY)));
        return new AppConfig(port, capacity, bodyMaxKb, autoProxy);
    }
}
