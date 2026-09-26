package dev.sobatista.tuplo.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the textual field syntax used by scripts and the CLI into {@link Field}s, tuples and schemas.
 *
 * <p>Grammar of a single field:
 * <ul>
 *   <li>{@code "abc"} → string. {@code "*"} → any string. {@code "abc*"} → prefix. {@code "*abc"} → suffix.</li>
 *   <li>{@code Type(1, "a")} → a concrete object. {@code Type} → any object of that type. {@code null} → any object.</li>
 * </ul>
 * Object arguments are integers or quoted strings.
 */
public final class Fields {

    private Fields() {}

    /** Parse a tuple body like {@code "a", Point(1, 2)} (concrete fields only). */
    public static Tuple parseTuple(String body) {
        return new Tuple(parseFields(body));
    }

    /** Parse a schema body like {@code "a*", Point, null, "*"} (concrete fields and wildcards). */
    public static Schema parseSchema(String body) {
        return new Schema(parseFields(body));
    }

    /** Split on top-level commas (ignoring commas inside quotes or parentheses) and parse each field. */
    public static List<Field> parseFields(String body) {
        List<Field> out = new ArrayList<>();
        for (String tok : splitTopLevel(body)) {
            String t = tok.trim();
            if (!t.isEmpty()) out.add(parseField(t));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("no fields in: " + body);
        return out;
    }

    public static Field parseField(String tok) {
        String t = tok.trim();
        if (t.equals("null")) return new Field.AnyObj();

        if (t.startsWith("\"")) {
            if (!t.endsWith("\"") || t.length() < 2) throw new IllegalArgumentException("unterminated string: " + tok);
            String s = t.substring(1, t.length() - 1);
            if (s.equals("*")) return new Field.AnyStr();
            if (s.length() > 1 && s.startsWith("*")) return new Field.SuffixStr(s.substring(1));
            if (s.length() > 1 && s.endsWith("*")) return new Field.PrefixStr(s.substring(0, s.length() - 1));
            return new Field.Str(s);
        }

        int lp = t.indexOf('(');
        if (lp < 0) {                                   // bare identifier → "any object of this type"
            requireIdentifier(t);
            return new Field.AnyOfType(t);
        }
        if (!t.endsWith(")")) throw new IllegalArgumentException("malformed object: " + tok);
        String type = t.substring(0, lp).trim();
        requireIdentifier(type);
        String argsBody = t.substring(lp + 1, t.length() - 1);
        List<Object> args = new ArrayList<>();
        for (String a : splitTopLevel(argsBody)) {
            String at = a.trim();
            if (at.isEmpty()) continue;
            if (at.startsWith("\"") && at.endsWith("\"") && at.length() >= 2) {
                args.add(at.substring(1, at.length() - 1));
            } else {
                try {
                    args.add(Integer.valueOf(at));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("object arg must be an int or a \"string\": " + at);
                }
            }
        }
        return new Field.Obj(type, args);
    }

    private static void requireIdentifier(String s) {
        if (s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            throw new IllegalArgumentException("not a valid type name: " + s);
        }
    }

    /** Split on commas that are not inside double quotes or parentheses. */
    private static List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean inQuote = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuote = !inQuote;
            if (!inQuote && c == '(') depth++;
            if (!inQuote && c == ')') depth--;
            if (c == ',' && depth == 0 && !inQuote) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        return parts;
    }
}
