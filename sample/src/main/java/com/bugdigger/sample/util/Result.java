package com.bugdigger.sample.util;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A Result type for handling success/failure cases, similar to Kotlin's Result.
 * Demonstrates generics, sealed class patterns (via final classes), and lambdas.
 *
 * @param <T> The success value type
 */
public abstract sealed class Result<T> permits Result.Success, Result.Failure {

    /**
     * Creates a success result.
     */
    public static <T> Result<T> success(T value) {
        return new Success<>(value);
    }

    /**
     * Creates a failure result.
     */
    public static <T> Result<T> failure(Throwable error) {
        return new Failure<>(error);
    }

    /**
     * Executes a block and wraps the result.
     */
    public static <T> Result<T> runCatching(Supplier<T> block) {
        try {
            return success(block.get());
        } catch (Throwable t) {
            return failure(t);
        }
    }

    public abstract boolean isSuccess();

    public abstract boolean isFailure();

    public abstract T getOrNull();

    public abstract Throwable exceptionOrNull();

    public abstract T getOrThrow();

    public abstract T getOrDefault(T defaultValue);

    public abstract T getOrElse(Supplier<T> defaultSupplier);

    public abstract <R> Result<R> map(Function<T, R> transform);

    public abstract <R> Result<R> flatMap(Function<T, Result<R>> transform);

    public abstract Result<T> onSuccess(Consumer<T> action);

    public abstract Result<T> onFailure(Consumer<Throwable> action);

    public abstract Result<T> recover(Function<Throwable, T> recovery);

    /**
     * Success case of Result.
     */
    public static final class Success<T> extends Result<T> {
        private final T value;

        private Success(T value) {
            this.value = value;
        }

        public T getValue() {
            return value;
        }

        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public boolean isFailure() {
            return false;
        }

        @Override
        public T getOrNull() {
            return value;
        }

        @Override
        public Throwable exceptionOrNull() {
            return null;
        }

        @Override
        public T getOrThrow() {
            return value;
        }

        @Override
        public T getOrDefault(T defaultValue) {
            return value;
        }

        @Override
        public T getOrElse(Supplier<T> defaultSupplier) {
            return value;
        }

        @Override
        public <R> Result<R> map(Function<T, R> transform) {
            return success(transform.apply(value));
        }

        @Override
        public <R> Result<R> flatMap(Function<T, Result<R>> transform) {
            return transform.apply(value);
        }

        @Override
        public Result<T> onSuccess(Consumer<T> action) {
            action.accept(value);
            return this;
        }

        @Override
        public Result<T> onFailure(Consumer<Throwable> action) {
            return this;
        }

        @Override
        public Result<T> recover(Function<Throwable, T> recovery) {
            return this;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Success<?> success = (Success<?>) o;
            return Objects.equals(value, success.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value);
        }

        @Override
        public String toString() {
            return "Success(" + value + ")";
        }
    }

    /**
     * Failure case of Result.
     */
    public static final class Failure<T> extends Result<T> {
        private final Throwable error;

        private Failure(Throwable error) {
            this.error = Objects.requireNonNull(error);
        }

        public Throwable getError() {
            return error;
        }

        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public boolean isFailure() {
            return true;
        }

        @Override
        public T getOrNull() {
            return null;
        }

        @Override
        public Throwable exceptionOrNull() {
            return error;
        }

        @Override
        public T getOrThrow() {
            if (error instanceof RuntimeException) {
                throw (RuntimeException) error;
            }
            throw new RuntimeException(error);
        }

        @Override
        public T getOrDefault(T defaultValue) {
            return defaultValue;
        }

        @Override
        public T getOrElse(Supplier<T> defaultSupplier) {
            return defaultSupplier.get();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Result<R> map(Function<T, R> transform) {
            return (Result<R>) this;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Result<R> flatMap(Function<T, Result<R>> transform) {
            return (Result<R>) this;
        }

        @Override
        public Result<T> onSuccess(Consumer<T> action) {
            return this;
        }

        @Override
        public Result<T> onFailure(Consumer<Throwable> action) {
            action.accept(error);
            return this;
        }

        @Override
        public Result<T> recover(Function<Throwable, T> recovery) {
            return success(recovery.apply(error));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Failure<?> failure = (Failure<?>) o;
            return Objects.equals(error.getMessage(), failure.error.getMessage());
        }

        @Override
        public int hashCode() {
            return Objects.hash(error.getMessage());
        }

        @Override
        public String toString() {
            return "Failure(" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")";
        }
    }
}
