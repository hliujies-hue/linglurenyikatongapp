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
 public void testFeatureReuseRequiresSamePersonAndHashAfterRestart() throws Exception {
  byte[] feature=new byte[512];feature[0]=42;store.saveFace(7,"hash-one",feature);
  store.close();store=new TerminalStore(context,name);
  assertEquals(42,store.faceFeature(7,"hash-one")[0]);
  assertNull(store.faceFeature(7,"hash-two"));assertNull(store.faceFeature(8,"hash-one"));
  store.deleteFace(7);assertNull(store.faceFeature(7,"hash-one"));
 }

 public void testResumedSessionCannotReplaceLostJournal() throws Exception {
  try{store.bind(session(repeat("a",48)).put("resumed",true).put("libraryMode","SHARED"));fail("lost journal silently resumed");}catch(IllegalStateException expected){}
  assertNull(store.active());
 }
 public void testLibraryPersistsAcrossLogoutRestartAndNewAccount() throws Exception {
  String origin="https://platform.example",id=repeat("a",48),library=store.libraryId(origin);
  store.bind(session(id).put("origin",origin).put("libraryMode","SHARED").put("libraryId",library));
  store.saveFace(7,"stable-hash",new byte[512]);store.resetDone(id);store.state(id,"RELEASED");
  store.close();store=new TerminalStore(context,name);
  assertEquals(library,store.libraryId(origin));assertEquals(1L,store.faces());assertNull(store.active());
  store.bind(session(repeat("d",48)).put("origin",origin).put("libraryMode","SHARED").put("libraryId",library));
  assertNotNull(store.faceFeature(7,"stable-hash"));assertFalse(store.active().getBoolean("resetDone"));
  try{store.switchLibrary("https://other.example");fail("changed platform with active session");}catch(IllegalStateException expected){}
  assertEquals(1L,store.faces());
  store.state(repeat("d",48),"RELEASED");store.switchLibrary("https://other.example");
  assertEquals(0L,store.faces());assertFalse(library.equals(store.libraryId("https://other.example")));
 }
 public void testSharedUpgradeKeepsJournalAndRequestsReadinessAck() throws Exception {
  String id=repeat("a",48);store.bind(session(id));store.resetDone(id);JSONObject e=store.event(7,"NO_MATCH");
  String library=store.libraryId("https://platform.example");
  store.bind(session(id).put("libraryMode","SHARED").put("libraryId",library));
  assertFalse(store.active().getBoolean("resetDone"));assertEquals(1L,store.active().getLong("lastSequence"));
  assertEquals(e.getString("eventId"),store.pending(id).get(0).getString("eventId"));
 }
 public void testDashboardSeparatesConfirmedPendingAndAccountHistory() throws Exception {
  String id=repeat("a",48);store.bind(session(id));
  JSONObject pass=store.event(7,""),denied=store.event(0,"NO_MATCH");store.event(8,"NETWORK_UNAVAILABLE");
  store.receipt(pass,receipt(pass));store.confirmed(pass);
  store.receipt(denied,receipt(denied).put("result","DENY").put("reason","NO_MATCH").put("open",false));
  JSONObject data=store.dashboard(id);assertEquals(3,data.getInt("today"));assertEquals(1,data.getInt("passed"));assertEquals(1,data.getInt("denied"));assertEquals(1,data.getInt("awaiting"));assertEquals(2,data.getInt("pending"));
  assertEquals(0,store.dashboard(repeat("d",48)).getInt("today"));
  assertEquals(3,data.getJSONArray("items").length());
 }
 public void testRecordArchiveMergeAndScope()throws Exception {
  String id=repeat("a",48),origin="https://example.com",fp="journal-test-phone-1";
  store.bind(session(id).put("origin",origin));JSONObject e=store.event(42,"NO_MATCH");store.receipt(e,receipt(e));store.confirmed(e);
  JSONObject archive=store.backupRecords(origin,fp);assertEquals(0,store.restoreRecords(archive,origin,fp));
  try{store.restoreRecords(archive,origin,"other-device");fail("cross-device restore");}catch(IllegalStateException expected){}
  store.getWritableDatabase().delete("events","session_id=?",new String[]{id});assertEquals(1,store.restoreRecords(archive,origin,fp));assertEquals(1,store.pendingCount(id));assertEquals(0,store.restoreRecords(archive,origin,fp));
  assertEquals(2,store.event(43,"NO_MATCH").getLong("sequence"));
 }
}
