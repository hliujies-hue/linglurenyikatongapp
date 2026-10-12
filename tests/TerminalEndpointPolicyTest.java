import com.lingluren.yikatong.terminal.TerminalEndpointPolicy;

public final class TerminalEndpointPolicyTest {
    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Untrusted credential destination accepted");
    }
    public static void main(String[] args) {
        TerminalEndpointPolicy.requireOrigin("https://yktapi.qiliai.com");
        for(String origin:new String[]{"http://yktapi.qiliai.com","https://ykt.qiliai.com","https://yktapi.qiliai.com.evil.test","https://user@yktapi.qiliai.com"})
            rejected(()->TerminalEndpointPolicy.requireOrigin(origin));
        for(String path:new String[]{"/api/terminal/login","/api/terminal/bind","/api/terminal/heartbeat","/api/auth/logout"}) TerminalEndpointPolicy.requirePath(path);
        for(String path:new String[]{"//evil.test/api/login","/api/../admin","/api/%2e%2e/login","/api/login?token=secret","/api/login#fragment","/api/\\evil"})
            rejected(()->TerminalEndpointPolicy.requirePath(path));
        rejected(()->TerminalEndpointPolicy.requirePath(null));
        System.out.println("PASS: HTTPS API origin, credential destination, path traversal and query rejection");
    }
}
