package com.lingluren.yikatong.terminal;
import java.util.*;
public final class TerminalIndexLedgerTest {
 static final class Index implements TerminalIndexLedger.NativeIndex {
  Map<Integer,byte[]> rows=new HashMap<>();int pushes,deletes;boolean failPush,failDelete;
  public int push(int id,byte[] f){pushes++;if(failPush)return -9;if(rows.containsKey(id))return -1003;rows.put(id,f);return 0;}
  public int delete(int id){deletes++;if(failDelete)return -8;rows.remove(id);return 0;}
 }
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  TerminalIndexLedger ledger=new TerminalIndexLedger();Index sdk=new Index();byte[] first={1},second={2};
  check(ledger.restore(sdk,1,first)==0);check(ledger.restore(sdk,1,first)==0);check(sdk.pushes==1);
  check(ledger.replace(sdk,1,second)==0);check(sdk.deletes==1);check(sdk.rows.get(1)==second);
  sdk.failDelete=true;check(ledger.replace(sdk,1,first)==-8);check(sdk.pushes==2);sdk.failDelete=false;
  sdk.failPush=true;check(ledger.replace(sdk,1,first)==-9);check(!sdk.rows.containsKey(1));sdk.failPush=false;
  check(ledger.restore(sdk,1,first)==0);check(sdk.rows.get(1)==first);
  check(ledger.remove(sdk,1)==0);int n=sdk.deletes;check(ledger.remove(sdk,1)==0);check(sdk.deletes==n);
  ledger.clear();sdk.rows.clear();check(ledger.restore(sdk,1,second)==0);check(sdk.rows.get(1)==second);
  System.out.println("PASS: duplicate delivery, replacement, failure retry, deletion and cold restore");
 }
}
