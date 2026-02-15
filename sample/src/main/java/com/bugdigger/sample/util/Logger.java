package com.bugdigger.sample.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple logger utility class.
 */
public class Logger {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final String name;
    private LogLevel minLevel;

    public Logger(String name) {
        this.name = name;
        this.minLevel = LogLevel.DEBUG;
    }

    public void setMinLevel(LogLevel level) {
        this.minLevel = level;
    }

    public void debug(String message) {
        log(LogLevel.DEBUG, message);
    }

    public void info(String message) {
        log(LogLevel.INFO, message);
    }

    public void warn(String message) {
        log(LogLevel.WARN, message);
    }

    public void error(String message) {
        log(LogLevel.ERROR, message);
    }

    public void error(String message, Throwable throwable) {
        log(LogLevel.ERROR, message + " - " + throwable.getMessage());
        if (minLevel.ordinal() <= LogLevel.DEBUG.ordinal()) {
            throwable.printStackTrace();
        }
    }

    private void log(LogLevel level, String message) {
        if (level.ordinal() >= minLevel.ordinal()) {
            String timestamp = LocalDateTime.now().format(FORMATTER);
            String threadName = Thread.currentThread().getName();
            System.out.printf("[%s] [%s] [%s] %s: %s%n",
                timestamp, level, threadName, name, message);
        }
    }

    /**
     * Log level enum with ANSI color support.
     */
    public enum LogLevel {
        DEBUG("DEBUG"),
        INFO("INFO"),
        WARN("WARN"),
        ERROR("ERROR");

        private final String display;

        LogLevel(String display) {
            this.display = display;
        }

        @Override
        public String toString() {
            return String.format("%-5s", display);
        }
    }
}
