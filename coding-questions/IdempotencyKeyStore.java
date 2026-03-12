import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Stripe Interview Question #3: Idempotency Key Store
 *
 * Staff-level extensions:
 * - Fine-grained per-key locking (concurrent requests with different keys don't block)
 * - Request fingerprinting: detect misuse (same idempotency key, different request body)
 * - TTL-based automatic cleanup with background thread
 * - Pluggable storage backend (in-memory, or interface for Redis/DB)
 * - Metrics tracking (hit rate, collision detection)
 */
public class IdempotencyKeyStore {

    enum Status { IN_PROGRESS, COMPLETED, FAILED }

    static class StoredResult {
        final Status status;
        final String response;
        final String requestFingerprint; // hash of request params
        final long createdAt;
        final long completedAt;

        StoredResult(Status status, String response, String requestFingerprint,
                     long createdAt, long completedAt) {
            this.status = status;
            this.response = response;
            this.requestFingerprint = requestFingerprint;
            this.createdAt = createdAt;
            this.completedAt = completedAt;
        }
    }

    // =========================================================================
    // Pluggable storage interface
    // =========================================================================
    interface IdempotencyStore {
        StoredResult get(String key);
        void put(String key, StoredResult result);
        void remove(String key);
        int size();
    }

    // =========================================================================
    // In-memory implementation with TTL cleanup
    // =========================================================================
    static class InMemoryStore implements IdempotencyStore {
        private final ConcurrentHashMap<String, StoredResult> store = new ConcurrentHashMap<>();

        @Override
        public StoredResult get(String key) { return store.get(key); }

        @Override
        public void put(String key, StoredResult result) { store.put(key, result); }

        @Override
        public void remove(String key) { store.remove(key); }

        @Override
        public int size() { return store.size(); }

        void removeExpired(long ttlMs) {
            long now = System.currentTimeMillis();
            store.entrySet().removeIf(e ->
                    now - e.getValue().createdAt > ttlMs);
        }
    }

    // =========================================================================
    // Metrics
    // =========================================================================
    static class Metrics {
        private long totalRequests = 0;
        private long cacheHits = 0;
        private long cacheMisses = 0;
        private long fingerprintMismatches = 0;
        private long concurrentConflicts = 0;

        synchronized void recordHit() { totalRequests++; cacheHits++; }
        synchronized void recordMiss() { totalRequests++; cacheMisses++; }
        synchronized void recordFingerprintMismatch() { fingerprintMismatches++; }
        synchronized void recordConflict() { concurrentConflicts++; }

        double getHitRate() {
            return totalRequests == 0 ? 0 : (double) cacheHits / totalRequests;
        }

        @Override
        public String toString() {
            return String.format(
                    "total=%d, hits=%d, misses=%d, fpMismatches=%d, conflicts=%d, hitRate=%.2f%%",
                    totalRequests, cacheHits, cacheMisses,
                    fingerprintMismatches, concurrentConflicts,
                    getHitRate() * 100);
        }
    }

    // =========================================================================
    // Core implementation
    // =========================================================================

    private final InMemoryStore store;
    private final long ttlMs;
    private final Metrics metrics = new Metrics();
    private final ConcurrentHashMap<String, ReentrantLock> keyLocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    public IdempotencyKeyStore(long ttlMs, long cleanupIntervalMs) {
        this.store = new InMemoryStore();
        this.ttlMs = ttlMs;

        // Background cleanup thread
        this.cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "idempotency-cleanup");
            t.setDaemon(true);
            return t;
        });
        cleanupExecutor.scheduleAtFixedRate(
                () -> store.removeExpired(ttlMs),
                cleanupIntervalMs, cleanupIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Execute an operation idempotently.
     *
     * @param idempotencyKey    unique key from the client
     * @param requestFingerprint hash of the request body/params (for mismatch detection)
     * @param operation          the actual work to perform
     * @return the result (either fresh or replayed)
     * @throws IdempotencyException on fingerprint mismatch or concurrent conflict
     */
    public String execute(String idempotencyKey, String requestFingerprint,
                          Supplier<String> operation) {
        // Per-key lock: only blocks concurrent requests with THE SAME key
        ReentrantLock lock = keyLocks.computeIfAbsent(idempotencyKey,
                k -> new ReentrantLock());

        if (!lock.tryLock()) {
            // Another thread is processing this exact key right now
            metrics.recordConflict();
            throw new IdempotencyException(
                    "Concurrent request with same idempotency key: " + idempotencyKey);
        }

        try {
            return executeUnderLock(idempotencyKey, requestFingerprint, operation);
        } finally {
            lock.unlock();
        }
    }

    private String executeUnderLock(String idempotencyKey, String requestFingerprint,
                                     Supplier<String> operation) {
        StoredResult existing = store.get(idempotencyKey);

        if (existing != null) {
            // Check TTL
            if (System.currentTimeMillis() - existing.createdAt > ttlMs) {
                store.remove(idempotencyKey);
                // Fall through to execute
            } else if (existing.status == Status.COMPLETED) {
                // Fingerprint check: same key must have same request params
                if (!existing.requestFingerprint.equals(requestFingerprint)) {
                    metrics.recordFingerprintMismatch();
                    throw new IdempotencyException(
                            "Idempotency key reused with different request parameters. " +
                            "Key: " + idempotencyKey);
                }
                metrics.recordHit();
                return existing.response; // Replay cached result
            } else if (existing.status == Status.IN_PROGRESS) {
                metrics.recordConflict();
                throw new IdempotencyException(
                        "Request already in progress for key: " + idempotencyKey);
            }
            // Status.FAILED: allow retry — fall through
        }

        metrics.recordMiss();

        // Mark as in-progress
        long now = System.currentTimeMillis();
        store.put(idempotencyKey, new StoredResult(
                Status.IN_PROGRESS, null, requestFingerprint, now, 0));

        try {
            String result = operation.get();
            store.put(idempotencyKey, new StoredResult(
                    Status.COMPLETED, result, requestFingerprint,
                    now, System.currentTimeMillis()));
            return result;
        } catch (Exception e) {
            store.put(idempotencyKey, new StoredResult(
                    Status.FAILED, null, requestFingerprint, now, 0));
            throw e;
        }
    }

    public Metrics getMetrics() { return metrics; }

    public void shutdown() {
        cleanupExecutor.shutdown();
    }

    static class IdempotencyException extends RuntimeException {
        IdempotencyException(String message) { super(message); }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== Idempotency Key Store Tests (Staff Level) ===\n");

        testReplayResult();
        testFingerprintMismatchDetection();
        testFailedRetryAllowed();
        testTTLExpiry();
        testConcurrentDifferentKeys();
        testConcurrentSameKey();
        testMetrics();
        testCleanup();

        System.out.println("\nAll tests passed.");
    }

    private static int callCount = 0;

    private static void testReplayResult() {
        IdempotencyKeyStore store = new IdempotencyKeyStore(60_000, 30_000);
        callCount = 0;

        Supplier<String> op = () -> { callCount++; return "charge_ch_123"; };

        String r1 = store.execute("key-1", "fp-abc", op);
        String r2 = store.execute("key-1", "fp-abc", op);

        assert r1.equals("charge_ch_123");
        assert r2.equals("charge_ch_123");
        assert callCount == 1 : "Should only execute once";

        store.shutdown();
        System.out.println("[PASS] testReplayResult");
    }

    private static void testFingerprintMismatchDetection() {
        IdempotencyKeyStore store = new IdempotencyKeyStore(60_000, 30_000);

        store.execute("key-2", "fp-original", () -> "result");

        try {
            store.execute("key-2", "fp-DIFFERENT", () -> "result");
            assert false : "Should throw on fingerprint mismatch";
        } catch (IdempotencyException e) {
            assert e.getMessage().contains("different request parameters");
        }

        store.shutdown();
        System.out.println("[PASS] testFingerprintMismatchDetection");
    }

    private static void testFailedRetryAllowed() {
        IdempotencyKeyStore store = new IdempotencyKeyStore(60_000, 30_000);
        callCount = 0;

        // First attempt fails
        try {
            store.execute("key-3", "fp-x", () -> {
                callCount++;
                throw new RuntimeException("Gateway timeout");
            });
        } catch (RuntimeException ignored) {}

        // Retry should re-execute
        String result = store.execute("key-3", "fp-x", () -> {
            callCount++;
            return "charge_ch_456";
        });

        assert callCount == 2;
        assert result.equals("charge_ch_456");

        store.shutdown();
        System.out.println("[PASS] testFailedRetryAllowed");
    }

    private static void testTTLExpiry() throws InterruptedException {
        IdempotencyKeyStore store = new IdempotencyKeyStore(200, 100);
        callCount = 0;

        store.execute("key-4", "fp-a", () -> { callCount++; return "r1"; });
        Thread.sleep(300);

        String result = store.execute("key-4", "fp-a", () -> { callCount++; return "r2"; });

        assert callCount == 2 : "Should re-execute after TTL";
        assert result.equals("r2");

        store.shutdown();
        System.out.println("[PASS] testTTLExpiry");
    }

    private static void testConcurrentDifferentKeys() throws Exception {
        IdempotencyKeyStore store = new IdempotencyKeyStore(60_000, 30_000);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch latch = new CountDownLatch(4);
        List<String> results = new CopyOnWriteArrayList<>();

        // 4 concurrent requests with DIFFERENT keys — should all succeed
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    String r = store.execute("key-" + idx, "fp-" + idx,
                            () -> "result-" + idx);
                    results.add(r);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        assert results.size() == 4 : "All 4 should succeed concurrently";

        executor.shutdown();
        store.shutdown();
        System.out.println("[PASS] testConcurrentDifferentKeys");
    }

    private static void testConcurrentSameKey() throws Exception {
        IdempotencyKeyStore store = new IdempotencyKeyStore(60_000, 30_000);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch latch = new CountDownLatch(2);

        List<String> successes = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();

        // Two concurrent requests with SAME key
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    started.await();
                    String r = store.execute("same-key", "fp-same", () -> {
                        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                        return "result";
                    });
                    successes.add(r);
                } catch (IdempotencyException e) {
                    errors.add(e.getMessage());
                } catch (InterruptedException ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        started.countDown(); // release both threads
        latch.await(5, TimeUnit.SECONDS);

        // One should succeed, one should get conflict or replay
        assert successes.size() >= 1 : "At least one should succeed";

        executor.shutdown();
        store.shutdown();
        System.out.println("[PASS] testConcurrentSameKey");
    }

    private static void testMetrics() {
        IdempotencyKeyStore store = new IdempotencyKeyStore(60_000, 30_000);

        store.execute("m1", "fp1", () -> "r1");
        store.execute("m1", "fp1", () -> "r1"); // hit
        store.execute("m2", "fp2", () -> "r2"); // miss

        Metrics m = store.getMetrics();
        assert m.cacheHits == 1 : "Should have 1 hit";
        assert m.cacheMisses == 2 : "Should have 2 misses";
        assert m.totalRequests == 3;

        store.shutdown();
        System.out.println("[PASS] testMetrics");
    }

    private static void testCleanup() throws InterruptedException {
        IdempotencyKeyStore store = new IdempotencyKeyStore(100, 50);

        store.execute("cleanup-1", "fp1", () -> "r1");
        store.execute("cleanup-2", "fp2", () -> "r2");

        assert store.store.size() == 2;

        Thread.sleep(300); // Wait for cleanup to run

        assert store.store.size() == 0 : "Expired entries should be cleaned up";

        store.shutdown();
        System.out.println("[PASS] testCleanup");
    }
}
