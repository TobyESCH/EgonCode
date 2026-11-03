package dev.egoncode;

import java.util.*;

/** Small JSON codec so the CLI has no runtime dependencies. */
final class Json {
    private Json() {}

    static Map<String,Object> obj(Object... values) {
        Map<String,Object> out = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) out.put((String) values[i], values[i + 1]);
        return out;
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> map(Object value) {
        return value instanceof Map<?,?> ? (Map<String,Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        return value instanceof List<?> ? (List<Object>) value : List.of();
    }

    static String str(Object value) { return value == null ? "" : String.valueOf(value); }
    static String get(Map<String,Object> value, String key) { return str(value.get(key)); }
    static long number(Object value, long fallback) {
        return value instanceof Number n ? n.longValue() : fallback;
    }

    static Object parse(String source) {
        Parser p = new Parser(source);
        Object result = p.value();
        p.space();
        if (p.at < source.length()) throw new IllegalArgumentException("Trailing JSON at " + p.at);
        return result;
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) out.append("null");
        else if (value instanceof String s) {
            out.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
                }
            }
            out.append('"');
        } else if (value instanceof Number || value instanceof Boolean) out.append(value);
        else if (value instanceof Map<?,?> map) {
            out.append('{'); boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) out.append(','); first = false;
                append(out, String.valueOf(entry.getKey())); out.append(':'); append(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof Iterable<?> items) {
            out.append('['); boolean first = true;
            for (Object item : items) { if (!first) out.append(','); first = false; append(out, item); }
            out.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON type: " + value.getClass());
    }

    private static final class Parser {
        final String source; int at;
        Parser(String source) { this.source = source; }
        void space() { while (at < source.length() && Character.isWhitespace(source.charAt(at))) at++; }
        char peek() { space(); return at < source.length() ? source.charAt(at) : '\0'; }
        char take() { return source.charAt(at++); }
        Object value() {
            return switch (peek()) {
                case '{' -> object(); case '[' -> array(); case '"' -> string();
                case 't' -> literal("true", true); case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }
        Object literal(String word, Object result) {
            if (!source.startsWith(word, at)) throw new IllegalArgumentException("Invalid JSON at " + at);
            at += word.length(); return result;
        }
        Map<String,Object> object() {
            take(); Map<String,Object> result = new LinkedHashMap<>();
            if (peek() == '}') { take(); return result; }
            while (true) {
                if (peek() != '"') throw new IllegalArgumentException("Expected key at " + at);
                String key = string(); if (peek() != ':') throw new IllegalArgumentException("Expected : at " + at);
                take(); result.put(key, value()); char next = peek(); take();
                if (next == '}') return result;
                if (next != ',') throw new IllegalArgumentException("Expected , at " + (at - 1));
            }
        }
        List<Object> array() {
            take(); List<Object> result = new ArrayList<>();
            if (peek() == ']') { take(); return result; }
            while (true) {
                result.add(value()); char next = peek(); take();
                if (next == ']') return result;
                if (next != ',') throw new IllegalArgumentException("Expected , at " + (at - 1));
            }
        }
        String string() {
            take(); StringBuilder result = new StringBuilder();
            while (at < source.length()) {
                char c = take(); if (c == '"') return result.toString();
                if (c == '\\') {
                    if (at >= source.length()) break;
                    c = take();
                    switch (c) {
                        case 'n' -> result.append('\n'); case 'r' -> result.append('\r');
                        case 't' -> result.append('\t'); case 'b' -> result.append('\b');
                        case 'f' -> result.append('\f'); case '"' -> result.append('"');
                        case '\\' -> result.append('\\'); case '/' -> result.append('/');
                        case 'u' -> { result.append((char) Integer.parseInt(source.substring(at, at + 4), 16)); at += 4; }
                        default -> throw new IllegalArgumentException("Bad escape at " + at);
                    }
                } else result.append(c);
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }
        Number number() {
            int start = at;
            while (at < source.length() && "-+0123456789.eE".indexOf(source.charAt(at)) >= 0) at++;
            if (start == at) throw new IllegalArgumentException("Invalid JSON at " + at);
            String text = source.substring(start, at);
            if (text.contains(".") || text.contains("e") || text.contains("E")) return Double.parseDouble(text);
            return Long.parseLong(text);
        }
    }
}
