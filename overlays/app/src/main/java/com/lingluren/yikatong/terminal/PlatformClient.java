package com.lingluren.yikatong.terminal;

import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** Uses the platform's existing login endpoints and durable admin-session authorization. */
public final class PlatformClient {
    private final String origin;
    private String token = "";
    public PlatformClient(String origin) {
        if (!"https://yktapi.qiliai.com".equals(origin)) throw new IllegalArgumentException("平台地址未配置");
        this.origin = origin;
    }
    public void token(String token) { this.token = token; }
    public JSONObject request(String path, JSONObject body) throws Exception {
        if (!path.startsWith("/api/")) throw new IllegalArgumentException("接口地址无效");
        HttpsURLConnection connection = (HttpsURLConnection) new URL(origin+path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000); connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent","YikatongTerminal/0.1-demo");
        connection.setRequestProperty("Accept","application/json");
        if(!token.isEmpty()) connection.setRequestProperty("Authorization","Bearer "+token);
        try {
            if(body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream stream=connection.getOutputStream()) { stream.write(bytes); }
            }
            int http=connection.getResponseCode();
            InputStream stream=http>=400 ? connection.getErrorStream() : connection.getInputStream();
            if(stream==null) throw new IOException("平台连接异常（"+http+"）");
            String text;
            try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int size,total=0;
                while((size=in.read(buffer))!=-1) {
                    total+=size;if(total>1024*1024)throw new IOException("平台响应过大");
                    out.write(buffer,0,size);
                }
                text=new String(out.toByteArray(),StandardCharsets.UTF_8);
            }
            JSONObject response;
            try { response=new JSONObject(text); }
            catch(Exception invalid) { throw new IOException("平台返回异常（"+http+"），请联系工作人员"); }
            if(http!=200 || response.optInt("code",-1)!=0) {
                String message=response.optString("message","平台请求失败");
                throw new IOException(message.length()>180?"平台请求失败（"+http+"）":message);
            }
            return response.getJSONObject("data");
        } finally { connection.disconnect(); }
    }
    public void logout() { try { request("/api/auth/logout",new JSONObject()); } catch(Exception ignored) {} finally { token=""; } }
}
