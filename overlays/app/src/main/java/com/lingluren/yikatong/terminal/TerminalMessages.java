package com.lingluren.yikatong.terminal;
final class TerminalMessages {
    static String friendly(String value){
        if(value==null||value.isEmpty())return "操作未完成，请重试";
        String lower=value.toLowerCase(java.util.Locale.ROOT);
        if(lower.contains("unable to resolve host")||lower.contains("unknownhost")||lower.contains("no address associated"))return "平台域名无法解析，请检查网络或平台地址";
        if(lower.contains("timeout")||lower.contains("timed out"))return "平台响应超时，请稍后重试";
        if(lower.contains("failed to connect")||lower.contains("connection refused")||lower.contains("network is unreachable"))return "暂时无法连接平台，请检查网络";
        if(lower.contains("ssl")||lower.contains("certificate"))return "安全连接验证失败，请检查设备时间或联系管理员";
        return value;
    }
}
