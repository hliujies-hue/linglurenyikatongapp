package com.lingluren.yikatong.terminal;
import org.json.JSONObject;

/** Wire validation shared by the durable journal and retry loop. */
public final class TerminalProtocol {
    public static final String VERSION="0.3.7-repeat";
    private TerminalProtocol(){}
    public static JSONObject ref(JSONObject session) throws Exception {
        return new JSONObject().put("sessionId",session.getString("sessionId")).put("sessionToken",session.getString("sessionToken")).put("generation",session.getLong("generation")).put("fingerprint",session.getString("fingerprint"));
    }
    public static void validateSession(JSONObject s) throws Exception {
        if(s.getInt("protocolVersion")!=2 || !s.getString("sessionId").matches("[a-f0-9]{48}") || !s.getString("sessionToken").matches("[a-f0-9]{64}") || s.getLong("generation")<1 || s.getString("fingerprint").length()<16)
            throw new IllegalStateException("平台会话格式不匹配，请更新配套平台");
    }
    public static void validateReceipt(JSONObject event,JSONObject r) throws Exception {
        if(!r.optBoolean("persisted") || !event.getString("eventId").equals(r.optString("eventId")) || event.getLong("sequence")!=r.optLong("sequence") || !r.optString("receipt").matches("[a-f0-9]{48}"))throw new IllegalStateException("平台回执与原记录不一致");
    }
    public static boolean canOpen(JSONObject r){return r.optBoolean("persisted")&&r.optBoolean("open")&&!r.optBoolean("duplicate")&&"PASS".equals(r.optString("result"));}
}
