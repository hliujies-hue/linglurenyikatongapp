package com.lingluren.yikatong.terminal;
import android.test.InstrumentationTestCase;
import android.content.Context;
import org.json.JSONObject;
/** Tests use an isolated database; they never alter the signed-in account or its queue. */
public final class TerminalStoreInstrumentedTest extends InstrumentationTestCase {
 private Context context; private TerminalStore store; private String name;
 @Override protected void setUp() throws Exception {super.setUp();context=getInstrumentation().getTargetContext();name="terminal-test-"+System.nanoTime()+".db";store=new TerminalStore(context,name);}
 @Override protected void tearDown() throws Exception {if(store!=null)store.close();if(context!=null&&name!=null)context.deleteDatabase(name);super.tearDown();}
 private JSONObject session(String id) throws Exception {return new JSONObject().put("protocolVersion",2).put("sessionId",id).put("sessionToken",repeat("b",64)).put("generation",1).put("fingerprint","journal-test-phone-1").put("state","ACTIVE").put("resetRequired",true);}
 private static String repeat(String s,int n){StringBuilder b=new StringBuilder();while(n-->0)b.append(s);return b.toString();}
 private JSONObject receipt(JSONObject event) throws Exception{return new JSONObject().put("persisted",true).put("eventId",event.getString("eventId")).put("sequence",event.getLong("sequence")).put("receipt",repeat("c",48)).put("result","PASS").put("open",true).put("duplicate",false);}
 public void testJournalSurvivesRestartAndNeverReplaysAdmission() throws Exception {
  String id=repeat("a",48);store.bind(session(id));JSONObject event=store.event(42,"PLATFORM_TIMEOUT");assertEquals(1,event.getLong("sequence"));
  store.close();store=new TerminalStore(context,name);assertEquals(1,store.pendingCount(id));JSONObject saved=store.pending(id).get(0);assertEquals(event.toString(),saved.toString());
  JSONObject response=receipt(saved);store.receipt(saved,response);store.close();store=new TerminalStore(context,name);assertNotNull(store.pending(id).get(0).getJSONObject("savedResponse"));store.confirmed(saved);assertEquals(0,store.pendingCount(id));
  assertEquals(2,store.event(42,"NETWORK_UNAVAILABLE").getLong("sequence"));response.put("duplicate",true);assertFalse(TerminalProtocol.canOpen(response));
 }
 public void testReceiptAndSwitchBarrier() throws Exception {
  String id=repeat("a",48);store.bind(session(id));JSONObject e=store.event(7,"NO_MATCH");JSONObject wrong=receipt(e).put("sequence",2);
  try{store.receipt(e,wrong);fail("accepted wrong receipt");}catch(IllegalStateException expected){}
  try{store.bind(session(repeat("d",48)));fail("overwrote pending session");}catch(IllegalStateException expected){}
  store.state(id,"PAUSING");store.bind(session(id));assertEquals("PAUSING",store.active().getString("state"));
  try{store.event(7,"NO_MATCH");fail("captured during exit");}catch(IllegalStateException expected){}
 }
 public void testCleanupPreservesUnconfirmedAndSequence() throws Exception {
  String id=repeat("a",48);store.bind(session(id));JSONObject a=store.event(1,"NO_MATCH"),b=store.event(2,"NO_MATCH");store.receipt(a,receipt(a));store.confirmed(a);
  store.cleanup(System.currentTimeMillis()+8L*86400000);assertEquals(1,store.pendingCount(id));assertEquals(b.getString("eventId"),store.pending(id).get(0).getString("eventId"));assertEquals(3,store.event(3,"NO_MATCH").getLong("sequence"));
 }
}
