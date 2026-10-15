package com.lingluren.yikatong.terminal;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.util.Arrays;
public final class TerminalStorageEnvelopeTest {
 public static void main(String[] args)throws Exception{
  KeyGenerator g=KeyGenerator.getInstance("AES");g.init(256);SecretKey key=g.generateKey();byte[] plain=new byte[512];Arrays.fill(plain,(byte)42);
  byte[] a=TerminalStorageEnvelope.encrypt(key,plain,"feature:1:hash"),b=TerminalStorageEnvelope.encrypt(key,plain,"feature:1:hash");
  if(Arrays.equals(a,b)||!Arrays.equals(plain,TerminalStorageEnvelope.decrypt(key,a,"feature:1:hash")))throw new AssertionError("nonce or roundtrip");
  reject(key,a,"feature:2:hash");reject(g.generateKey(),a,"feature:1:hash");
  for(int i:new int[]{0,1,13,a.length-1}){byte[] bad=a.clone();bad[i]^=1;reject(key,bad,"feature:1:hash");}
  reject(key,Arrays.copyOf(a,20),"feature:1:hash");System.out.println("PASS: encryption roundtrip, random IV, wrong owner/key, tampering and truncation");
 }
 static void reject(SecretKey key,byte[] data,String aad)throws Exception{try{TerminalStorageEnvelope.decrypt(key,data,aad);}catch(Exception expected){return;}throw new AssertionError("accepted corrupted ciphertext");}
}
