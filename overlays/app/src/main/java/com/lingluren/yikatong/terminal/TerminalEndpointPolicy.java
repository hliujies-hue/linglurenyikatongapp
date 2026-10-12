package com.lingluren.yikatong.terminal;

import java.net.URI;

/** User-configured HTTPS origin; credentials never follow redirects to another host. */
public final class TerminalEndpointPolicy {
    private TerminalEndpointPolicy() {}
    public static String normalizeOrigin(String origin) {
        if(origin==null) throw new IllegalArgumentException("请输入平台 HTTPS 地址");
        try {
            URI uri=new URI(origin.trim());
            String path=uri.getRawPath();
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null ||
                uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null ||
                (path!=null && !path.isEmpty() && !"/".equals(path)) || uri.getPort()==0 || uri.getPort()>65535)
                throw new IllegalArgumentException("请填写 HTTPS 域名及可选端口，不要填写账号、路径或查询参数");
            return new URI("https",null,uri.getHost().toLowerCase(java.util.Locale.ROOT),uri.getPort(),null,null,null).toASCIIString();
        } catch(java.net.URISyntaxException error) {
            throw new IllegalArgumentException("平台地址格式不正确");
        }
    }
    public static void requireOrigin(String origin) { normalizeOrigin(origin); }
    public static void requirePath(String path) {
        if (path==null || !path.matches("/api/[A-Za-z0-9/_-]+")) throw new IllegalArgumentException("接口地址无效");
    }
}
