package covellite.core.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny pure-JDK JSON codec. Supports the value types needed by CoveMCP:
 * null, boolean, Number (BigDecimal for decimals, Long for integers), String,
 * List<Object>, and Map<String, Object>.
 */
public final class Json {

    private Json() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        return (Map<String, Object>) parse(text);
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String text) {
        return (List<Object>) parse(text);
    }

    public static Object parse(String text) {
        Parser p = new Parser(text);
        Object value = p.parseValue();
        p.skipWhitespace();
        if (p.pos < p.len) {
            throw new IllegalArgumentException("Unexpected trailing data at " + p.pos);
        }
        return value;
    }

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        append(value, sb);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        appendPretty(value, sb, 0);
        return sb.toString();
    }

    private static void append(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof String s) {
            appendString(s, sb);
        } else if (value instanceof Number n) {
            sb.append(n.toString());
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(',');
                append(item, sb);
                first = false;
            }
            sb.append(']');
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(',');
                appendString((String) e.getKey(), sb);
                sb.append(':');
                append(e.getValue(), sb);
                first = false;
            }
            sb.append('}');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
        }
    }

    private static void appendPretty(Object value, StringBuilder sb, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof String s) {
            appendString(s, sb);
        } else if (value instanceof Number n) {
            sb.append(n.toString());
        } else if (value instanceof List<?> list) {
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append("[\n");
            boolean first = true;
            for (Object item : list) {
                if (!first) sb.append(",\n");
                pad(sb, indent + 2);
                appendPretty(item, sb, indent + 2);
                first = false;
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append(']');
        } else if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append("{\n");
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) sb.append(",\n");
                pad(sb, indent + 2);
                appendString((String) e.getKey(), sb);
                sb.append(':');
                appendPretty(e.getValue(), sb, indent + 2);
                first = false;
            }
            sb.append('\n');
            pad(sb, indent);
            sb.append('}');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
        }
    }

    private static void pad(StringBuilder sb, int n) {
        for (int i = 0; i < n; i++) sb.append(' ');
    }

    private static void appendString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        final String text;
        final int len;
        int pos = 0;

        Parser(String text) {
            this.text = text;
            this.len = text.length();
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= len) throw new IllegalArgumentException("Unexpected end of input");
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    return map;
                }
                throw new IllegalArgumentException("Expected ',' or '}' at " + pos);
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    return list;
                }
                throw new IllegalArgumentException("Expected ',' or ']' at " + pos);
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= len) throw new IllegalArgumentException("Invalid escape");
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"', '\\', '/' -> sb.append(esc);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > len) throw new IllegalArgumentException("Invalid unicode escape");
                            String hex = text.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("Invalid escape \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        Object parseLiteral(String literal, Object value) {
            if (text.startsWith(literal, pos)) {
                pos += literal.length();
                return value;
            }
            throw new IllegalArgumentException("Expected '" + literal + "' at " + pos);
        }

        Object parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < len && Character.isDigit(text.charAt(pos))) pos++;
            boolean integer = true;
            if (pos < len && text.charAt(pos) == '.') {
                integer = false;
                pos++;
                while (pos < len && Character.isDigit(text.charAt(pos))) pos++;
            }
            if (pos < len && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                integer = false;
                pos++;
                if (pos < len && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                while (pos < len && Character.isDigit(text.charAt(pos))) pos++;
            }
            String num = text.substring(start, pos);
            try {
                if (integer) {
                    long l = Long.parseLong(num);
                    return l;
                }
                return new BigDecimal(num);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid number: " + num);
            }
        }

        void skipWhitespace() {
            while (pos < len) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        char peek() {
            return pos < len ? text.charAt(pos) : '\0';
        }

        void expect(char expected) {
            if (pos >= len || text.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at " + pos);
            }
            pos++;
        }
    }
}
