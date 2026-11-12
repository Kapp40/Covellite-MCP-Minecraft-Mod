package covellite.core;

import covellite.core.json.Json;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class JsonTest {

    public static void run() {
        testPrimitives();
        testStrings();
        testArrays();
        testObjects();
        testRoundTrip();
        testNumbers();
        testErrors();
        System.out.println("  Json: all tests passed");
    }

    private static void testPrimitives() {
        T.equal("null", null, Json.parse("null"));
        T.equal("true", Boolean.TRUE, Json.parse("true"));
        T.equal("false", Boolean.FALSE, Json.parse("false"));
    }

    private static void testStrings() {
        T.equal("simple string", "hello", Json.parse("\"hello\""));
        T.equal("escaped string", "a\nb\tc", Json.parse("\"a\\nb\\tc\""));
        T.equal("quote", "say \"hi\"", Json.parse("\"say \\\"hi\\\"\""));
        T.equal("unicode", "a", Json.parse("\"\\u0061\""));
    }

    @SuppressWarnings("unchecked")
    private static void testArrays() {
        List<Object> arr = Json.parseArray("[1, 2, 3]");
        T.equal("array size", 3, arr.size());
        T.equal("array[0]", 1L, arr.get(0));
        T.equal("array[1]", 2L, arr.get(1));
        T.equal("array[2]", 3L, arr.get(2));

        List<Object> nested = Json.parseArray("[[], {}, null]");
        T.equal("nested size", 3, nested.size());
        T.isTrue("nested empty list", ((List<Object>) nested.get(0)).isEmpty());
        T.isTrue("nested empty map", ((Map<String, Object>) nested.get(1)).isEmpty());
        T.equal("nested null", null, nested.get(2));
    }

    private static void testObjects() {
        Map<String, Object> obj = Json.parseObject("{\"a\":1,\"b\":\"two\",\"c\":true,\"d\":null}");
        T.equal("obj.a", 1L, obj.get("a"));
        T.equal("obj.b", "two", obj.get("b"));
        T.equal("obj.c", Boolean.TRUE, obj.get("c"));
        T.equal("obj.d", null, obj.get("d"));
    }

    private static void testRoundTrip() {
        String original = "{\"name\":\"CoveMCP\",\"pos\":[1.5,2.0,3.5],\"active\":true,\"inventory\":null}";
        Object parsed = Json.parse(original);
        String serialized = Json.stringify(parsed);
        Object reparsed = Json.parse(serialized);
        T.equal("roundtrip", Json.stringify(parsed), Json.stringify(reparsed));
    }

    private static void testNumbers() {
        T.equal("integer", 42L, Json.parse("42"));
        T.equal("negative integer", -7L, Json.parse("-7"));
        T.equal("decimal", new BigDecimal("3.14"), Json.parse("3.14"));
        T.equal("negative decimal", new BigDecimal("-0.5"), Json.parse("-0.5"));
        T.equal("exponent", new BigDecimal("1e3"), Json.parse("1e3"));
    }

    private static void testErrors() {
        T.expectThrows("trailing data", IllegalArgumentException.class, () -> Json.parse("{} {}"));
        T.expectThrows("unterminated string", IllegalArgumentException.class, () -> Json.parse("\"hi"));
        T.expectThrows("invalid number", IllegalArgumentException.class, () -> Json.parse("1.2.3"));
    }
}
