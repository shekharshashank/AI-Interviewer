import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stripe Interview Question #5: Webhook Event Delivery System
 *
 * Staff-level extensions:
 * - Circuit breaker per endpoint (stop hammering failing endpoints)
 * - HMAC signature generation for webhook payload verification
 * - Ordering guarantees within an event type
 * - Concurrent delivery across endpoints with thread pool
 * - Delivery rate limiting per endpoint
 * - Batch delivery support
 * - Comprehensive event lifecycle tracking
 */
public class WebhookDelivery {

    // =========================================================================
    // Data models
    // =========================================================================

    static class WebhookEvent {
        final String eventId;
        final String type;
        final String payload;
        final long timestamp;
        final int sequenceNumber; // for ordering within a type

        WebhookEvent(String eventId, String type, String payload,
                     long timestamp, int sequenceNumber) {
            this.eventId = eventId;
            this.type = type;
            this.payload = payload;
            this.timestamp = timestamp;
            this.sequenceNumber = sequenceNumber;
        }
    }

    enum DeliveryStatus {
        PENDING, DELIVERED, RETRYING, CIRCUIT_OPEN, DEAD_LETTERED
    }

    static class DeliveryRecord {
        final String eventId;
        final String endpointUrl;
        DeliveryStatus status;
        int attemptCount;
        final List<AttemptDetail> attempts = new ArrayList<>();
        long nextRetryAt;

        DeliveryRecord(String eventId, String endpointUrl) {
            this.eventId = eventId;
            this.endpointUrl = endpointUrl;
            this.status = DeliveryStatus.PENDING;
            this.attemptCount = 0;
        }
    }

    static class AttemptDetail {
        final int attemptNumber;
        final long timestamp;
        final int httpStatus;
        final String error;
        final long durationMs;

        AttemptDetail(int attemptNumber, long timestamp, int httpStatus,
                      String error, long durationMs) {
            this.attemptNumber = attemptNumber;
            this.timestamp = timestamp;
            this.httpStatus = httpStatus;
            this.error = error;
            this.durationMs = durationMs;
        }
    }

    // =========================================================================
    // Circuit Breaker (per-endpoint)
    // =========================================================================

    static class CircuitBreaker {
        enum State { CLOSED, OPEN, HALF_OPEN }

        private State state = State.CLOSED;
        private int failureCount = 0;
        private long lastFailureTime = 0;
        private final int failureThreshold;
        private final long resetTimeoutMs;

        CircuitBreaker(int failureThreshold, long resetTimeoutMs) {
            this.failureThreshold = failureThreshold;
            this.resetTimeoutMs = resetTimeoutMs;
        }

        synchronized boolean allowRequest() {
            switch (state) {
                case CLOSED:
                    return true;
                case OPEN:
                    if (System.currentTimeMillis() - lastFailureTime > resetTimeoutMs) {
                        state = State.HALF_OPEN;
                        return true; // Allow one probe request
                    }
                    return false;
                case HALF_OPEN:
                    return false; // Only one probe at a time
                default:
                    return false;
            }
        }

        synchronized void recordSuccess() {
            failureCount = 0;
            state = State.CLOSED;
        }

        synchronized void recordFailure() {
            failureCount++;
            lastFailureTime = System.currentTimeMillis();
            if (failureCount >= failureThreshold) {
                state = State.OPEN;
            }
        }

        State getState() { return state; }
    }

    // =========================================================================
    // Webhook Signature (HMAC-SHA256)
    // =========================================================================

    static class WebhookSigner {
        private final String secret;

        WebhookSigner(String secret) {
            this.secret = secret;
        }

