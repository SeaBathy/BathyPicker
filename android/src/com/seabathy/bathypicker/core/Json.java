package com.seabathy.bathypicker.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Writes the maps and lists the page reads. Unlike org.json, it keeps null values (the page checks for them). */
public final class Json {
    private Json() {}

    /**
     * Reads JSON into maps (LinkedHashMap), lists, strings, Doubles, Booleans and nulls. For the
     * survey catalogue, which the core also reads on a computer (no org.json there).
     */
    public static Object parse(String text) {
        int[] pos = {0};
        Object v = value(text, pos);
        skip(text, pos);
        if (pos[0] != text.length()) throw new IllegalArgumentException("Unexpected text after the JSON value");
        return v;
    }

    private static void skip(String s, int[] p) {
        while (p[0] < s.length() && Character.isWhitespace(s.charAt(p[0]))) p[0]++;
    }

    private static Object value(String s, int[] p) {
        skip(s, p);
        if (p[0] >= s.length()) throw new IllegalArgumentException("Unexpected end of JSON");
        char c = s.charAt(p[0]);
        if (c == '{') {
            Map<String, Object> m = new LinkedHashMap<>();
            p[0]++;
            skip(s, p);
            if (s.charAt(p[0]) == '}') { p[0]++; return m; }
            while (true) {
                skip(s, p);
                String k = string(s, p);
                skip(s, p);
                if (s.charAt(p[0]++) != ':') throw new IllegalArgumentException("Expected : in JSON");
                m.put(k, value(s, p));
                skip(s, p);
                char d = s.charAt(p[0]++);
                if (d == '}') return m;
                if (d != ',') throw new IllegalArgumentException("Expected , or } in JSON");
            }
        }
        if (c == '[') {
            List<Object> l = new ArrayList<>();
            p[0]++;
            skip(s, p);
            if (s.charAt(p[0]) == ']') { p[0]++; return l; }
            while (true) {
                l.add(value(s, p));
                skip(s, p);
                char d = s.charAt(p[0]++);
                if (d == ']') return l;
                if (d != ',') throw new IllegalArgumentException("Expected , or ] in JSON");
            }
        }
        if (c == '"') return string(s, p);
        if (s.startsWith("true", p[0])) { p[0] += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p[0])) { p[0] += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p[0])) { p[0] += 4; return null; }
        int a = p[0];
        while (p[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(p[0])) >= 0) p[0]++;
        if (a == p[0]) throw new IllegalArgumentException("Unexpected character in JSON");
        return Double.parseDouble(s.substring(a, p[0]));
    }

    private static String string(String s, int[] p) {
        if (s.charAt(p[0]) != '"') throw new IllegalArgumentException("Expected a string in JSON");
        p[0]++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = s.charAt(p[0]++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            char e = s.charAt(p[0]++);
            switch (e) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u': sb.append((char) Integer.parseInt(s.substring(p[0], p[0] + 4), 16)); p[0] += 4; break;
                default: sb.append(e); break;
            }
        }
    }

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String) {
            quote(sb, (String) o);
        } else if (o instanceof Boolean) {
            sb.append(o.toString());
        } else if (o instanceof Double || o instanceof Float) {
            double d = ((Number) o).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d).append(".0");
            else sb.append(Double.toString(d));
        } else if (o instanceof Number) {
            sb.append(o.toString());
        } else if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object v : (Collection<?>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, v);
            }
            sb.append(']');
        } else {
            quote(sb, o.toString());
        }
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        quote(sb, s);
        return sb.toString();
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '<': sb.append("\\u003c"); break;
                case ' ': sb.append("\\u2028"); break;
                case ' ': sb.append("\\u2029"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
