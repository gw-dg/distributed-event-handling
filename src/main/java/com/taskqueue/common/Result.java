package com.taskqueue.common;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Represents the outcome of an operation.
 *
 * <p>
 * A Result is either:
 * <ul>
 * <li>Success containing a value</li>
 * <li>Failure containing an error message and retryability information</li>
 * </ul>
 *
 * <p>
 * This replaces:
 *
 * <ul>
 * <li>null returns</li>
 * <li>boolean success flags</li>
 * <li>control-flow exceptions</li>
 * </ul>
 *
 * @param <T> type of successful value
 */
public sealed interface Result<T>
        permits Result.Success, Result.Failure {

    /**
     * Successful result.
     */
    record Success<T>(T value) implements Result<T> {
    }

    /**
     * Failed result.
     *
     * retryable = true means Worker may retry according
     * to RetryPolicy.
     */
    record Failure<T>(String error,
            boolean retryable)
            implements Result<T> {

        public Failure {
            if (error == null || error.isBlank()) {
                throw new IllegalArgumentException(
                        "error message cannot be blank");
            }
        }
    }

    // ==========================================================
    // Factory Methods
    // ==========================================================

    static <T> Result<T> ok(T value) {
        return new Success<>(value);
    }

    static <T> Result<T> fail(String error) {
        return new Failure<>(error, false);
    }

    static <T> Result<T> retryable(String error) {
        return new Failure<>(error, true);
    }

    // ==========================================================
    // Queries
    // ==========================================================

    default boolean isSuccess() {
        return this instanceof Success<T>;
    }

    default boolean isFailure() {
        return this instanceof Failure<T>;
    }

    default boolean isRetryable() {
        return this instanceof Failure<T> f
                && f.retryable();
    }

    // ==========================================================
    // Extraction
    // ==========================================================

    default T orElse(T fallback) {
        return switch (this) {
            case Success<T> s -> s.value();
            case Failure<T> ignored -> fallback;
        };
    }

    default T orElseGet(
            Supplier<? extends T> supplier) {

        return switch (this) {
            case Success<T> s -> s.value();
            case Failure<T> ignored -> supplier.get();
        };
    }

    default T orElseThrow() {

        return switch (this) {

            case Success<T> s -> s.value();

            case Failure<T> f ->
                throw new NoSuchElementException(
                        f.error());
        };
    }

    default Optional<T> toOptional() {

        return switch (this) {

            case Success<T> s ->
                Optional.ofNullable(s.value());

            case Failure<T> ignored ->
                Optional.empty();
        };
    }

    // ==========================================================
    // Functional Operations
    // ==========================================================

    /**
     * Transform success value.
     */
    default <R> Result<R> map(
            Function<? super T, ? extends R> mapper) {

        return switch (this) {

            case Success<T> s ->
                Result.ok(
                        mapper.apply(
                                s.value()));

            case Failure<T> f ->
                new Failure<>(
                        f.error(),
                        f.retryable());
        };
    }

    /**
     * Chain another Result-producing operation.
     */
    default <R> Result<R> flatMap(
            Function<? super T, ? extends Result<R>> mapper) {

        return switch (this) {

            case Success<T> s ->
                mapper.apply(s.value());

            case Failure<T> f ->
                new Failure<>(
                        f.error(),
                        f.retryable());
        };
    }

    /**
     * Recover a failed result.
     */
    default Result<T> recover(
            Function<String, ? extends T> recovery) {

        return switch (this) {

            case Success<T> s ->
                s;

            case Failure<T> f ->
                Result.ok(
                        recovery.apply(
                                f.error()));
        };
    }

    // ==========================================================
    // Side Effects
    // ==========================================================

    default Result<T> ifSuccess(
            Consumer<? super T> consumer) {

        if (this instanceof Success<T> s) {
            consumer.accept(s.value());
        }

        return this;
    }

    default Result<T> ifFailure(
            Consumer<String> consumer) {

        if (this instanceof Failure<T> f) {
            consumer.accept(f.error());
        }

        return this;
    }

    // ==========================================================
    // Batch Utilities
    // ==========================================================

    /**
     * Converts:
     *
     * List&lt;Result&lt;T&gt;&gt;
     *
     * into
     *
     * Result&lt;List&lt;T&gt;&gt;
     */
    static <T> Result<List<T>> sequence(
            List<Result<T>> results) {

        List<T> values = new ArrayList<>(results.size());

        for (Result<T> result : results) {

            switch (result) {

                case Success<T> s ->
                    values.add(s.value());

                case Failure<T> f -> {

                    if (f.retryable()) {
                        return Result.retryable(
                                f.error());
                    }

                    return Result.fail(
                            f.error());
                }
            }
        }

        return Result.ok(values);
    }

    // ==========================================================
    // Convenience
    // ==========================================================

    default String errorMessage() {

        return switch (this) {

            case Success<T> ignored ->
                "";

            case Failure<T> f ->
                f.error();
        };
    }

    default String status() {

        return switch (this) {

            case Success<T> ignored ->
                "SUCCESS";

            case Failure<T> f ->
                f.retryable()
                        ? "RETRYABLE_FAILURE"
                        : "FAILURE";
        };
    }
}
