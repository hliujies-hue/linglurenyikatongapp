package com.lingluren.yikatong.terminal;

import android.content.Context;
import android.os.Build;
import android.security.KeyPairGeneratorSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import javax.security.auth.x500.X500Principal;

/** Private installation key. Missing keys never trigger replacement of encrypted data. */
final class TerminalStorageCrypto {
    private final Context context;
    private final String alias;
    private SecretKey key;
    TerminalStorageCrypto(Context context,String database){this.context=context;alias="ykt.storage."+database;}
    synchronized void initialize(boolean existing){
        if(key!=null)return;
        try{
            KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
            // Preserve the wrapped-key path when a pre-23 phone later upgrades Android.
            android.content.SharedPreferences prefs=context.getSharedPreferences("terminal-key-wrap",Context.MODE_PRIVATE);
            String wrapped=prefs.getString(alias,"");
            if(!wrapped.isEmpty()||Build.VERSION.SDK_INT<23){
                if(!ks.containsAlias(alias)){
                    if(existing||!wrapped.isEmpty())throw new IllegalStateException("本机加密密钥已丢失");
                    Calendar until=Calendar.getInstance();until.add(Calendar.YEAR,50);
                    KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA","AndroidKeyStore");
                    generator.initialize(new KeyPairGeneratorSpec.Builder(context).setAlias(alias).setSubject(new X500Principal("CN=Terminal Storage"))
                        .setSerialNumber(BigInteger.ONE).setStartDate(new Date(0)).setEndDate(until.getTime()).setKeySize(2048).build());generator.generateKeyPair();
                }
                Cipher rsa=Cipher.getInstance("RSA/ECB/PKCS1Padding");
                if(wrapped.isEmpty()){
                    if(existing)throw new IllegalStateException("本机加密密钥封装文件丢失");
                    byte[] raw=new byte[32];new SecureRandom().nextBytes(raw);
                    try{rsa.init(Cipher.ENCRYPT_MODE,ks.getCertificate(alias).getPublicKey());
                        if(!prefs.edit().putString(alias,Base64.encodeToString(rsa.doFinal(raw),Base64.NO_WRAP)).commit())throw new IllegalStateException("无法保存本机加密密钥");
                        key=new SecretKeySpec(raw,"AES");
                    }finally{Arrays.fill(raw,(byte)0);}
                }else{
                    rsa.init(Cipher.DECRYPT_MODE,ks.getKey(alias,null));byte[] raw=rsa.doFinal(Base64.decode(wrapped,Base64.NO_WRAP));
                    try{key=new SecretKeySpec(raw,"AES");}finally{Arrays.fill(raw,(byte)0);}
                }
            }else{
                if(!ks.containsAlias(alias)){
                    if(existing)throw new IllegalStateException("本机加密密钥已丢失");
                    KeyGenerator gen=KeyGenerator.getInstance("AES","AndroidKeyStore");
                    gen.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());gen.generateKey();
                }
                key=(SecretKey)ks.getKey(alias,null);
            }
        }catch(Exception e){throw problem(e instanceof IllegalStateException?e.getMessage():"系统密钥库读取失败（"+e.getClass().getSimpleName()+"）",e);}
    }
    byte[] encrypt(byte[] plain,String field){
        try{return TerminalStorageEnvelope.encrypt(key,plain,field);}catch(Exception e){throw problem("本机数据加密失败",e);}
    }
    byte[] decrypt(byte[] value,String field){
        try{return TerminalStorageEnvelope.decrypt(key,value,field);}catch(Exception e){throw problem("本机数据解密校验失败（"+field+"）",e);}
    }
    String seal(String plain,String field){return "enc1:"+Base64.encodeToString(encrypt(plain.getBytes(StandardCharsets.UTF_8),field),Base64.NO_WRAP);}
    String open(String value,String field){
        if(value==null)return null;
        if(!value.startsWith("enc1:"))throw problem("本机数据格式不是已迁移的加密格式（"+field+"）",null);
        try{return new String(decrypt(Base64.decode(value.substring(5),Base64.NO_WRAP),field),StandardCharsets.UTF_8);}
        catch(IllegalArgumentException e){throw problem("本机加密数据格式损坏",e);}
    }
    private static IllegalStateException problem(String stage,Throwable cause){return new IllegalStateException(stage+"；已停止读取和写入，请保留应用及数据并联系技术支持，禁止卸载或清除数据",cause);}
}
