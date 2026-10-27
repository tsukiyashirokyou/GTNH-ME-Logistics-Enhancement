package com.kyou.metransferdrive.transfer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Supplier;

/** Rejects synchronous feedback into the originating drive while letting AE2 route to other storage. */
public final class TransferInjectionGuard {

    private static final ThreadLocal<Deque<Object>> SOURCES = new ThreadLocal<>();

    private TransferInjectionGuard() {}

    public static <T> T call(final Object source, final Supplier<T> action) {
        Objects.requireNonNull(source, "source");
        Deque<Object> sources = SOURCES.get();
        if (sources == null) {
            sources = new ArrayDeque<>();
            SOURCES.set(sources);
        }
        sources.push(source);
        try {
            return action.get();
        } finally {
            sources.pop();
            if (sources.isEmpty()) {
                SOURCES.remove();
            }
        }
    }

    public static boolean blocks(final Object drive) {
        final Deque<Object> sources = SOURCES.get();
        if (sources != null) {
            for (final Object source : sources) {
                if (source == drive) {
                    return true;
                }
            }
        }
        return false;
    }
}
