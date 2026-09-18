package engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON parser (// comments allowed). Object -> Map, array -> List, number -> Double. */
final class Json {
    /** A map file is data, and data from anywhere describes its own nesting. The parser recurses
     *  once per level, so without a ceiling a file of nothing but brackets is a stack overflow -
     *  an Error, thrown from wherever the stack happened to run out, rather than a refusal that
     *  says which line is at fault. The limit is far above any map: Haven's deepest is 7. */
    private static final int MAX_DEPTH = 128;

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    static Object parse(String text) {
        Json j = new Json(text);
        Object v = j.value(0);
        j.ws();
        if (j.i != j.s.length()) throw j.err("trailing content");
        return v;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw err("nested deeper than " + MAX_DEPTH + " levels");
        ws();
        if (i >= s.length()) throw err("unexpected end of input");
        return switch (s.charAt(i)) {
            case '{' -> object(depth + 1);
            case '[' -> array(depth + 1);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw err("expected a string key");
            String key = string();
            ws();
            expect(':');
            m.put(key, value(depth));
            ws();
            if (peek() == ',') { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') { i++; return l; }
        while (true) {
            l.add(value(depth));
            ws();
            if (peek() == ',') { i++; continue; }
            expect(']');
            return l;
        }
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("a backslash with nothing after it");
            char e = s.charAt(i++);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    if (i + 4 > s.length()) throw err("a \\u escape cut short");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException notHex) {
                        throw err("a \\u escape that is not four hex digits");
                    }
                    i += 4;
                }
                default -> b.append(e);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw err("unexpected character '" + s.charAt(i) + "'");
        // The scan above accepts any run of number characters, so "1.2.3" and "--4" reach this
        // point and Double.parseDouble throws where it stands. Thrown from here it says which line.
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException notANumber) {
            throw err("not a number: " + s.substring(start, i));
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw err("expected " + word);
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) i++;
            else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else break;
        }
    }

    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

    private void expect(char c) {
        if (peek() != c) throw err("expected '" + c + "'");
        i++;
    }

    private IllegalArgumentException err(String msg) {
        int line = 1;
        for (int k = 0; k < Math.min(i, s.length()); k++) if (s.charAt(k) == '\n') line++;
        return new IllegalArgumentException("JSON line " + line + ": " + msg);
    }
}
