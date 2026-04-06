package com.bugdigger.sample.services;

import com.bugdigger.sample.util.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately convoluted data processor for testing CFG visualization,
 * bytecode inspection, and method tracing in Bytesight.
 */
public class DataProcessor {

    private final Logger logger;
    private int totalProcessed;

    public DataProcessor(Logger logger) {
        this.logger = logger;
        this.totalProcessed = 0;
    }

    /**
     * Processes input data with branching, loops, and exception handling.
     * Designed to produce an interesting control flow graph.
     */
    public String processData(String input, int iterations) {
        logger.debug("processData called: input='" + input + "', iterations=" + iterations);

        if (input == null || input.isEmpty()) {
            logger.warn("Empty input received, returning default");
            return "DEFAULT";
        }

        StringBuilder result = new StringBuilder();
        int score = 0;

        // For loop with nested if/else — creates multiple basic blocks
        for (int i = 0; i < iterations; i++) {
            char c = input.charAt(i % input.length());

            if (Character.isUpperCase(c)) {
                score += 3;
                result.append(Character.toLowerCase(c));
                logger.debug("Round " + i + ": uppercase '" + c + "', score=" + score);
            } else if (Character.isDigit(c)) {
                score += Character.getNumericValue(c);
                result.append('#');
                logger.debug("Round " + i + ": digit '" + c + "', score=" + score);
            } else {
                score += 1;
                result.append(c);
            }

            // Early exit condition — adds another branch
            if (score > 50) {
                logger.info("Score threshold exceeded at round " + i);
                break;
            }
        }

        // While loop with try/catch — exception edges in CFG
        int retries = 0;
        while (retries < 3) {
            try {
                String transformed = transform(result.toString(), score);
                logger.debug("Transform succeeded: " + transformed);
                result = new StringBuilder(transformed);
                break;
            } catch (Exception e) {
                retries++;
                logger.warn("Transform failed (attempt " + retries + "): " + e.getMessage());
                if (retries >= 3) {
                    logger.error("All retries exhausted");
                    return "ERROR:" + e.getMessage();
                }
            }
        }

        // Switch statement — creates multiple case edges
        String prefix;
        switch (score % 4) {
            case 0:
                prefix = "[ZERO]";
                break;
            case 1:
                prefix = "[LOW]";
                break;
            case 2:
                prefix = "[MID]";
                break;
            default:
                prefix = "[HIGH]";
                break;
        }

        totalProcessed++;
        String finalResult = prefix + " " + result + " (score=" + score + ", run=#" + totalProcessed + ")";
        logger.info("processData result: " + finalResult);
        return finalResult;
    }

    /**
     * Helper method that sometimes throws — produces exception edges when called
     * from within a try-catch in the caller's CFG.
     */
    private String transform(String value, int score) {
        if (score < 0) {
            throw new IllegalArgumentException("Negative score: " + score);
        }

        List<Character> chars = new ArrayList<>();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (score % 2 == 0) {
                chars.add(Character.toUpperCase(c));
            } else {
                chars.add(c);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (char c : chars) {
            sb.append(c);
        }
        return sb.toString();
    }

    public int getTotalProcessed() {
        return totalProcessed;
    }
}
