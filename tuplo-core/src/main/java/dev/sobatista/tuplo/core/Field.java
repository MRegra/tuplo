package dev.sobatista.tuplo.core;

import java.io.Serializable;
import java.util.List;

/**
 * One field of a tuple or of a schema.
 *
 * <p>A tuple is a sequence of <em>concrete</em> fields: a {@link Str} (a string) or an {@link Obj}
 * (a small object: a type name plus integer/string constructor args). A schema is a sequence of
 * fields that may also contain <em>wildcards</em>, which match a family of concrete fields:
 *
 * <ul>
 *   <li>{@link AnyStr} — {@code "*"} — any string.</li>
 *   <li>{@link PrefixStr} — {@code "abc*"} — any string starting with {@code abc}.</li>
 *   <li>{@link SuffixStr} — {@code "*abc"} — any string ending with {@code abc}.</li>
 *   <li>{@link AnyOfType} — a bare type name — any object of that type.</li>
 *   <li>{@link AnyObj} — {@code null} — any object.</li>
 * </ul>
 *
 * <p>Matching is asymmetric: {@link #matches(Field)} is called on a <em>schema</em> field with a
 * <em>concrete</em> tuple field as the argument.
 */
public sealed interface Field extends Serializable
        permits Field.Str, Field.Obj, Field.AnyStr, Field.PrefixStr, Field.SuffixStr, Field.AnyOfType, Field.AnyObj {

    /** True when this (schema) field matches the given concrete tuple field. */
    boolean matches(Field concrete);

    /** True when this field carries no wildcard (valid inside a tuple, not only a schema). */
    boolean isConcrete();

    // ---- concrete fields ---------------------------------------------------

    /** A concrete string value. */
    record Str(String value) implements Field {
        public Str {
            if (value == null) throw new IllegalArgumentException("string field value must not be null");
        }
        @Override public boolean matches(Field c) { return this.equals(c); }
        @Override public boolean isConcrete() { return true; }
        @Override public String toString() { return "\"" + value + "\""; }
    }

    /** A concrete object: a type name plus constructor args (each an Integer or a String). */
    record Obj(String type, List<Object> args) implements Field {
        public Obj {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("object type must not be blank");
            args = List.copyOf(args);
            for (Object a : args) {
                if (!(a instanceof Integer) && !(a instanceof String)) {
                    throw new IllegalArgumentException("object args must be Integer or String, got " + a);
                }
            }
        }
        @Override public boolean matches(Field c) { return this.equals(c); }
        @Override public boolean isConcrete() { return true; }
        @Override public String toString() {
            var sb = new StringBuilder(type).append('(');
            for (int i = 0; i < args.size(); i++) {
                if (i > 0) sb.append(", ");
                Object a = args.get(i);
                sb.append(a instanceof String s ? "\"" + s + "\"" : a);
            }
            return sb.append(')').toString();
        }
    }

    // ---- wildcards (schema-only) ------------------------------------------

    /** {@code "*"} — matches any string. */
    record AnyStr() implements Field {
        @Override public boolean matches(Field c) { return c instanceof Str; }
        @Override public boolean isConcrete() { return false; }
        @Override public String toString() { return "\"*\""; }
    }

    /** {@code "prefix*"} — matches any string starting with {@code prefix}. */
    record PrefixStr(String prefix) implements Field {
        public PrefixStr { if (prefix == null) throw new IllegalArgumentException("prefix must not be null"); }
        @Override public boolean matches(Field c) { return c instanceof Str s && s.value().startsWith(prefix); }
        @Override public boolean isConcrete() { return false; }
        @Override public String toString() { return "\"" + prefix + "*\""; }
    }

    /** {@code "*suffix"} — matches any string ending with {@code suffix}. */
    record SuffixStr(String suffix) implements Field {
        public SuffixStr { if (suffix == null) throw new IllegalArgumentException("suffix must not be null"); }
        @Override public boolean matches(Field c) { return c instanceof Str s && s.value().endsWith(suffix); }
        @Override public boolean isConcrete() { return false; }
        @Override public String toString() { return "\"*" + suffix + "\""; }
    }

    /** A bare type name — matches any object of that exact type, whatever its args. */
    record AnyOfType(String type) implements Field {
        public AnyOfType { if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank"); }
        @Override public boolean matches(Field c) { return c instanceof Obj o && o.type().equals(type); }
        @Override public boolean isConcrete() { return false; }
        @Override public String toString() { return type; }
    }

    /** {@code null} — matches any object of any type. */
    record AnyObj() implements Field {
        @Override public boolean matches(Field c) { return c instanceof Obj; }
        @Override public boolean isConcrete() { return false; }
        @Override public String toString() { return "null"; }
    }
}
