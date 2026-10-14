package com.lingluren.yikatong.terminal;

import java.io.*;
import java.security.SecureRandom;
import javax.crypto.*;
import javax.crypto.spec.*;

/** Authenticated, password-protected record archive. No plaintext is written to external storage. */
final class TerminalBackupCrypto {
    private static final int LIMIT=32*1024*1024;
    static void encrypt(byte[] plain,char[] password,OutputStream target) throws Exception {
        if(plain.length>LIMIT)throw new IOException("记录备份超过32MB，请联系管理员归档");
        byte[] salt=new byte[16],iv=new byte[12];SecureRandom random=new SecureRandom();random.nextBytes(salt);random.nextBytes(iv);
        DataOutputStream out=new DataOutputStream(target);out.writeInt(0x594b5431);out.write(salt);out.write(iv);
        out.write(cipher(Cipher.ENCRYPT_MODE,password,salt,iv).doFinal(plain));out.flush();
    }
    static byte[] decrypt(InputStream source,char[] password) throws Exception {
        DataInputStream in=new DataInputStream(source);if(in.readInt()!=0x594b5431)throw new IOException("不是受支持的核验记录备份");
        byte[] salt=new byte[16],iv=new byte[12];in.readFully(salt);in.readFully(iv);
        ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))!=-1){if(data.size()+n>LIMIT+16)throw new IOException("备份文件过大");data.write(buffer,0,n);}
        try{return cipher(Cipher.DECRYPT_MODE,password,salt,iv).doFinal(data.toByteArray());}
        catch(javax.crypto.BadPaddingException e){throw new IOException("备份密码错误或文件已损坏");}
    }
    private static Cipher cipher(int mode,char[] password,byte[] salt,byte[] iv)throws Exception {
        if(password.length<12)throw new IllegalArgumentException("备份密码至少12位");
        PBEKeySpec spec=new PBEKeySpec(password,salt,150000,256);byte[] key=null;
        try{key=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));c.updateAAD(new byte[]{89,75,84,49});return c;}
        finally{spec.clearPassword();if(key!=null)java.util.Arrays.fill(key,(byte)0);}
    }
}
