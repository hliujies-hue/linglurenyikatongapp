package com.lingluren.yikatong.terminal;

import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;

/** HTTPS only. Credentials never follow redirects; signed photos receive no bearer token. */
public final class PlatformClient {
    private final String origin;
    private String token="";
    private volatile long retryUntil;
    public long retryDelayMillis(){return Math.max(0,(retryUntil-System.nanoTime())/1000000L);}
    public static final class ApiException extends TerminalDrainCoordinator.ResponseException {
        ApiException(int status,String message){super(status,message);}
    }
    public static final class PhotoException extends IOException {
        public final boolean rejected;
        PhotoException(String message,boolean rejected){super(message);this.rejected=rejected;}
    }
    private static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(){super("平台响应过大");}
    }
    public PlatformClient(String origin){this.origin=TerminalEndpointPolicy.normalizeOrigin(origin);}
    public String origin(){return origin;}
    public void token(String token){this.token=token;}
    public JSONObject request(String path,JSONObject body) throws Exception {
        TerminalEndpointPolicy.requirePath(path);return json(path,body);
    }
    public JSONObject statistics(String from,String to) throws Exception {
        if(!from.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!to.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("请选择有效日期");
        return json("/api/terminal/statistics?startDate="+from+"&endDate="+to,null);
    }
    private JSONObject json(String path,JSONObject body) throws Exception {
        long wait=retryDelayMillis();if(wait>0)throw new ApiException(429,"平台限流保护中，请等待"+((wait+999)/1000)+"秒后重试；本机记录保留");
        HttpsURLConnection connection=(HttpsURLConnection)new URL(origin+path).openConnection();configure(connection);
        if(!token.isEmpty()&&!path.startsWith("/api/terminal/recovery/"))connection.setRequestProperty("Authorization","Bearer "+token);
        try {
            if(body!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
                byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);
                try(OutputStream out=connection.getOutputStream()){out.write(bytes);}
            }
            int http=connection.getResponseCode();
            if(http==429){long seconds=60;try{seconds=Long.parseLong(connection.getHeaderField("Retry-After"));}catch(Exception ignored){}seconds=Math.max(1,Math.min(3600,seconds));retryUntil=System.nanoTime()+seconds*1000000000L;throw new ApiException(429,"平台请求达到限流额度，等待"+seconds+"秒后自动重试；本机记录保留");}
            InputStream stream=http>=400?connection.getErrorStream():connection.getInputStream();
            if(stream==null)throw new ApiException(http,"平台连接异常（"+http+"）");
            JSONObject reply;
            try { reply=new JSONObject(new String(read(stream,1024*1024),StandardCharsets.UTF_8)); }
            catch(Exception bad){throw new ApiException(http,"平台返回格式异常（"+http+"）");}
            if(http!=200||reply.optInt("code",-1)!=0){String message=reply.optString("message","平台请求失败");throw new ApiException(http,message.length()>180?"平台请求失败（"+http+"）":message);}
            Object data=reply.get("data");return data instanceof JSONObject?(JSONObject)data:new JSONObject().put("value",data);
        }finally{connection.disconnect();}
    }
    public byte[] photo(String url) throws Exception {
        URI uri,base=new URI(origin);
        try{uri=new URI(url);}catch(java.net.URISyntaxException e){throw new PhotoException("人员照片地址格式无效，请管理员检查照片记录",true);}
        if(!"https".equals(uri.getScheme())||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null||!base.getHost().equalsIgnoreCase(uri.getHost())||port(base)!=port(uri)||uri.getRawPath()==null||!uri.getRawPath().matches("/api/device/photos/[A-Za-z0-9._%-]+"))throw new PhotoException("人员照片地址不属于当前平台，请管理员检查照片记录",true);
        HttpsURLConnection connection=(HttpsURLConnection)uri.toURL().openConnection();configure(connection);
        try {
            int status=connection.getResponseCode();
            if(status==404||status==410)throw new PhotoException("平台人员照片文件不存在（HTTP "+status+"），请重新上传该人员照片",true);
            if(status!=200)throw new PhotoException("人员照片下载失败（HTTP "+status+"），保留失败任务后重试",false);
            try{return read(connection.getInputStream(),2*1024*1024);}
            catch(ResponseTooLargeException e){throw new PhotoException("人员照片超过终端2MiB下载上限，请后台压缩或重新上传",true);}
        }
        finally{connection.disconnect();}
    }
    public static byte[] brandingImage(String value) throws Exception {
        URI uri=new URI(value);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null)throw new IOException("品牌图片必须使用HTTPS地址");
        HttpsURLConnection c=(HttpsURLConnection)uri.toURL().openConnection();configure(c);
        try{if(c.getResponseCode()!=200)throw new IOException("品牌图片HTTP状态："+c.getResponseCode());return read(c.getInputStream(),8*1024*1024);}finally{c.disconnect();}
    }
    private static int port(URI u){return u.getPort()==-1?443:u.getPort();}
    private static void configure(HttpsURLConnection c){c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(10000);c.setRequestProperty("User-Agent","YikatongTerminal/"+TerminalProtocol.VERSION);c.setRequestProperty("Accept","application/json");c.setUseCaches(false);}
    private static byte[] read(InputStream stream,int limit) throws IOException {try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
        byte[] buffer=new byte[8192];int n,total=0;while((n=in.read(buffer))!=-1){total+=n;if(total>limit)throw new ResponseTooLargeException();out.write(buffer,0,n);}return out.toByteArray();}}
    public void logout(){try{request("/api/auth/logout",new JSONObject());}catch(Exception ignored){}finally{token="";}}
}
