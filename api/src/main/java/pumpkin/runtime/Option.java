package pumpkin.runtime;

import java.util.Objects;

public final class Option<T> {
    private static final Option<?> NONE = new Option<>(false, null);
    private final boolean some;
    private final T value;

    private Option(boolean some, T value) {
        this.some = some;
        this.value = value;
    }

    public static <T> Option<T> some(T value) {
        return new Option<>(true, Objects.requireNonNull(value));
    }

    @SuppressWarnings("unchecked")
    public static <T> Option<T> none() {
        return (Option<T>) NONE;
    }

    public boolean isSome() {
        return some;
    }

    public T value() {
        if (!some) throw new IllegalStateException("Option is empty");
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Option<?> option && some == option.some && Objects.equals(value, option.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(some, value);
    }
}
