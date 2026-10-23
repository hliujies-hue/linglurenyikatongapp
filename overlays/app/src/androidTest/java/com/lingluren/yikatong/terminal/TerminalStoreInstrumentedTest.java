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
 public void testReauthenticationPreservesJournalFeaturesAndDrainState() throws Exception {
  String id=repeat("a",48);store.bind(session(id).put("username","staff").put("origin","https://example.test").put("accessToken","old"));
  store.resetDone(id);store.saveFace(7,"original-hash",new byte[512]);JSONObject event=store.event(7,"NETWORK_UNAVAILABLE");
  store.receipt(event,receipt(event));String pending=store.pending(id).get(0).toString();
  for(String state:new String[]{"ACTIVE","PAUSING","DRAINING"}){
   store.state(id,state);store.updateAccessToken(id,"renewed-"+state);store.close();store=new TerminalStore(context,name);
   JSONObject active=store.active();assertEquals("renewed-"+state,active.getString("accessToken"));assertEquals(state,active.getString("state"));
   assertEquals(id,active.getString("sessionId"));assertEquals(repeat("b",64),active.getString("sessionToken"));assertEquals(1L,active.getLong("generation"));
   assertTrue(active.getBoolean("resetDone"));assertEquals(1L,active.getLong("lastSequence"));assertEquals(1L,store.pendingCount(id));
   assertEquals(pending,store.pending(id).get(0).toString());assertNotNull(store.faceFeature(7,"original-hash"));
  }
  try{store.updateAccessToken(repeat("d",48),"wrong-session");fail("updated another session");}catch(IllegalStateException expected){}
  try{store.updateAccessToken(id,"");fail("saved empty token");}catch(IllegalArgumentException expected){}
  assertEquals("renewed-DRAINING",store.active().getString("accessToken"));assertEquals(pending,store.pending(id).get(0).toString());
 }
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
 public void testFaceBatchUsesKeysetAndPreservesIdGaps() throws Exception {
  int first=store.saveFace(10,"first",new byte[512]);
  int deleted=store.saveFace(11,"removed",new byte[512]);
  int last=store.saveFace(12,"last",new byte[512]);store.deleteFace(11);
  try(android.database.Cursor rows=store.faceBatch(0,1)){assertTrue(rows.moveToNext());assertEquals(first,rows.getInt(0));assertEquals(512,rows.getBlob(1).length);assertFalse(rows.moveToNext());}
  try(android.database.Cursor rows=store.faceBatch(first,128)){assertTrue(rows.moveToNext());assertEquals(last,rows.getInt(0));assertTrue(last>deleted);assertFalse(rows.moveToNext());}
  try(android.database.Cursor rows=store.faceBatch(last,128)){assertFalse(rows.moveToNext());}
  assertEquals(2L,store.faces());
 }

 public void testLegacyPlaintextMigrationPreservesSessionEventsAndFeatures() throws Exception {
  store.close();context.deleteDatabase(name);
  android.database.sqlite.SQLiteDatabase db=context.openOrCreateDatabase(name,0,null);
  db.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,snapshot TEXT NOT NULL,state TEXT NOT NULL,last_sequence INTEGER NOT NULL DEFAULT 0,reset_done INTEGER NOT NULL DEFAULT 0,active_slot INTEGER UNIQUE DEFAULT 1)");
  db.execSQL("CREATE TABLE events(session_id TEXT NOT NULL REFERENCES sessions(id),sequence INTEGER NOT NULL,event_id TEXT NOT NULL UNIQUE,payload TEXT NOT NULL,response TEXT,confirmed_at INTEGER,created_at INTEGER NOT NULL,PRIMARY KEY(session_id,sequence))");
  db.execSQL("CREATE TABLE faces(local_id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER NOT NULL UNIQUE,target_hash TEXT NOT NULL,feature BLOB NOT NULL)");
  db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
  String id=repeat("a",48);JSONObject snapshot=session(id).put("accessToken","legacy-sensitive-token");
  JSONObject event=TerminalProtocol.ref(snapshot).put("sequence",1).put("eventId","test-legacy-event").put("userId",7).put("failure","NETWORK_UNAVAILABLE");
  db.execSQL("INSERT INTO sessions(id,snapshot,state,last_sequence) VALUES(?,?,?,1)",new Object[]{id,snapshot.toString(),"ACTIVE"});
  db.execSQL("INSERT INTO events(session_id,sequence,event_id,payload,created_at) VALUES(?,1,?,?,?)",new Object[]{id,"test-legacy-event",event.toString(),System.currentTimeMillis()});
  byte[] feature=new byte[512];java.util.Arrays.fill(feature,(byte)42);
  db.execSQL("INSERT INTO faces(user_id,target_hash,feature) VALUES(7,'hash',?)",new Object[]{feature});
  db.execSQL("INSERT INTO metadata(name,value) VALUES('library_id','legacy-library')");
  db.setVersion(1);db.close();store=new TerminalStore(context,name);
  assertEquals("legacy-sensitive-token",store.active().getString("accessToken"));
  assertEquals(event.toString(),store.pending(id).get(0).toString());assertEquals("legacy-library",store.metadata("library_id",""));
  assertTrue(java.util.Arrays.equals(feature,store.faceFeature(7,"hash")));
  try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT snapshot FROM sessions",null)){assertTrue(c.moveToFirst());assertTrue(c.getString(0).startsWith("enc1:"));assertFalse(c.getString(0).contains("legacy-sensitive-token"));}
  try(android.database.Cursor c=store.getReadableDatabase().rawQuery("SELECT feature FROM faces",null)){assertTrue(c.moveToFirst());assertFalse(java.util.Arrays.equals(feature,c.getBlob(0)));}
  store.close();store=new TerminalStore(context,name);assertEquals(1L,store.pendingCount(id));assertEquals("legacy-sensitive-token",store.active().getString("accessToken"));
  store.receipt(event,receipt(event));assertEquals(1L,store.dashboard(id).getLong("passed"));
 }
 public void testFeatureCipherRejectsWrongOwnerAndTampering() throws Exception {
  store.saveFace(1,"hash",new byte[512]);store.saveFace(2,"hash",new byte[512]);
  store.getWritableDatabase().execSQL("UPDATE faces SET feature=(SELECT feature FROM faces WHERE user_id=1) WHERE user_id=2");
  try{store.faceFeature(2,"hash");fail("accepted another person's encrypted template");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("解密校验失败"));}
 }
 public void testCompletedMigrationCheckpointsDoNotDoubleEncrypt() throws Exception {
  store.saveFace(1,"hash",new byte[512]);
  android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();
  db.execSQL("UPDATE crypto_state SET value=1 WHERE name='faces'");
  db.execSQL("DELETE FROM crypto_state WHERE name='complete'");
  db.execSQL("INSERT INTO faces(user_id,target_hash,feature) VALUES(2,'hash',?)",new Object[]{new byte[512]});
  store.close();store=new TerminalStore(context,name);
  assertEquals(512,store.faceFeature(1,"hash").length);assertEquals(512,store.faceFeature(2,"hash").length);
 }
}
