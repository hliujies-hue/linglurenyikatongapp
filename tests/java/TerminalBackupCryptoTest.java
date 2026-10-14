package com.lingluren.yikatong.terminal;
import java.io.*;
import java.util.Arrays;
public class TerminalBackupCryptoTest {
 public static void main(String[] args)throws Exception {
  char[] password="test-record-password-123".toCharArray();byte[] plain="核验流水与回执".getBytes("UTF-8");ByteArrayOutputStream out=new ByteArrayOutputStream();TerminalBackupCrypto.encrypt(plain,password,out);byte[] encoded=out.toByteArray();
  if(!Arrays.equals(plain,TerminalBackupCrypto.decrypt(new ByteArrayInputStream(encoded),password)))throw new AssertionError("round trip");
  reject(encoded,"incorrect-password-123".toCharArray());byte[] damaged=encoded.clone();damaged[damaged.length-1]^=1;reject(damaged,password);
  reject(Arrays.copyOf(encoded,encoded.length-5),password);damaged=encoded.clone();damaged[0]^=1;reject(damaged,password);
  if(!TerminalMessages.friendly("Unable to resolve host x: No address associated with hostname").contains("域名无法解析"))throw new AssertionError("DNS");
  if(!"年票未生效".equals(TerminalMessages.friendly("年票未生效")))throw new AssertionError("business text changed");
  System.out.println("PASS: backup encryption, wrong password, tampering, truncation, format and network feedback");
 }
 static void reject(byte[] value,char[] password)throws Exception {try{TerminalBackupCrypto.decrypt(new ByteArrayInputStream(value),password);throw new AssertionError("accepted invalid archive");}catch(IOException expected){}}
}
