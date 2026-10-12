package com.lingluren.yikatong.terminal;
import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
/** Secrets and face templates are encrypted at rest; never log plaintext. */
public final class Vault {
 private final Context context;
 public Vault(Context c) { context = c.getApplicationContext(); }
 private synchronized SecretKey key() throws Exception {
  KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
  if (!store.containsAlias("ykt-terminal-v1")) {
   KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
   g.init(new KeyGenParameterSpec.Builder("ykt-terminal-v1",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
     .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
   g.generateKey();
  }
  return (SecretKey) store.getKey("ykt-terminal-v1",null);
 }
 public byte[] seal(byte[] data) throws Exception {
  Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,key());
  byte[] encrypted=c.doFinal(data), iv=c.getIV(), out=new byte[1+iv.length+encrypted.length];
  out[0]=(byte)iv.length; System.arraycopy(iv,0,out,1,iv.length); System.arraycopy(encrypted,0,out,1+iv.length,encrypted.length); return out;
 }
 public byte[] open(byte[] data) throws Exception {
  if(data.length<30 || data[0]!=12) throw new IllegalArgumentException("Invalid encrypted record");
  Cipher c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,data,1,12));
  return c.doFinal(data,13,data.length-13);
 }
 public void save(String name,String value) throws Exception {
  String encoded=Base64.encodeToString(seal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
  if(!context.getSharedPreferences("vault",0).edit().putString(name,encoded).commit()) throw new IllegalStateException("Secret storage failed");
 }
 public String get(String name) throws Exception {
  String value=context.getSharedPreferences("vault",0).getString(name,null);
  return value==null?"":new String(open(Base64.decode(value,Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
 }
 public void remove(String name) { context.getSharedPreferences("vault",0).edit().remove(name).commit(); }
}
