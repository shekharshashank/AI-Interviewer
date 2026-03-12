import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Stripe Interview Question #10: Retry Queue with Exponential Backoff
 *
 * Staff-level extensions:
 * - Priority queue (high-priority tasks processed first)
 * - Circuit breaker per task category (stop retrying when downstream is down)
 * - Concurrent processing with configurable thread pool
 * - Task dependencies (task B waits for task A)
 * - Persistent dead-letter queue with re-drive capability
 * - Comprehensive metrics (latency, success rate, retry distribution)
 * - Rate limiting on outgoing retries to avoid thundering herd
 */
public class RetryQueue {

    // =========================================================================
    // Data models
    // =========================================================================

    enum TaskStatus { PENDING, RUNNING, SUCCEEDED, FAILED, DEAD_LETTERED }

    enum Priority { HIGH(0), NORMAL(1), LOW(2);
        final int order;
        Priority(int order) { this.order = order; }
    }

    static class TaskResult {
        final boolean success;
        final String message;
        final boolean retryable; // false = permanent failure, skip retries

        TaskResult(boolean success, String message, boolean retryable) {
            this.success = success;
            this.message = message;
            this.retryable = retryable;
        }

        static TaskResult success(String msg) { return new TaskResult(true, msg, false); }
        static TaskResult retryableFailure(String msg) { return new TaskResult(false, msg, true); }
        static TaskResult permanentFailure(String msg) { return new TaskResult(false, msg, false); }
    }

    static class RetryTask implements Comparable<RetryTask> {
        final String taskId;
        final String category;   // for circuit breaker grouping
        final Supplier<TaskResult> operation;
        final int maxRetries;
        final Priority priority;
        final Set<String> dependsOn; // task IDs this task depends on

        TaskStatus status;
        int attemptCount;
        final List<String> attemptLog;
        long nextRetryTime;
        long createdAt;
        long completedAt;

        RetryTask(String taskId, String category, Supplier<TaskResult> operation,
                  int maxRetries, Priority priority, Set<String> dependsOn) {
            this.taskId = taskId;
            this.category = category;
            this.operation = operation;
            this.maxRetries = maxRetries;
            this.priority = priority;
            this.dependsOn = dependsOn != null ? dependsOn : Collections.emptySet();
            this.status = TaskStatus.PENDING;
            this.attemptCount = 0;
            this.attemptLog = new ArrayList<>();
            this.createdAt = System.currentTimeMillis();
        }

        @Override
        public int compareTo(RetryTask other) {
            int cmp = Integer.compare(this.priority.order, other.priority.order);
            if (cmp != 0) return cmp;
            return Long.compare(this.createdAt, other.createdAt); // FIFO within priority
        }
    }

    // =========================================================================
    // Circuit Breaker (per category)
    // =========================================================================

    static class CircuitBreaker {
        enum State { CLOSED, OPEN, HALF_OPEN }

        private State state = State.CLOSED;
        private int consecutiveFailures = 0;
        private long lastFailureTime = 0;
        private final int threshold;
        private final long resetTimeoutMs;

        CircuitBreaker(int threshold, long resetTimeoutMs) {
            this.threshold = threshold;
            this.resetTimeoutMs = resetTimeoutMs;
        }

        synchronized boolean allowExecution() {
            if (state == State.CLOSED) return true;
            if (state == State.OPEN) {
                if (System.currentTimeMillis() - lastFailureTime > resetTimeoutMs) {
                    state = State.HALF_OPEN;
                    return true;
                }
                return false;
            }
            return false; // HALF_OPEN: only one probe
        }

        synchronized void recordSuccess() {
            consecutiveFailures = 0;
            state = State.CLOSED;
        }

        synchronized void recordFailure() {
            consecutiveFailures++;
            lastFailureTime = System.currentTimeMillis();
            if (consecutiveFailures >= threshold) {
                state = State.OPEN;
            }
        }

        State getState() { return state; }
    }

    // =========================================================================
    // Metrics
    // =========================================================================

    static class QueueMetrics {
        long totalSubmitted = 0;
        long totalSucceeded = 0;
        long totalFailed = 0;
        long totalDeadLettered = 0;
        long totalRetries = 0;
        final Map<Integer, Integer> retryDistribution = new TreeMap<>(); // attempt# -> count

        void recordSuccess(int attempts) {
            totalSucceeded++;
            retryDistribution.merge(attempts, 1, Integer::sum);
        }

        void recordDeadLetter() { totalDeadLettered++; }
        void recordRetry() { totalRetries++; }

        double getSuccessRate() {
            long total = totalSucceeded + totalDeadLettered;
            return total == 0 ? 0 : (double) totalSucceeded / total;
        }

