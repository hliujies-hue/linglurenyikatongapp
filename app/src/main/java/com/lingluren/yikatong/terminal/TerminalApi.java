package com.lingluren.yikatong.terminal;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.HttpsURLConnection;
/** Proposed versioned terminal API. Never fall back to legacy unauthenticated admission. */
public final class TerminalApi {
 private final String base;
 private volatile String token="";
 public TerminalApi(String base)throws Exception{
  URI uri=new URI(base);
  if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)
   throw new IllegalArgumentException("平台地址必须为 HTTPS 地址");
  this.base=base.replaceAll("/+$","");
 }
 public void token(String value){token=value;}
 public JSONObject post(String path,JSONObject body)throws Exception{
  URL url=new URL(base+"/api/terminal/v1/"+path);
  HttpsURLConnection c=(HttpsURLConnection)url.openConnection();
  c.setInstanceFollowRedirects(false);c.setConnectTimeout(4000);c.setReadTimeout(7000);
  c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json; charset=utf-8");
  if(!token.isEmpty())c.setRequestProperty("Authorization","Bearer "+token);
  try{
   byte[] payload=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(payload.length);
   try(OutputStream out=c.getOutputStream()){out.write(payload);}
   int status=c.getResponseCode();
   if(status==404)throw new IOException("平台尚未部署核验端接口");
   if(status==401||status==403)throw new IOException("账号或设备无权限，请重新登录或联系平台");
   if(status!=200)throw new IOException("平台服务异常（HTTP "+status+"）");
   JSONObject reply=new JSONObject(new String(readBounded(c.getInputStream(),2*1024*1024),StandardCharsets.UTF_8));
   if(!reply.optBoolean("ok",false))throw new IOException("平台拒绝请求，错误码："+reply.optString("code","UNKNOWN"));
   return reply.getJSONObject("data");
  }finally{c.disconnect();}
 }
 public static byte[] readBounded(InputStream in,int limit)throws IOException{
  try(InputStream source=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
   byte[] buffer=new byte[8192];int n,total=0;
   while((n=source.read(buffer))!=-1){total+=n;if(total>limit)throw new IOException("响应超过大小限制");out.write(buffer,0,n);}return out.toByteArray();
  }
 }
 public byte[] photo(String path)throws Exception{
  // Only same-origin paths: no arbitrary signed hosts, no auth headers sent to third parties.
  if(!path.startsWith("/api/terminal/v1/photos/")||path.contains("..")||path.contains("\\")||path.contains("#"))throw new IOException("照片地址无效");
  HttpsURLConnection c=(HttpsURLConnection)new URL(base+path).openConnection();
  c.setInstanceFollowRedirects(false);c.setConnectTimeout(5000);c.setReadTimeout(15000);c.setRequestProperty("Authorization","Bearer "+token);
  try{if(c.getResponseCode()!=200)throw new IOException("照片下载失败");return readBounded(c.getInputStream(),10*1024*1024);}finally{c.disconnect();}
 }
}
