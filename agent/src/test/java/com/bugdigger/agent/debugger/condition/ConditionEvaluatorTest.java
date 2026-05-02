package com.bugdigger.agent.debugger.condition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the expression grammar, identifier resolution, type coercion, and the
 * fail-open behavior on parse / eval errors.
 */
class ConditionEvaluatorTest {

    private ConditionEvaluator.Context ctx(Object self, Object[] args) {
        return new ConditionEvaluator.Context(self, args, null);
    }

    @Test
    void emptyExpressionIsUnconditional() {
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate("", ctx(null, null));
        assertFalse(r.isConditional());
        assertTrue(r.shouldFire());
    }

    @Test
    void blankExpressionIsUnconditional() {
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate("   \t", ctx(null, null));
        assertFalse(r.isConditional());
        assertTrue(r.shouldFire());
    }

    @Test
    void integerComparisonsWork() {
        Holder h = new Holder(5, "alice");
        assertFires("i > 3", h, null);
        assertDoesNotFire("i > 10", h, null);
        assertFires("i == 5", h, null);
        assertFires("i != 6", h, null);
        assertFires("i >= 5", h, null);
        assertDoesNotFire("i > 5", h, null);
    }

    @Test
    void stringEqualityComparesByValue() {
        Holder h = new Holder(5, "alice");
        assertFires("name == \"alice\"", h, null);
        assertDoesNotFire("name == \"bob\"", h, null);
    }

    @Test
    void andShortCircuits() {
        Holder h = new Holder(5, "alice");
        // Right side would NPE/error if evaluated, but && short-circuits.
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate("i > 100 && unknown == 1", ctx(h, null));
        assertFalse(r.shouldFire());
        assertFalse(r.hasError());
    }

    @Test
    void orShortCircuits() {
        Holder h = new Holder(5, "alice");
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate("i == 5 || unknown == 1", ctx(h, null));
        assertTrue(r.shouldFire());
        assertFalse(r.hasError());
    }

    @Test
    void notNegates() {
        Holder h = new Holder(5, "alice");
        assertFires("!(i > 100)", h, null);
        assertDoesNotFire("!(i == 5)", h, null);
    }

    @Test
    void parensControlPrecedence() {
        Holder h = new Holder(5, "alice");
        // Without parens: 1 + 2 * 3 == 7
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate("1 + 2 * 3 == 7", ctx(h, null));
        assertTrue(r.shouldFire());
        // With parens: (1+2)*3 == 9
        ConditionEvaluator.Result r2 = ConditionEvaluator.evaluate("(1 + 2) * 3 == 9", ctx(h, null));
        assertTrue(r2.shouldFire());
    }

    @Test
    void thisDotFieldResolves() {
        Holder h = new Holder(5, "alice");
        assertFires("this.name == \"alice\"", h, null);
    }

    @Test
    void unknownIdentifierFailsOpen() {
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate("nonexistent > 5", ctx(null, null));
        assertTrue(r.shouldFire(), "fail-open: still suspends");
        assertTrue(r.hasError(), "error is reported");
        assertTrue(r.error().contains("nonexistent"), r.error());
    }

    @Test
    void parseErrorFailsOpen() {
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate("i >>> 5", ctx(null, null));
        assertTrue(r.shouldFire());
        assertTrue(r.hasError());
    }

    @Test
    void doubleArithmeticPromotes() {
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate("1.5 + 0.5 == 2.0", ctx(null, null));
        assertTrue(r.shouldFire());
    }

    @Test
    void nullEqualityWorks() {
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate("null == null", ctx(null, null));
        assertTrue(r.shouldFire());
        ConditionEvaluator.Result r2 = ConditionEvaluator.evaluate("null != null", ctx(null, null));
        assertFalse(r2.shouldFire());
    }

    @Test
    void integerDivideByZeroFailsOpen() {
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate("10 / 0 == 0", ctx(null, null));
        assertTrue(r.shouldFire());
        assertTrue(r.hasError());
        assertTrue(r.error().contains("zero"));
    }

    private void assertFires(String expr, Object self, Object[] args) {
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate(expr, ctx(self, args));
        assertTrue(r.shouldFire(), "Expected '" + expr + "' to fire; error=" + r.error());
        assertFalse(r.hasError(), "Expected '" + expr + "' to be error-free; got " + r.error());
    }

    private void assertDoesNotFire(String expr, Object self, Object[] args) {
        ConditionEvaluator.Result r = ConditionEvaluator.evaluate(expr, ctx(self, args));
        assertFalse(r.shouldFire(), "Expected '" + expr + "' NOT to fire");
        assertFalse(r.hasError(), "Expected no error; got " + r.error());
    }

    /** Simple POJO with a typed int and a String field. */
    private static final class Holder {
        @SuppressWarnings("unused") final int i;
        @SuppressWarnings("unused") final String name;
        Holder(int i, String name) { this.i = i; this.name = name; }
    }
}