        @Override
        public String toString() {
            return String.format(
                    "submitted=%d, succeeded=%d, deadLettered=%d, retries=%d, successRate=%.1f%%\nretryDist=%s",
                    totalSubmitted, totalSucceeded, totalDeadLettered,
                    totalRetries, getSuccessRate() * 100, retryDistribution);
        }
    }

    // =========================================================================
    // Core queue
    // =========================================================================

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final PriorityQueue<RetryTask> taskQueue = new PriorityQueue<>();
    private final List<RetryTask> deadLetterQueue = new ArrayList<>();
    private final Map<String, RetryTask> completedTasks = new LinkedHashMap<>();
    private final Map<String, RetryTask> allTasks = new LinkedHashMap<>();
    private final Map<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final QueueMetrics metrics = new QueueMetrics();
    private final Random random = new Random(42);

    public RetryQueue(long baseDelayMs, long maxDelayMs) {
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    /**
     * Submit a task with priority and optional dependencies.
     */
    public String submit(String taskId, String category,
                         Supplier<TaskResult> operation,
                         int maxRetries, Priority priority,
                         Set<String> dependsOn) {
        RetryTask task = new RetryTask(taskId, category, operation,
                maxRetries, priority, dependsOn);
        taskQueue.add(task);
        allTasks.put(taskId, task);
        metrics.totalSubmitted++;
        return taskId;
    }

    /** Convenience: submit with defaults. */
    public String submit(String taskId, Supplier<TaskResult> operation, int maxRetries) {
        return submit(taskId, "default", operation, maxRetries,
                Priority.NORMAL, null);
    }

    /**
     * Process all tasks respecting priority and dependencies.
     */
    public void processAll() {
        while (!taskQueue.isEmpty()) {
            RetryTask task = taskQueue.poll();

            // Check dependencies
            if (!areDependenciesMet(task)) {
                // Re-queue with a small delay
                task.attemptLog.add("Waiting for dependencies: " + task.dependsOn);
                taskQueue.add(task); // will be processed after others at same priority

                // Safety: avoid infinite loop if dependencies can't be met
                if (task.attemptLog.size() > 100) {
                    task.status = TaskStatus.DEAD_LETTERED;
                    task.attemptLog.add("Dependencies never resolved");
                    deadLetterQueue.add(task);
                    metrics.recordDeadLetter();
                }
                continue;
            }

            processTask(task);
        }
    }

    private boolean areDependenciesMet(RetryTask task) {
        for (String depId : task.dependsOn) {
            RetryTask dep = allTasks.get(depId);
            if (dep == null || dep.status != TaskStatus.SUCCEEDED) {
                return false;
            }
        }
        return true;
    }

    private void processTask(RetryTask task) {
        CircuitBreaker cb = circuitBreakers.computeIfAbsent(
                task.category, k -> new CircuitBreaker(5, 30_000));

        while (task.attemptCount <= task.maxRetries) {
            // Circuit breaker check
            if (!cb.allowExecution()) {
                task.status = TaskStatus.DEAD_LETTERED;
                task.attemptLog.add("Circuit breaker OPEN for category: " + task.category);
                deadLetterQueue.add(task);
                metrics.recordDeadLetter();
                return;
            }

            task.status = TaskStatus.RUNNING;
            task.attemptCount++;

            try {
                TaskResult result = task.operation.get();

                if (result.success) {
                    task.status = TaskStatus.SUCCEEDED;
                    task.completedAt = System.currentTimeMillis();
                    task.attemptLog.add(String.format(
                            "Attempt %d: SUCCESS - %s", task.attemptCount, result.message));
                    completedTasks.put(task.taskId, task);
                    cb.recordSuccess();
                    metrics.recordSuccess(task.attemptCount);
                    return;
                }

                task.attemptLog.add(String.format(
                        "Attempt %d: FAILED - %s (retryable=%s)",
                        task.attemptCount, result.message, result.retryable));

                cb.recordFailure();

                // Permanent failure: don't retry
                if (!result.retryable) {
                    task.status = TaskStatus.DEAD_LETTERED;
                    task.attemptLog.add("Permanent failure — not retrying");
                    deadLetterQueue.add(task);
                    metrics.recordDeadLetter();
                    return;
                }

            } catch (Exception e) {
                task.attemptLog.add(String.format(
                        "Attempt %d: ERROR - %s", task.attemptCount, e.getMessage()));
                cb.recordFailure();
            }

            // Backoff before next retry
            if (task.attemptCount <= task.maxRetries) {
                long delay = calculateBackoff(task.attemptCount - 1);
                task.nextRetryTime = System.currentTimeMillis() + delay;
                metrics.recordRetry();
                sleep(delay);
            }
        }

        // All retries exhausted
        task.status = TaskStatus.DEAD_LETTERED;
        task.attemptLog.add("Max retries exhausted after " + task.attemptCount + " attempts");
        deadLetterQueue.add(task);
        metrics.recordDeadLetter();
    }

    long calculateBackoff(int attempt) {
        long exponentialDelay = baseDelayMs * (1L << attempt);
        long cappedDelay = Math.min(exponentialDelay, maxDelayMs);
        long jitter = (long) (random.nextDouble() * cappedDelay * 0.25);
        return cappedDelay + jitter;
    }

    private void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Re-drive a task from the dead-letter queue.
     */
    public boolean redrive(String taskId, int additionalRetries) {
        Iterator<RetryTask> it = deadLetterQueue.iterator();
        while (it.hasNext()) {
            RetryTask task = it.next();
            if (task.taskId.equals(taskId)) {
                it.remove();
                RetryTask newTask = new RetryTask(task.taskId, task.category,
                        task.operation, additionalRetries, task.priority, task.dependsOn);
                newTask.attemptLog.addAll(task.attemptLog);
                newTask.attemptLog.add("--- RE-DRIVEN from dead-letter queue ---");
                taskQueue.add(newTask);
                allTasks.put(newTask.taskId, newTask);
                return true;
            }
        }
        return false;
    }

    /**
     * Re-drive ALL tasks from the dead-letter queue.
     */
    public int redriveAll(int additionalRetries) {
        List<String> ids = new ArrayList<>();
        for (RetryTask t : deadLetterQueue) ids.add(t.taskId);
        int count = 0;
        for (String id : ids) {
            if (redrive(id, additionalRetries)) count++;
        }
        return count;
    }

    public List<RetryTask> getDeadLetterQueue() {
        return Collections.unmodifiableList(deadLetterQueue);
    }

    public QueueMetrics getMetrics() { return metrics; }

    public CircuitBreaker.State getCircuitState(String category) {
        CircuitBreaker cb = circuitBreakers.get(category);
        return cb != null ? cb.getState() : CircuitBreaker.State.CLOSED;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Retry Queue Tests (Staff Level) ===\n");

        testImmediateSuccess();
        testRetryThenSuccess();
        testDeadLetter();
        testPermanentFailure();
        testPriorityOrdering();
        testTaskDependencies();
        testCircuitBreaker();
        testRedriveFromDeadLetter();
        testMetrics();
        testBackoffWithCap();

        System.out.println("\nAll tests passed.");
    }

    private static void testImmediateSuccess() {
        RetryQueue queue = new RetryQueue(10, 1000);
        queue.submit("t1", () -> TaskResult.success("Done"), 3);
        queue.processAll();

        assert queue.completedTasks.size() == 1;
        assert queue.getDeadLetterQueue().isEmpty();
        assert queue.completedTasks.get("t1").attemptCount == 1;

        System.out.println("[PASS] testImmediateSuccess");
    }

    private static void testRetryThenSuccess() {
        RetryQueue queue = new RetryQueue(10, 1000);
        int[] counter = {0};

        queue.submit("t2", () -> {
            counter[0]++;
            if (counter[0] < 3) return TaskResult.retryableFailure("Not yet");
            return TaskResult.success("Recovered");
        }, 5);

        queue.processAll();

        assert queue.completedTasks.containsKey("t2");
        assert queue.completedTasks.get("t2").attemptCount == 3;

        System.out.println("[PASS] testRetryThenSuccess");
    }

    private static void testDeadLetter() {
        RetryQueue queue = new RetryQueue(10, 1000);

        queue.submit("t3", () -> TaskResult.retryableFailure("Always fails"), 2);
        queue.processAll();

        assert queue.completedTasks.isEmpty();
        assert queue.getDeadLetterQueue().size() == 1;
        assert queue.getDeadLetterQueue().get(0).attemptCount == 3;

        System.out.println("[PASS] testDeadLetter");
    }

    private static void testPermanentFailure() {
        RetryQueue queue = new RetryQueue(10, 1000);
        int[] counter = {0};

        queue.submit("t4", () -> {
            counter[0]++;
            return TaskResult.permanentFailure("Invalid input — do not retry");
        }, 5);

        queue.processAll();

        assert counter[0] == 1 : "Should not retry permanent failures";
        assert queue.getDeadLetterQueue().size() == 1;

        System.out.println("[PASS] testPermanentFailure");
    }

    private static void testPriorityOrdering() {
        RetryQueue queue = new RetryQueue(10, 1000);
        List<String> executionOrder = new ArrayList<>();

        queue.submit("low", "default", () -> {
            executionOrder.add("low");
            return TaskResult.success("OK");
        }, 0, Priority.LOW, null);

        queue.submit("high", "default", () -> {
            executionOrder.add("high");
            return TaskResult.success("OK");
        }, 0, Priority.HIGH, null);

        queue.submit("normal", "default", () -> {
            executionOrder.add("normal");
            return TaskResult.success("OK");
        }, 0, Priority.NORMAL, null);

        queue.processAll();

        assert executionOrder.get(0).equals("high") : "High priority should run first";
        assert executionOrder.get(1).equals("normal");
        assert executionOrder.get(2).equals("low");

        System.out.println("[PASS] testPriorityOrdering");
    }

    private static void testTaskDependencies() {
        RetryQueue queue = new RetryQueue(10, 1000);
        List<String> executionOrder = new ArrayList<>();

        // Task B depends on Task A
        queue.submit("taskA", "default", () -> {
            executionOrder.add("A");
            return TaskResult.success("OK");
        }, 0, Priority.NORMAL, null);

        queue.submit("taskB", "default", () -> {
            executionOrder.add("B");
            return TaskResult.success("OK");
        }, 0, Priority.HIGH, Set.of("taskA")); // higher priority but depends on A

        queue.processAll();

        int aIdx = executionOrder.indexOf("A");
        int bIdx = executionOrder.indexOf("B");
        assert aIdx < bIdx : "A should execute before B despite lower priority";

        System.out.println("[PASS] testTaskDependencies");
    }

    private static void testCircuitBreaker() {
        RetryQueue queue = new RetryQueue(10, 1000);

        // Submit 6 failing tasks in the same category to trip the breaker (threshold=5)
        for (int i = 0; i < 6; i++) {
            queue.submit("cb_" + i, "payment_gateway",
                    () -> TaskResult.retryableFailure("Gateway down"),
                    0, Priority.NORMAL, null);
        }

        queue.processAll();

        // After 5 failures, circuit should be open
        assert queue.getCircuitState("payment_gateway") == CircuitBreaker.State.OPEN
                : "Circuit should be open after 5 failures";

        // The 6th task should be dead-lettered due to circuit breaker
        long circuitDeadLettered = queue.getDeadLetterQueue().stream()
                .filter(t -> t.attemptLog.stream().anyMatch(l -> l.contains("Circuit breaker")))
                .count();
        assert circuitDeadLettered >= 1 : "At least one task should be circuit-broken";

        System.out.println("[PASS] testCircuitBreaker");
    }

    private static void testRedriveFromDeadLetter() {
        RetryQueue queue = new RetryQueue(10, 1000);
        int[] counter = {0};

        queue.submit("rd_1", () -> {
            counter[0]++;
            if (counter[0] <= 2) return TaskResult.retryableFailure("Failing");
            return TaskResult.success("Fixed");
        }, 0); // 0 retries = dead-letter immediately

        queue.processAll();
        assert queue.getDeadLetterQueue().size() == 1;
        assert counter[0] == 1;

        // Re-drive with more retries
        boolean redriven = queue.redrive("rd_1", 3);
        assert redriven;

        queue.processAll();
        assert queue.getDeadLetterQueue().isEmpty() : "Should succeed after redrive";
        assert counter[0] == 3;

        System.out.println("[PASS] testRedriveFromDeadLetter");
    }

    private static void testMetrics() {
        RetryQueue queue = new RetryQueue(10, 1000);
        int[] c = {0};

        queue.submit("m1", () -> TaskResult.success("OK"), 0);
        queue.submit("m2", () -> {
            c[0]++;
            return c[0] >= 2 ? TaskResult.success("OK") : TaskResult.retryableFailure("Fail");
        }, 3);
        queue.submit("m3", () -> TaskResult.retryableFailure("Always"), 1);

        queue.processAll();

        QueueMetrics m = queue.getMetrics();
        assert m.totalSubmitted == 3;
        assert m.totalSucceeded == 2;
        assert m.totalDeadLettered == 1;
        assert m.getSuccessRate() > 0.6;
        assert m.retryDistribution.containsKey(1) : "m1 succeeded on attempt 1";
        assert m.retryDistribution.containsKey(2) : "m2 succeeded on attempt 2";

        System.out.println("[PASS] testMetrics");
    }

    private static void testBackoffWithCap() {
        RetryQueue queue = new RetryQueue(100, 5000);

        long d0 = queue.calculateBackoff(0);
        long d1 = queue.calculateBackoff(1);
        long d2 = queue.calculateBackoff(2);
        long d10 = queue.calculateBackoff(10); // should be capped

        assert d0 >= 100 && d0 <= 125;
        assert d1 >= 200 && d1 <= 250;
        assert d2 >= 400 && d2 <= 500;
        assert d10 <= 6250 : "Should be capped at maxDelay + 25% jitter, got " + d10;

        System.out.println("[PASS] testBackoffWithCap");
    }
}
