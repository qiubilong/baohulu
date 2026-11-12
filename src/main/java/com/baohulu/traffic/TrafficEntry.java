package com.baohulu.traffic;

import java.util.Map;

import lombok.Data;

/**
 * 单条流量记录
 * 承载一次请求/响应的完整明文数据（MITM 解密后）
 *
 * @author qiubilong
 * @date 2026-11-12
 */
@Data
public class TrafficEntry {

    /** 自增 id */
    private long id;

    /** 请求开始时间（yyyy-MM-dd HH:mm:ss.SSS） */
    private String startTime;

    /** 请求方法：GET/POST/... */
    private String method;

    /** 完整请求 URL */
    private String url;

    /** 目标主机（域名或 IP） */
    private String host;

    /** 请求路径 */
    private String path;

    /** 查询串（不含 ?） */
    private String query;

    /** 请求头 */
    private Map<String, String> requestHeaders;

    /** 请求体文本（超出上限截断） */
    private String requestBody;

    /** 请求体是否被截断 */
    private boolean requestTruncated;

    /** 请求体 Content-Type */
    private String requestContentType;

    /** 响应状态码，0 表示未收到响应（转发失败等） */
    private int status;

    /** 响应头 */
    private Map<String, String> responseHeaders;

    /** 响应体文本（二进制内容为 null） */
    private String responseBody;

    /** 响应体是否被截断 */
    private boolean responseTruncated;

    /** 响应体 Content-Type */
    private String responseContentType;

    /** 响应体是否为二进制（为 true 时 responseBody 为空，仅看类型与大小） */
    private boolean responseBinary;

    /** 响应体原始大小（字节） */
    private long responseSize;

    /** 总耗时（毫秒） */
    private long durationMs;

    /** 错误信息（转发异常等），无错误为 null */
    private String error;
}
