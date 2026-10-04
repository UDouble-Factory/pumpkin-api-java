package pumpkin.runtime;

import java.util.Objects;

public final class Result<T, E> {
    private final boolean failure;
    private final T value;
    private final E error;

    private Result(boolean failure, T value, E error) {
        this.failure = failure;
        this.value = value;
        this.error = error;
    }

    public static <T, E> Result<T, E> success(T value) {
        return new Result<>(false, value, null);
    }

    public static <T, E> Result<T, E> failure(E error) {
        return new Result<>(true, null, error);
    }

    public boolean isFailure() {
        return failure;
    }

    public T value() {
        if (failure) throw new IllegalStateException("Result is a failure: " + error);
        return value;
    }

    public E error() {
        if (!failure) throw new IllegalStateException("Result is a success");
        return error;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Result<?, ?> result && failure == result.failure && Objects.equals(value, result.value) && Objects.equals(error, result.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(failure, value, error);
    }
}
