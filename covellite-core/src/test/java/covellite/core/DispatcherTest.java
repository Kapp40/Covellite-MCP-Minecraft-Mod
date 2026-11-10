package covellite.core;

import covellite.core.dispatch.Dispatcher;

import java.util.Map;

public final class DispatcherTest {

    public static void run() {
        testBasicDispatch();
        testUnknownMethod();
        testMissingMethod();
        testException();
        System.out.println("  Dispatcher: all tests passed");
    }

    private static void testBasicDispatch() {
        Dispatcher d = new Dispatcher();
        d.register("echo", params -> Map.of("value", params.get("value")));
        Map<String, Object> response = d.dispatch(Map.of("id", "1", "method", "echo", "params", Map.of("value", 42)));
        T.equal("dispatch id", "1", response.get("id"));
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        T.equal("dispatch result", 42, result.get("value"));
    }

    private static void testUnknownMethod() {
        Dispatcher d = new Dispatcher();
        Map<String, Object> response = d.dispatch(Map.of("id", "2", "method", "nope"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        T.equal("unknown code", "unknown_method", error.get("code"));
    }

    private static void testMissingMethod() {
        Dispatcher d = new Dispatcher();
        Map<String, Object> response = d.dispatch(Map.of("id", "3"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        T.equal("missing code", "invalid_request", error.get("code"));
    }

    private static void testException() {
        Dispatcher d = new Dispatcher();
        d.register("fail", params -> { throw new IllegalArgumentException("bad arg"); });
        Map<String, Object> response = d.dispatch(Map.of("id", "4", "method", "fail"));
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        T.equal("exception code", "invalid_params", error.get("code"));
    }
}