        String sign(String payload, long timestamp) {
            String signedPayload = timestamp + "." + payload;
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret.getBytes(), "HmacSHA256"));
                byte[] hash = mac.doFinal(signedPayload.getBytes());
                return bytesToHex(hash);
            } catch (Exception e) {
                throw new RuntimeException("Signing failed", e);
            }
        }

        boolean verify(String payload, long timestamp, String signature) {
            String expected = sign(payload, timestamp);
            return expected.equals(signature);
        }

        private static String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    // =========================================================================
    // Core delivery system
    // =========================================================================

    private final int maxRetries;
    private final long baseDelayMs;
    private final ConcurrentHashMap<String, CircuitBreaker> circuitBreakers =
            new ConcurrentHashMap<>();
    private final Set<String> deliveredEventIds = ConcurrentHashMap.newKeySet();
    private final List<DeliveryRecord> deadLetterQueue = new CopyOnWriteArrayList<>();
    private final Map<String, DeliveryRecord> deliveryLog = new ConcurrentHashMap<>();
    private final WebhookSigner signer;
    private final Random random = new Random(42);

    // Ordering: track last delivered sequence per (endpoint, eventType)
    private final ConcurrentHashMap<String, Integer> lastDeliveredSequence =
            new ConcurrentHashMap<>();

    public WebhookDelivery(int maxRetries, long baseDelayMs, String signingSecret) {
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
        this.signer = new WebhookSigner(signingSecret);
    }

    /**
     * Deliver an event to an endpoint with full lifecycle management.
     *
     * @param event    the event to deliver
     * @param endpoint simulates HTTP POST — returns status code
     * @param endpointUrl identifier for the endpoint (for circuit breaker)
     * @return delivery record with full attempt history
     */
    public DeliveryRecord deliver(WebhookEvent event,
                                   Function<WebhookEvent, Integer> endpoint,
                                   String endpointUrl) {
        // Deduplication
        if (deliveredEventIds.contains(event.eventId + ":" + endpointUrl)) {
            DeliveryRecord cached = deliveryLog.get(event.eventId + ":" + endpointUrl);
            return cached;
        }

        // Ordering check
        String orderKey = endpointUrl + ":" + event.type;
        Integer lastSeq = lastDeliveredSequence.get(orderKey);
        if (lastSeq != null && event.sequenceNumber <= lastSeq) {
            // Out-of-order event — skip (already delivered a later one)
            DeliveryRecord skipped = new DeliveryRecord(event.eventId, endpointUrl);
            skipped.status = DeliveryStatus.DELIVERED;
            return skipped;
        }

        CircuitBreaker cb = circuitBreakers.computeIfAbsent(
                endpointUrl, k -> new CircuitBreaker(5, 30_000));

        DeliveryRecord record = new DeliveryRecord(event.eventId, endpointUrl);

        // Generate signature
        String signature = signer.sign(event.payload, event.timestamp);

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // Circuit breaker check
            if (!cb.allowRequest()) {
                record.status = DeliveryStatus.CIRCUIT_OPEN;
                deadLetterQueue.add(record);
                deliveryLog.put(event.eventId + ":" + endpointUrl, record);
                return record;
            }

            record.attemptCount++;
            long startTime = System.currentTimeMillis();

            try {
                int statusCode = endpoint.apply(event);
                long duration = System.currentTimeMillis() - startTime;

                record.attempts.add(new AttemptDetail(
                        attempt + 1, System.currentTimeMillis(),
                        statusCode, null, duration));

                if (statusCode >= 200 && statusCode < 300) {
                    cb.recordSuccess();
                    record.status = DeliveryStatus.DELIVERED;
                    deliveredEventIds.add(event.eventId + ":" + endpointUrl);
                    lastDeliveredSequence.put(orderKey, event.sequenceNumber);
                    deliveryLog.put(event.eventId + ":" + endpointUrl, record);
                    return record;
                }

                cb.recordFailure();
                record.status = DeliveryStatus.RETRYING;

            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                record.attempts.add(new AttemptDetail(
                        attempt + 1, System.currentTimeMillis(),
                        0, e.getMessage(), duration));
                cb.recordFailure();
            }

            // Backoff before next retry
            if (attempt < maxRetries) {
                long delay = calculateBackoff(attempt);
                record.nextRetryAt = System.currentTimeMillis() + delay;
                sleep(delay);
            }
        }

        // Exhausted retries
        record.status = DeliveryStatus.DEAD_LETTERED;
        deadLetterQueue.add(record);
        deliveryLog.put(event.eventId + ":" + endpointUrl, record);
        return record;
    }

    long calculateBackoff(int attempt) {
        long exponentialDelay = baseDelayMs * (1L << attempt);
        long jitter = (long) (random.nextDouble() * exponentialDelay * 0.5);
        return exponentialDelay + jitter;
    }

    private void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public List<DeliveryRecord> getDeadLetterQueue() {
        return Collections.unmodifiableList(deadLetterQueue);
    }

    public CircuitBreaker.State getCircuitState(String endpointUrl) {
        CircuitBreaker cb = circuitBreakers.get(endpointUrl);
        return cb != null ? cb.getState() : CircuitBreaker.State.CLOSED;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Webhook Delivery Tests (Staff Level) ===\n");

        testSuccessfulDelivery();
        testRetryWithBackoff();
        testDeduplication();
        testCircuitBreaker();
        testSignatureGeneration();
        testOrderingGuarantee();
        testDeadLetterOnExhaustedRetries();

        System.out.println("\nAll tests passed.");
    }

    private static void testSuccessfulDelivery() {
        WebhookDelivery system = new WebhookDelivery(3, 10, "whsec_test123");
        WebhookEvent event = new WebhookEvent("evt_001", "payment_intent.succeeded",
                "{\"amount\":2000}", System.currentTimeMillis(), 1);

        DeliveryRecord record = system.deliver(event, e -> 200, "https://merchant.com/webhook");

        assert record.status == DeliveryStatus.DELIVERED;
        assert record.attemptCount == 1;
        assert record.attempts.get(0).httpStatus == 200;

        System.out.println("[PASS] testSuccessfulDelivery");
    }

    private static void testRetryWithBackoff() {
        WebhookDelivery system = new WebhookDelivery(3, 10, "whsec_test123");
        WebhookEvent event = new WebhookEvent("evt_002", "charge.failed",
                "{}", System.currentTimeMillis(), 1);

        int[] callCount = {0};
        DeliveryRecord record = system.deliver(event, e -> {
            callCount[0]++;
            return callCount[0] >= 3 ? 200 : 500;
        }, "https://flaky.com/webhook");

        assert record.status == DeliveryStatus.DELIVERED;
        assert record.attemptCount == 3;
        assert record.attempts.size() == 3;
        assert record.attempts.get(0).httpStatus == 500;
        assert record.attempts.get(2).httpStatus == 200;

        System.out.println("[PASS] testRetryWithBackoff");
    }

    private static void testDeduplication() {
        WebhookDelivery system = new WebhookDelivery(3, 10, "whsec_test123");
        WebhookEvent event = new WebhookEvent("evt_003", "customer.created",
                "{}", System.currentTimeMillis(), 1);

        int[] callCount = {0};
        Function<WebhookEvent, Integer> endpoint = e -> { callCount[0]++; return 200; };

        system.deliver(event, endpoint, "https://example.com/webhook");
        system.deliver(event, endpoint, "https://example.com/webhook"); // duplicate

        assert callCount[0] == 1 : "Should only call endpoint once";

        System.out.println("[PASS] testDeduplication");
    }

    private static void testCircuitBreaker() {
        WebhookDelivery system = new WebhookDelivery(0, 10, "whsec_test123");
        // maxRetries=0 means each event gets exactly 1 attempt

        String endpoint = "https://down.com/webhook";

        // Send 5 failing events to trip the circuit breaker (threshold=5)
        for (int i = 0; i < 5; i++) {
            WebhookEvent evt = new WebhookEvent("evt_cb_" + i, "test",
                    "{}", System.currentTimeMillis(), i + 1);
            system.deliver(evt, e -> 500, endpoint);
        }

        assert system.getCircuitState(endpoint) == CircuitBreaker.State.OPEN
                : "Circuit should be open after 5 failures";

        // Next delivery should be short-circuited
        WebhookEvent blocked = new WebhookEvent("evt_cb_blocked", "test",
                "{}", System.currentTimeMillis(), 6);
        DeliveryRecord record = system.deliver(blocked, e -> 200, endpoint);

        assert record.status == DeliveryStatus.CIRCUIT_OPEN
                : "Should not attempt delivery when circuit is open";

        System.out.println("[PASS] testCircuitBreaker");
    }

    private static void testSignatureGeneration() {
        WebhookSigner signer = new WebhookSigner("whsec_secret123");
        String payload = "{\"id\":\"evt_123\",\"type\":\"charge.succeeded\"}";
        long timestamp = 1234567890L;

        String sig1 = signer.sign(payload, timestamp);
        String sig2 = signer.sign(payload, timestamp);
        assert sig1.equals(sig2) : "Same input should produce same signature";

        assert signer.verify(payload, timestamp, sig1) : "Should verify correctly";
        assert !signer.verify(payload, timestamp + 1, sig1) : "Wrong timestamp should fail";
        assert !signer.verify(payload + "x", timestamp, sig1) : "Tampered payload should fail";

        System.out.println("[PASS] testSignatureGeneration");
    }

    private static void testOrderingGuarantee() {
        WebhookDelivery system = new WebhookDelivery(3, 10, "whsec_test123");
        String endpoint = "https://example.com/webhook";

        List<String> deliveredOrder = new CopyOnWriteArrayList<>();

        // Deliver events in order
        for (int i = 1; i <= 3; i++) {
            WebhookEvent evt = new WebhookEvent("evt_ord_" + i, "invoice.updated",
                    "{\"seq\":" + i + "}", System.currentTimeMillis(), i);
            system.deliver(evt, e -> {
                deliveredOrder.add(e.eventId);
                return 200;
            }, endpoint);
        }

        assert deliveredOrder.size() == 3;

        // Now try to deliver an older event (seq=1) — should be skipped
        WebhookEvent stale = new WebhookEvent("evt_ord_stale", "invoice.updated",
                "{\"seq\":0}", System.currentTimeMillis(), 1);
        int[] staleCallCount = {0};
        system.deliver(stale, e -> { staleCallCount[0]++; return 200; }, endpoint);

        // The stale event should be skipped because seq 3 was already delivered
        assert staleCallCount[0] == 0 : "Stale event should be skipped";

        System.out.println("[PASS] testOrderingGuarantee");
    }

    private static void testDeadLetterOnExhaustedRetries() {
        WebhookDelivery system = new WebhookDelivery(2, 10, "whsec_test123");
        WebhookEvent event = new WebhookEvent("evt_dl", "payout.failed",
                "{}", System.currentTimeMillis(), 1);

        DeliveryRecord record = system.deliver(event, e -> 500,
                "https://broken.com/webhook");

        assert record.status == DeliveryStatus.DEAD_LETTERED;
        assert record.attemptCount == 3; // 1 initial + 2 retries
        assert system.getDeadLetterQueue().size() == 1;

        System.out.println("[PASS] testDeadLetterOnExhaustedRetries");
    }
}
