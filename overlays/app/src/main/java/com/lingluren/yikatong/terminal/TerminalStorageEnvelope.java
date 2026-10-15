package com.lingluren.yikatong.terminal;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
/** Versioned AEAD envelope shared by the Android store and host corruption tests. */
final class TerminalStorageEnvelope {
    static byte[] encrypt(SecretKey key,byte[] plain,String field)throws Exception{
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key);
        c.updateAAD(field.getBytes(StandardCharsets.UTF_8));byte[] body=c.doFinal(plain),iv=c.getIV();
        if(iv.length!=12)throw new IllegalStateException("加密随机数长度不正确");
        byte[] out=new byte[13+body.length];out[0]=1;System.arraycopy(iv,0,out,1,12);System.arraycopy(body,0,out,13,body.length);return out;
    }
    static byte[] decrypt(SecretKey key,byte[] value,String field)throws Exception{
        if(value==null||value.length<29||value[0]!=1)throw new IllegalStateException("加密数据版本或长度不正确");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(value,1,13)));
        c.updateAAD(field.getBytes(StandardCharsets.UTF_8));return c.doFinal(value,13,value.length-13);
    }
}
