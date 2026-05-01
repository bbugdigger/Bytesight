package com.bugdigger.agent.debugger.condition;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates a small expression language against a frame's {@code this} fields
 * and method arguments. Used by the breakpoint-hit pipeline to gate whether
 * a hit should actually suspend the thread.
 *
 * <h3>Grammar</h3>
 * <pre>
 *   expr    := orExpr
 *   orExpr  := andExpr ('||' andExpr)*
 *   andExpr := notExpr ('&&' notExpr)*
 *   notExpr := '!' notExpr | cmpExpr
 *   cmpExpr := addExpr (('==' | '!=' | '&lt;=' | '&gt;=' | '&lt;' | '&gt;') addExpr)?
 *   addExpr := mulExpr (('+' | '-') mulExpr)*
 *   mulExpr := primary (('*' | '/' | '%') primary)*
 *   primary := literal | ident ('.' ident)? | '(' expr ')' | '!' primary
 *   literal := int | long ('L'|'l') | double | string | 'true' | 'false' | 'null'
 * </pre>
 *
 * <h3>Identifier resolution</h3>
 * Method argument names are resolved first, then {@code this.field}. Bare field
 * names also resolve against {@code this}. {@code this.foo} = field {@code foo}
 * on the receiver. Object navigation beyond one hop is not supported.
 *
 * <h3>Fail-open semantics</h3>
 * Parse or eval errors return {@link Result#error(String)} which the caller
 * treats as "suspend anyway" so the user is never surprised by a never-firing
 * bp. The error message is surfaced to the UI.
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {}

    public static Result evaluate(String expr, Context ctx) {
        if (expr == null || expr.isBlank()) return Result.unconditional();
        try {
            List<Token> tokens = new Lexer(expr).tokenize();
            Expression tree = new Parser(tokens).parse();
            Object value = tree.eval(ctx);
            return Result.of(toBool(value));
        } catch (ParseError | EvalError e) {
            return Result.error(e.getMessage());
        }
    }

    // ===== Context ============================================================

    /**
     * Resolves identifiers against a frame's local args + receiver fields.
     * Built from the data BreakpointInterceptor already has at hit time.
     */
    public static final class Context {
        private final Object self;            // null for static methods + line bps without entry stash
        private final Object[] arguments;     // null when not available (line bp without entry stash)
        private final Method method;          // for arg name lookup; null if unavailable

        public Context(Object self, Object[] arguments, Method method) {
            this.self = self;
            this.arguments = arguments;
            this.method = method;
        }

        Object lookup(String name) {
            if ("this".equals(name)) return self;  // bare `this` = receiver object
            // 1. method args by name (requires -parameters or attempts arg0..argN fallback)
            if (method != null && arguments != null) {
                Parameter[] params = method.getParameters();
                for (int i = 0; i < params.length && i < arguments.length; i++) {
                    if (params[i].isNamePresent() ? params[i].getName().equals(name)
                            : ("arg" + i).equals(name)) {
                        return arguments[i];
                    }
                }
            }
            // 2. this.field (bare field reference)
            if (self != null) {
                Object v = readField(self, name);
                if (v != UNDEFINED) return v;
            }
            return UNDEFINED;
        }

        Object lookupMember(Object receiver, String name) {
            if (receiver == null) throw new EvalError("null receiver for ." + name);
            if (receiver == UNDEFINED) return UNDEFINED;
            return readField(receiver, name);
        }

        private static Object readField(Object obj, String name) {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                try {
                    Field f = c.getDeclaredField(name);
                    if (Modifier.isStatic(f.getModifiers())) { c = c.getSuperclass(); continue; }
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException ignore) {
                    c = c.getSuperclass();
                } catch (IllegalAccessException e) {
                    return UNDEFINED;
                }
            }
            return UNDEFINED;
        }
    }

    /** Sentinel for "name does not resolve in this context". */
    static final Object UNDEFINED = new Object() {
        @Override public String toString() { return "<undefined>"; }
    };

    // ===== Result =============================================================

    public static final class Result {
        private final boolean conditional;
        private final boolean shouldFire;
        private final String error;

        private Result(boolean conditional, boolean shouldFire, String error) {
            this.conditional = conditional;
            this.shouldFire = shouldFire;
            this.error = error;
        }

        public static Result unconditional() { return new Result(false, true, null); }
        public static Result of(boolean fire)  { return new Result(true, fire, null); }
        /** Fail-open: the bp still suspends so the user notices the broken expression. */
        public static Result error(String msg) { return new Result(true, true, msg); }

        public boolean isConditional() { return conditional; }
        public boolean shouldFire()    { return shouldFire; }
        public boolean hasError()      { return error != null; }
        public String error()          { return error; }
    }

    // ===== Lexer ==============================================================

    enum TokType {
        INT, LONG, DOUBLE, STRING, IDENT,
        TRUE, FALSE, NULL,
        EQ, NE, LE, GE, LT, GT,
        AND, OR, NOT,
        PLUS, MINUS, STAR, SLASH, PERCENT,
        LPAREN, RPAREN, DOT,
        EOF
    }

    static final class Token {
        final TokType type;
        final String text;
        final Object literal;
        Token(TokType type, String text, Object literal) {
            this.type = type;
            this.text = text;
            this.literal = literal;
        }
        @Override public String toString() { return type + "(" + text + ")"; }
    }

    static final class Lexer {
        private final String src;
        private int p;
        Lexer(String src) { this.src = src; this.p = 0; }

        List<Token> tokenize() {
            List<Token> out = new ArrayList<>();
            while (p < src.length()) {
                char c = src.charAt(p);
                if (Character.isWhitespace(c)) { p++; continue; }
                if (Character.isDigit(c)) { out.add(number()); continue; }
                if (c == '"') { out.add(string()); continue; }
                if (Character.isJavaIdentifierStart(c)) { out.add(ident()); continue; }
                Token sym = symbol();
                if (sym != null) { out.add(sym); continue; }
                throw new ParseError("Unexpected character '" + c + "' at position " + p);
            }
            out.add(new Token(TokType.EOF, "", null));
            return out;
        }

        private Token number() {
            int start = p;
            boolean isDouble = false;
            while (p < src.length() && Character.isDigit(src.charAt(p))) p++;
            if (p < src.length() && src.charAt(p) == '.') {
                isDouble = true; p++;
                while (p < src.length() && Character.isDigit(src.charAt(p))) p++;
            }
            char suffix = p < src.length() ? src.charAt(p) : '\0';
            String txt = src.substring(start, p);
            if (suffix == 'L' || suffix == 'l') { p++; return new Token(TokType.LONG, txt + suffix, Long.parseLong(txt)); }
            if (suffix == 'D' || suffix == 'd') { p++; return new Token(TokType.DOUBLE, txt + suffix, Double.parseDouble(txt)); }
            if (isDouble) return new Token(TokType.DOUBLE, txt, Double.parseDouble(txt));
            return new Token(TokType.INT, txt, Integer.parseInt(txt));
        }

        private Token string() {
            int start = ++p;  // skip leading "
            StringBuilder sb = new StringBuilder();
            while (p < src.length() && src.charAt(p) != '"') {
                char c = src.charAt(p++);
                if (c == '\\' && p < src.length()) {
                    char esc = src.charAt(p++);
                    switch (esc) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case '\\': sb.append('\\'); break;
                        case '"': sb.append('"'); break;
                        default: sb.append(esc); break;
                    }
                } else {
                    sb.append(c);
                }
            }
            if (p >= src.length()) throw new ParseError("Unterminated string starting at " + start);
            p++;  // closing "
            return new Token(TokType.STRING, sb.toString(), sb.toString());
        }

        private Token ident() {
            int start = p;
            while (p < src.length() && Character.isJavaIdentifierPart(src.charAt(p))) p++;
            String txt = src.substring(start, p);
            switch (txt) {
                case "true":  return new Token(TokType.TRUE, txt, Boolean.TRUE);
                case "false": return new Token(TokType.FALSE, txt, Boolean.FALSE);
                case "null":  return new Token(TokType.NULL, txt, null);
                default:      return new Token(TokType.IDENT, txt, null);
            }
        }

        private Token symbol() {
            char c = src.charAt(p);
            char c2 = p + 1 < src.length() ? src.charAt(p + 1) : '\0';
            switch (c) {
                case '=': if (c2 == '=') { p += 2; return new Token(TokType.EQ, "==", null); } break;
                case '!': if (c2 == '=') { p += 2; return new Token(TokType.NE, "!=", null); }
                          p++; return new Token(TokType.NOT, "!", null);
                case '<': if (c2 == '=') { p += 2; return new Token(TokType.LE, "<=", null); }
                          p++; return new Token(TokType.LT, "<", null);
                case '>': if (c2 == '=') { p += 2; return new Token(TokType.GE, ">=", null); }
                          p++; return new Token(TokType.GT, ">", null);
                case '&': if (c2 == '&') { p += 2; return new Token(TokType.AND, "&&", null); } break;
                case '|': if (c2 == '|') { p += 2; return new Token(TokType.OR, "||", null); } break;
                case '+': p++; return new Token(TokType.PLUS, "+", null);
                case '-': p++; return new Token(TokType.MINUS, "-", null);
                case '*': p++; return new Token(TokType.STAR, "*", null);
                case '/': p++; return new Token(TokType.SLASH, "/", null);
                case '%': p++; return new Token(TokType.PERCENT, "%", null);
                case '(': p++; return new Token(TokType.LPAREN, "(", null);
                case ')': p++; return new Token(TokType.RPAREN, ")", null);
                case '.': p++; return new Token(TokType.DOT, ".", null);
            }
            return null;
        }
    }

    // ===== Parser =============================================================

    static final class Parser {
        private final List<Token> tokens;
        private int p;
        Parser(List<Token> tokens) { this.tokens = tokens; this.p = 0; }

        Expression parse() {
            Expression e = parseOr();
            if (peek().type != TokType.EOF) throw new ParseError("Unexpected trailing token: " + peek());
            return e;
        }

        private Expression parseOr() {
            Expression left = parseAnd();
            while (peek().type == TokType.OR) { p++; left = new Binary(left, TokType.OR, parseAnd()); }
            return left;
        }

        private Expression parseAnd() {
            Expression left = parseNot();
            while (peek().type == TokType.AND) { p++; left = new Binary(left, TokType.AND, parseNot()); }
            return left;
        }

        private Expression parseNot() {
            if (peek().type == TokType.NOT) { p++; return new Unary(TokType.NOT, parseNot()); }
            return parseCmp();
        }

        private Expression parseCmp() {
            Expression left = parseAdd();
            TokType t = peek().type;
            if (t == TokType.EQ || t == TokType.NE || t == TokType.LT
                    || t == TokType.GT || t == TokType.LE || t == TokType.GE) {
                p++;
                return new Binary(left, t, parseAdd());
            }
            return left;
        }

        private Expression parseAdd() {
            Expression left = parseMul();
            while (peek().type == TokType.PLUS || peek().type == TokType.MINUS) {
                TokType op = peek().type; p++;
                left = new Binary(left, op, parseMul());
            }
            return left;
        }

        private Expression parseMul() {
            Expression left = parsePrimary();
            while (peek().type == TokType.STAR || peek().type == TokType.SLASH || peek().type == TokType.PERCENT) {
                TokType op = peek().type; p++;
                left = new Binary(left, op, parsePrimary());
            }
            return left;
        }

        private Expression parsePrimary() {
            Token tok = peek();
            switch (tok.type) {
                case MINUS: { p++; return new Unary(TokType.MINUS, parsePrimary()); }
                case INT: case LONG: case DOUBLE: case STRING:
                case TRUE: case FALSE: case NULL:
                    p++; return new Lit(tok.literal);
                case IDENT: {
                    p++;
                    if (peek().type == TokType.DOT) {
                        p++;
                        if (peek().type != TokType.IDENT)
                            throw new ParseError("Expected identifier after '.', got " + peek());
                        Token field = peek(); p++;
                        return new Member(tok.text, field.text);
                    }
                    return new Ident(tok.text);
                }
                case LPAREN: {
                    p++;
                    Expression e = parseOr();
                    if (peek().type != TokType.RPAREN) throw new ParseError("Expected ')', got " + peek());
                    p++;
                    return e;
                }
                default: throw new ParseError("Unexpected token: " + tok);
            }
        }

        private Token peek() { return tokens.get(p); }
    }

    // ===== AST + eval =========================================================

    interface Expression {
        Object eval(Context ctx);
    }

    static final class Lit implements Expression {
        final Object value;
        Lit(Object value) { this.value = value; }
        @Override public Object eval(Context ctx) { return value; }
    }

    static final class Ident implements Expression {
        final String name;
        Ident(String name) { this.name = name; }
        @Override public Object eval(Context ctx) {
            Object v = ctx.lookup(name);
            if (v == UNDEFINED) throw new EvalError("Unknown identifier: " + name);
            return v;
        }
    }

    static final class Member implements Expression {
        final String objName;
        final String fieldName;
        Member(String o, String f) { this.objName = o; this.fieldName = f; }
        @Override public Object eval(Context ctx) {
            Object receiver = "this".equals(objName) ? ctx.lookup("this") : ctx.lookup(objName);
            Object v = ctx.lookupMember(receiver, fieldName);
            if (v == UNDEFINED) throw new EvalError("Unknown field: " + objName + "." + fieldName);
            return v;
        }
    }

    static final class Unary implements Expression {
        final TokType op;
        final Expression operand;
        Unary(TokType op, Expression operand) { this.op = op; this.operand = operand; }
        @Override public Object eval(Context ctx) {
            Object v = operand.eval(ctx);
            switch (op) {
                case NOT:   return !toBool(v);
                case MINUS: {
                    if (v instanceof Integer) return -((Integer) v);
                    if (v instanceof Long)    return -((Long) v);
                    if (v instanceof Double)  return -((Double) v);
                    if (v instanceof Float)   return -((Float) v);
                    throw new EvalError("Cannot negate non-numeric: " + v);
                }
                default: throw new EvalError("Bad unary op: " + op);
            }
        }
    }

    static final class Binary implements Expression {
        final Expression left;
        final TokType op;
        final Expression right;
        Binary(Expression l, TokType op, Expression r) { this.left = l; this.op = op; this.right = r; }
        @Override public Object eval(Context ctx) {
            // Short-circuit for && and || before evaluating right
            if (op == TokType.AND) {
                if (!toBool(left.eval(ctx))) return Boolean.FALSE;
                return toBool(right.eval(ctx));
            }
            if (op == TokType.OR) {
                if (toBool(left.eval(ctx))) return Boolean.TRUE;
                return toBool(right.eval(ctx));
            }
            Object l = left.eval(ctx);
            Object r = right.eval(ctx);
            switch (op) {
                case EQ: return objectsEqual(l, r);
                case NE: return !objectsEqual(l, r);
                case LT: return cmp(l, r) < 0;
                case GT: return cmp(l, r) > 0;
                case LE: return cmp(l, r) <= 0;
                case GE: return cmp(l, r) >= 0;
                case PLUS: {
                    if (l instanceof String || r instanceof String)
                        return String.valueOf(l) + String.valueOf(r);
                    return numericOp(l, r, '+');
                }
                case MINUS:   return numericOp(l, r, '-');
                case STAR:    return numericOp(l, r, '*');
                case SLASH:   return numericOp(l, r, '/');
                case PERCENT: return numericOp(l, r, '%');
                default: throw new EvalError("Bad binary op: " + op);
            }
        }
    }

    // ===== Coercion helpers ===================================================

    private static boolean toBool(Object o) {
        if (o instanceof Boolean) return (Boolean) o;
        if (o == null) return false;
        if (o == UNDEFINED) throw new EvalError("Undefined value used as boolean");
        if (o instanceof Number) return ((Number) o).doubleValue() != 0.0;
        throw new EvalError("Cannot coerce to boolean: " + o.getClass().getSimpleName());
    }

    private static boolean objectsEqual(Object l, Object r) {
        if (l == null || r == null) return l == r;
        if (l instanceof Number && r instanceof Number) {
            return ((Number) l).doubleValue() == ((Number) r).doubleValue();
        }
        if (l instanceof String && r instanceof String) return l.equals(r);
        // Fall back to reference identity so users can compare object refs.
        return l == r || Objects.equals(l, r);
    }

    private static int cmp(Object l, Object r) {
        if (l instanceof Number && r instanceof Number) {
            return Double.compare(((Number) l).doubleValue(), ((Number) r).doubleValue());
        }
        if (l instanceof String && r instanceof String) return ((String) l).compareTo((String) r);
        throw new EvalError("Cannot compare " + clsName(l) + " with " + clsName(r));
    }

    private static Object numericOp(Object l, Object r, char op) {
        if (!(l instanceof Number) || !(r instanceof Number))
            throw new EvalError("Numeric op '" + op + "' requires numbers, got "
                    + clsName(l) + " and " + clsName(r));
        double ld = ((Number) l).doubleValue();
        double rd = ((Number) r).doubleValue();
        boolean integral = (l instanceof Integer || l instanceof Long || l instanceof Short || l instanceof Byte)
                && (r instanceof Integer || r instanceof Long || r instanceof Short || r instanceof Byte);
        switch (op) {
            case '+': return integral ? (Object) (long) (ld + rd) : (Object) (ld + rd);
            case '-': return integral ? (Object) (long) (ld - rd) : (Object) (ld - rd);
            case '*': return integral ? (Object) (long) (ld * rd) : (Object) (ld * rd);
            case '/': {
                if (integral) {
                    if (rd == 0) throw new EvalError("Integer divide by zero");
                    return (long) (((long) ld) / ((long) rd));
                }
                return ld / rd;
            }
            case '%': {
                if (integral) {
                    if (rd == 0) throw new EvalError("Integer modulo by zero");
                    return ((long) ld) % ((long) rd);
                }
                return ld % rd;
            }
        }
        throw new EvalError("Bad numeric op: " + op);
    }

    private static String clsName(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    // ===== Errors =============================================================

    static final class ParseError extends RuntimeException {
        ParseError(String msg) { super(msg); }
    }

    static final class EvalError extends RuntimeException {
        EvalError(String msg) { super(msg); }
    }
}
