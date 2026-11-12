package com.baohulu.proxy;

import java.util.Map;

/**
 * 域名级 Header 改写拦截点（M2 预留）
 * 上游转发前按域名调用实现类对请求头做增删改；
 * M1 使用 {@link #NOOP} 空实现，M2 接入规则引擎
 *
 * @author qiubilong
 * @date 2026-11-12
 */
@FunctionalInterface
public interface HeaderModifier {

    /** 空实现：不做任何改写 */
    HeaderModifier NOOP = (host, headers) -> { };

    /**
     * 转发前改写上游请求头
     *
     * @param host 目标域名（不含端口）
     * @param headers 待改写的上游请求头集合（可变，直接增删改）
     */
    void modifyHeaders(String host, Map<String, String> headers);
}
