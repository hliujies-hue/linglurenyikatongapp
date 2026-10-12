package com.lingluren.yikatong.terminal;

/** Restrict credential-bearing requests to the deployed API origin and API paths. */
public final class TerminalEndpointPolicy {
    private TerminalEndpointPolicy() {}
    public static void requireOrigin(String origin) {
        if (!"https://yktapi.qiliai.com".equals(origin)) throw new IllegalArgumentException("平台地址未配置");
    }
    public static void requirePath(String path) {
        if (path==null || !path.matches("/api/[A-Za-z0-9/_-]+")) throw new IllegalArgumentException("接口地址无效");
    }
}
