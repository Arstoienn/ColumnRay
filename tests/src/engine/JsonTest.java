package engine;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** The parser every map is read through: what it accepts, and what it must refuse rather than
 *  silently half-read. A map file that is quietly truncated is worse than one that will not load. */
final class JsonTest {
    private JsonTest() {}

    static void run() {
        Check.group("Json");

        Check.eq(Json.parse("{}"), Map.of(), "an empty object");
        Check.eq(Json.parse("[]"), List.of(), "an empty array");
        Check.eq(Json.parse("  true "), Boolean.TRUE, "surrounding space is skipped");
        Check.eq(Json.parse("null"), null, "null");
        Check.eq(Json.parse("-2.5e2"), -250.0, "an exponent");

        Map<String, Object> m = World.obj(Json.parse("""
                // the map's own comments must not reach the parser
                { "name": "school", "cell": 1.0, // trailing comment
                  "poly": [[9, 1], [17, 8]],
                  "deep": { "a": { "b": [true, false, null] } } }
                """));
        Check.eq(m.get("name"), "school", "a string value");
        Check.eq(m.get("cell"), 1.0, "every number is a Double");
        Check.eq(World.obj(World.obj(m.get("deep")).get("a")).get("b"), Arrays.asList(true, false, null),
                "a null keeps its place in an array rather than being dropped, so the indices still line up");
        List<Object> poly = World.list(m.get("poly"));
        Check.eq(poly.size(), 2, "an array of arrays");
        Check.eq(World.list(poly.get(1)).get(0), 17.0, "a nested number");

        Check.eq(Json.parse("\"a\\nb\\t\\u0041\\\"\""), "a\nb\tA\"", "the escapes the parser knows");
        Check.eq(Json.parse("\"\\q\""), "q", "an unknown escape keeps the character");

        Check.eq(World.obj(Json.parse("{\"a\":1,\"a\":2}")).get("a"), 2.0, "a repeated key: the last wins");

        Check.rejects(() -> Json.parse("{} {}"), "trailing content", "two values in one file");
        Check.rejects(() -> Json.parse("{\"a\":1"), "expected '}'", "an object that is never closed");
        Check.rejects(() -> Json.parse("[1,2"), "expected ']'", "an array that is never closed");
        Check.rejects(() -> Json.parse("\"abc"), "unterminated string", "a string that is never closed");
        Check.rejects(() -> Json.parse("{a:1}"), "expected a string key", "an unquoted key");
        Check.rejects(() -> Json.parse(""), "unexpected end of input", "an empty file");
        Check.rejects(() -> Json.parse("tru"), "expected true", "a half-written literal");

        // A map file comes from somewhere - a converter, a download, half a file. None of these may
        // reach the caller as an Error from inside the parser's own arithmetic.
        String backslash = "\\";
        Check.rejects(() -> Json.parse("[".repeat(200)), "nested deeper than",
                "nesting deep enough to overflow the parser's stack");
        Check.rejects(() -> Json.parse("\"a" + backslash), "a backslash with nothing after it",
                "a string that ends on its escape character");
        Check.rejects(() -> Json.parse("\"" + backslash + "u12\""), "cut short",
                "a unicode escape with two digits instead of four");
        Check.rejects(() -> Json.parse("\"" + backslash + "uZZZZ\""), "four hex digits",
                "a unicode escape that is not hex");
        Check.rejects(() -> Json.parse("1.2.3"), "not a number",
                "a run of number characters that is not a number");
        Check.that(Json.parse("[".repeat(120) + "]".repeat(120)) != null,
                "nesting deeper than any map, but not deep enough to be dangerous, still parses");

        // The line number is the whole point of the message: a map file is thousands of lines.
        Check.rejects(() -> Json.parse("{\n\"a\": 1,\n\"b\": }\n"), "line 3", "the line a problem is on");
    }
}
