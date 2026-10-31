package me.bytefox.livingcolonists.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import me.bytefox.livingcolonists.ModLog;

/**
 * The mod's own event bus (#40): what happens in the game and in colonies ({@link GameEvents}) goes
 * here once, and whatever reacts to it subscribes, instead of being called by hand from each place.
 * Handlers run at once, in the order they subscribed, on the thread that posts (the server thread).
 */
public final class ModEvents {
    /** The bus of the running mod. */
    public static final ModEvents BUS = new ModEvents((kind, e) -> ModLog.warn("A handler of {} failed", kind, e));

    private final Map<Class<?>, List<Consumer<Object>>> handlers = new HashMap<>();
    private final BiConsumer<String, RuntimeException> failed;

    /** @param failed told of a handler that threw: the event's kind and the error */
    public ModEvents(BiConsumer<String, RuntimeException> failed) {
        this.failed = failed;
    }

    @SuppressWarnings("unchecked")
    public <T> void on(Class<T> type, Consumer<? super T> handler) {
        handlers.computeIfAbsent(type, key -> new ArrayList<>()).add(event -> handler.accept((T) event));
    }

    /** Hands the event to every handler of its kind; one that throws is logged and the rest still run. */
    public void post(Object event) {
        for (Consumer<Object> handler : handlers.getOrDefault(event.getClass(), List.of())) {
            try {
                handler.accept(event);
            } catch (RuntimeException e) {
                failed.accept(event.getClass().getSimpleName(), e);
            }
        }
    }
}
