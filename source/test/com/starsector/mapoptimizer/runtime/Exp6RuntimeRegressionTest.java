package com.starsector.mapoptimizer.runtime;

import com.starsector.mapoptimizer.agent.OptimizerConfig;

import java.lang.management.ManagementFactory;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Runtime semantics and reachability checks for the allocation patches introduced in exp6. */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class Exp6RuntimeRegressionTest {
    private Exp6RuntimeRegressionTest() {}

    public static void main(String[] args) throws Exception {
        rejectDisabledExplicitGc();
        OptimizerConfig enabled = config(true);
        installConfig(enabled);
        try {
            assertSnapshotIsolationAndReuse();
            assertSnapshotReentrancy();
            assertSnapshotFallback();
            installConfig(enabled);
            assertMemoryIteratorMatrix();
            assertSnapshotReachability();
            assertExceptionalSnapshotReachability();
            assertEmptyIteratorReachability();
        } finally {
            drainScratchScopes();
            installConfig(null);
        }

        System.out.println("OK exp6-runtime snapshot-isolation snapshot-reuse snapshot-reentrancy"
                + " snapshot-fallback snapshot-no-retention empty-snapshot-singleton"
                + " exceptional-snapshot-no-retention"
                + " memory-iterator-empty-singleton memory-iterator-order"
                + " memory-iterator-remove memory-iterator-no-retention");
    }

    private static void assertSnapshotIsolationAndReuse() {
        Object firstValue = new Object();
        Object secondValue = new Object();
        ArrayList<Object> source = new ArrayList<>(List.of(firstValue, secondValue));
        List<?> first;
        List<?> second;
        List<?> entityScripts;

        MapOptimizerHooks.beginScratchScope();
        try {
            List<?> emptyScripts = MapOptimizerHooks.borrowEntityScriptSnapshot(List.of());
            require(emptyScripts == Collections.emptyList(),
                    "empty entity-script snapshot did not return Collections.emptyList()");
            require(emptyScripts == MapOptimizerHooks.borrowEntityScriptSnapshot(List.of()),
                    "empty entity-script snapshots did not share the allocation-free singleton");
            assertReadOnly(emptyScripts, "empty entity-script snapshot");

            first = MapOptimizerHooks.borrowLocationAdvanceSnapshot(source);
            require(first != source && first.equals(List.of(firstValue, secondValue)),
                    "location snapshot did not capture the source contents");
            assertReadOnly(first, "location snapshot");

            source.clear();
            source.add(secondValue);
            require(first.equals(List.of(firstValue, secondValue)),
                    "source mutation changed an already-borrowed location snapshot");

            second = MapOptimizerHooks.borrowPausedLocationSnapshot(source);
            entityScripts = MapOptimizerHooks.borrowEntityScriptSnapshot(source);
            require(first != second && first != entityScripts && second != entityScripts,
                    "multiple live snapshots in one scope aliased each other");
            require(second.equals(List.of(secondValue)) && entityScripts.equals(List.of(secondValue)),
                    "later snapshots did not preserve their own point-in-time contents");

            source.set(0, firstValue);
            require(second.equals(List.of(secondValue)) && entityScripts.equals(List.of(secondValue)),
                    "source replacement leaked into a borrowed snapshot");
        } finally {
            MapOptimizerHooks.endScratchScope();
        }

        require(first.isEmpty() && second.isEmpty() && entityScripts.isEmpty(),
                "ending a scratch scope did not clear every live snapshot");

        MapOptimizerHooks.beginScratchScope();
        try {
            List<?> reused = MapOptimizerHooks.borrowLocationAdvanceSnapshot(List.of(secondValue));
            require(reused == first, "first snapshot slot was not reused by the next scope");
            require(reused.equals(List.of(secondValue)), "reused snapshot contains stale values");
        } finally {
            MapOptimizerHooks.endScratchScope();
        }
    }

    private static void assertSnapshotReentrancy() {
        Object outerValue = new Object();
        Object innerValue = new Object();

        MapOptimizerHooks.beginScratchScope();
        try {
            List<?> outer = MapOptimizerHooks.borrowLocationAdvanceSnapshot(List.of(outerValue));
            List<?> firstInner;
            MapOptimizerHooks.beginScratchScope();
            try {
                firstInner = MapOptimizerHooks.borrowEntityScriptSnapshot(List.of(innerValue));
                require(firstInner != outer, "nested scratch scope reused an active outer snapshot");
                require(firstInner.equals(List.of(innerValue)), "nested snapshot contents changed");
            } finally {
                MapOptimizerHooks.endScratchScope();
            }
            require(firstInner.isEmpty(), "nested scratch frame was not cleared on exit");
            require(outer.equals(List.of(outerValue)), "nested scope cleared its active outer frame");

            MapOptimizerHooks.beginScratchScope();
            try {
                List<?> reusedInner = MapOptimizerHooks.borrowEntityScriptSnapshot(List.of(innerValue));
                require(reusedInner == firstInner, "nested snapshot frame was not reused");
            } finally {
                MapOptimizerHooks.endScratchScope();
            }
            require(outer.equals(List.of(outerValue)), "reused nested frame corrupted outer state");
        } finally {
            MapOptimizerHooks.endScratchScope();
        }
    }

    private static void assertSnapshotFallback() throws Exception {
        installConfig(config(false));
        ArrayList<Object> source = new ArrayList<>(List.of(new Object()));
        List<?> first = MapOptimizerHooks.borrowLocationAdvanceSnapshot(source);
        List<?> second = MapOptimizerHooks.borrowLocationAdvanceSnapshot(source);
        List<?> scripts = MapOptimizerHooks.borrowEntityScriptSnapshot(source);
        require(first instanceof ArrayList<?> && second instanceof ArrayList<?>
                        && scripts instanceof ArrayList<?>,
                "disabled snapshot reuse did not preserve vanilla ArrayList snapshots");
        require(first != second && first != scripts && second != scripts,
                "disabled snapshot reuse unexpectedly pooled fallback lists");
        source.clear();
        require(first.size() == 1 && second.size() == 1 && scripts.size() == 1,
                "disabled snapshot fallback was not isolated from its source");
    }

    private static void assertMemoryIteratorMatrix() {
        Iterator<?> canonicalEmpty = Collections.emptyIterator();
        Iterator<?> expireEmpty = MapOptimizerHooks.memoryExpireIterator(new ArrayList<>());
        Iterator<?> requireEmpty = MapOptimizerHooks.memoryRequireIterator(new ArrayList<>());
        require(expireEmpty == canonicalEmpty && requireEmpty == canonicalEmpty,
                "empty memory collections did not use the shared empty iterator");
        require(!expireEmpty.hasNext() && !requireEmpty.hasNext(),
                "empty memory iterator unexpectedly contained an element");

        ArrayList<String> expire = new ArrayList<>(List.of("first", "second"));
        Iterator expireIterator = MapOptimizerHooks.memoryExpireIterator(expire);
        require(expireIterator.next().equals("first"), "expire iterator changed source order");
        expireIterator.remove();
        require(expire.equals(List.of("second")), "expire iterator did not delegate remove()");
        require(expireIterator.next().equals("second") && !expireIterator.hasNext(),
                "expire iterator did not visit every remaining source value");

        LinkedHashMap<String, String> requireMap = new LinkedHashMap<>();
        requireMap.put("one", "first");
        requireMap.put("two", "second");
        Collection<String> values = requireMap.values();
        Iterator requireIterator = MapOptimizerHooks.memoryRequireIterator(values);
        require(requireIterator.next().equals("first"), "require iterator changed values order");
        requireIterator.remove();
        require(!requireMap.containsKey("one") && requireMap.size() == 1,
                "require iterator did not preserve backing-map remove semantics");
        require(requireIterator.next().equals("second") && !requireIterator.hasNext(),
                "require iterator did not visit every remaining value");
    }

    private static void assertSnapshotReachability() throws Exception {
        ReferenceQueue<Object> valueQueue = new ReferenceQueue<>();
        ReferenceQueue<Collection<?>> sourceQueue = new ReferenceQueue<>();
        SnapshotProbe probe = populateSnapshots(valueQueue, sourceQueue);
        awaitCollected(probe.value(), valueQueue,
                "scratch snapshot retained a campaign value after scope exit");
        awaitCollected(probe.source(), sourceQueue,
                "scratch snapshot retained its source collection after scope exit");
    }

    private static SnapshotProbe populateSnapshots(
            ReferenceQueue<Object> valueQueue,
            ReferenceQueue<Collection<?>> sourceQueue) {
        Object value = new Object();
        ArrayList<Object> source = new ArrayList<>(List.of(value));
        WeakReference<Object> valueReference = new WeakReference<>(value, valueQueue);
        WeakReference<Collection<?>> sourceReference = new WeakReference<>(source, sourceQueue);

        MapOptimizerHooks.beginScratchScope();
        try {
            require(MapOptimizerHooks.borrowLocationAdvanceSnapshot(source).get(0) == value,
                    "location snapshot lost its source value");
            require(MapOptimizerHooks.borrowPausedLocationSnapshot(source).get(0) == value,
                    "paused snapshot lost its source value");
            require(MapOptimizerHooks.borrowEntityScriptSnapshot(source).get(0) == value,
                    "entity-script snapshot lost its source value");
        } finally {
            MapOptimizerHooks.endScratchScope();
        }
        source.clear();
        return new SnapshotProbe(valueReference, sourceReference);
    }

    private static void assertExceptionalSnapshotReachability() throws Exception {
        // Ensure slot zero owns a reusable array before exercising the supplied-array
        // failure path; an empty first use would give the custom collection a zero-length array.
        MapOptimizerHooks.beginScratchScope();
        try {
            MapOptimizerHooks.borrowLocationAdvanceSnapshot(
                    List.of(new Object(), new Object(), new Object()));
        } finally {
            MapOptimizerHooks.endScratchScope();
        }

        ReferenceQueue<Object> queue = new ReferenceQueue<>();
        WeakReference<Object> reference = exceptionalSnapshot(queue);
        awaitCollected(reference, queue,
                "partially populated snapshot array retained a value after copy failure");
    }

    private static WeakReference<Object> exceptionalSnapshot(ReferenceQueue<Object> queue) {
        Object value = new Object();
        WeakReference<Object> reference = new WeakReference<>(value, queue);
        Collection<Object> source = new PartiallyWritingCollection(value);

        MapOptimizerHooks.beginScratchScope();
        try {
            MapOptimizerHooks.borrowLocationAdvanceSnapshot(source);
            throw new AssertionError("partially writing collection did not throw");
        } catch (SnapshotCopyFailure expected) {
            // The hook must propagate the original failure after clearing its backing array.
        } finally {
            MapOptimizerHooks.endScratchScope();
        }
        return reference;
    }

    private static void assertEmptyIteratorReachability() throws Exception {
        ReferenceQueue<Collection<?>> expireQueue = new ReferenceQueue<>();
        EmptyIteratorProbe expire = emptyExpireIterator(expireQueue);
        awaitCollected(expire.source(), expireQueue,
                "empty expire iterator retained its source collection");
        require(!expire.iterator().hasNext(), "collected expire source changed the empty iterator");

        ReferenceQueue<Collection<?>> requireQueue = new ReferenceQueue<>();
        EmptyIteratorProbe require = emptyRequireIterator(requireQueue);
        awaitCollected(require.source(), requireQueue,
                "empty require iterator retained its source collection");
        require(!require.iterator().hasNext(), "collected require source changed the empty iterator");
    }

    private static EmptyIteratorProbe emptyExpireIterator(ReferenceQueue<Collection<?>> queue) {
        ArrayList<Object> source = new ArrayList<>();
        WeakReference<Collection<?>> reference = new WeakReference<>(source, queue);
        return new EmptyIteratorProbe(reference, MapOptimizerHooks.memoryExpireIterator(source));
    }

    private static EmptyIteratorProbe emptyRequireIterator(ReferenceQueue<Collection<?>> queue) {
        ArrayList<Object> source = new ArrayList<>();
        WeakReference<Collection<?>> reference = new WeakReference<>(source, queue);
        return new EmptyIteratorProbe(reference, MapOptimizerHooks.memoryRequireIterator(source));
    }

    private static void assertReadOnly(List<?> snapshot, String label) {
        try {
            List raw = snapshot;
            raw.add(new Object());
            throw new AssertionError(label + " accepted mutation");
        } catch (UnsupportedOperationException expected) {
            // Required: pooled lists must never be changed by transformed vanilla loops.
        }
    }

    private static OptimizerConfig config(boolean snapshotReuse) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("patch.campaignSnapshotReuse", Boolean.toString(snapshotReuse));
        properties.setProperty("patch.entityScriptSnapshotReuse", Boolean.toString(snapshotReuse));
        Constructor<OptimizerConfig> constructor =
                OptimizerConfig.class.getDeclaredConstructor(Properties.class);
        constructor.setAccessible(true);
        OptimizerConfig config = constructor.newInstance(properties);
        require(config.campaignSnapshotReuse == snapshotReuse
                        && config.entityScriptSnapshotReuse == snapshotReuse,
                "test configuration did not select the requested snapshot mode");
        return config;
    }

    private static void installConfig(OptimizerConfig config) throws Exception {
        Field field = MapOptimizerHooks.class.getDeclaredField("config");
        field.setAccessible(true);
        field.set(null, config);
    }

    private static void drainScratchScopes() {
        for (int i = 0; i < 8; i++) MapOptimizerHooks.endScratchScope();
    }

    private static void awaitCollected(WeakReference<?> reference, ReferenceQueue<?> queue,
                                       String message) throws Exception {
        boolean enqueued = false;
        for (int attempt = 0; attempt < 80; attempt++) {
            if (queue.poll() == reference) {
                enqueued = true;
                break;
            }
            System.gc();
            System.runFinalization();
            byte[][] pressure = new byte[8][];
            for (int i = 0; i < pressure.length; i++) pressure[i] = new byte[128 * 1024];
            Thread.sleep(10L);
        }
        if (!enqueued) enqueued = queue.poll() == reference;
        require(reference.get() == null && enqueued, message);
    }

    private static void rejectDisabledExplicitGc() {
        boolean disabled = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch("-XX:+DisableExplicitGC"::equals);
        require(!disabled, "exp6 GC regression cannot run with -XX:+DisableExplicitGC");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record SnapshotProbe(WeakReference<Object> value,
                                 WeakReference<Collection<?>> source) {}

    private record EmptyIteratorProbe(WeakReference<Collection<?>> source,
                                      Iterator<?> iterator) {}

    private static final class PartiallyWritingCollection extends AbstractCollection<Object> {
        private final Object value;

        private PartiallyWritingCollection(Object value) {
            this.value = value;
        }

        @Override
        public Iterator<Object> iterator() {
            return Collections.singleton(value).iterator();
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public <T> T[] toArray(T[] supplied) {
            require(supplied.length > 0,
                    "snapshot did not supply its warmed reusable backing array");
            supplied[0] = (T) value;
            throw new SnapshotCopyFailure();
        }
    }

    private static final class SnapshotCopyFailure extends RuntimeException {}
}
