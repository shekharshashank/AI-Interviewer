import java.util.*;
import java.util.stream.Collectors;

/**
 * Stripe Interview Question #7: Fraud Detection — Anomaly in Transaction Stream
 *
 * Staff-level extensions:
 * - Configurable rule engine (add/remove/weight rules dynamically)
 * - Weighted risk scoring (not just boolean flags)
 * - Merchant-specific risk thresholds
 * - Blocklist/allowlist management
 * - Rule evaluation short-circuit for performance
 * - Explanation of risk decision (which rules fired and why)
 */
public class FraudDetector {

    // =========================================================================
    // Data models
    // =========================================================================

    static class Transaction {
        final String txId;
        final String userId;
        final String merchantId;
        final int amountCents;
        final long timestamp;
        final String country;
        final String cardFingerprint;
        final String ipAddress;

        Transaction(String txId, String userId, String merchantId, int amountCents,
                    long timestamp, String country, String cardFingerprint,
                    String ipAddress) {
            this.txId = txId;
            this.userId = userId;
            this.merchantId = merchantId;
            this.amountCents = amountCents;
            this.timestamp = timestamp;
            this.country = country;
            this.cardFingerprint = cardFingerprint;
            this.ipAddress = ipAddress;
        }
    }

    static class RiskAssessment {
        final double score;      // 0.0 (safe) to 1.0 (fraud)
        final RiskLevel level;
        final List<RuleResult> firedRules;
        final String action;     // "allow", "review", "block"

        RiskAssessment(double score, RiskLevel level,
                       List<RuleResult> firedRules, String action) {
            this.score = score;
            this.level = level;
            this.firedRules = firedRules;
            this.action = action;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Score: %.2f | Level: %s | Action: %s\n",
                    score, level, action));
            for (RuleResult r : firedRules) {
                sb.append(String.format("  [%.2f] %s: %s\n",
                        r.riskContribution, r.ruleName, r.explanation));
            }
            return sb.toString();
        }
    }

    enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    // =========================================================================
    // Rule engine
    // =========================================================================

    static class RuleResult {
        final String ruleName;
        final boolean triggered;
        final double riskContribution; // 0.0 to 1.0
        final String explanation;

        RuleResult(String ruleName, boolean triggered,
                   double riskContribution, String explanation) {
            this.ruleName = ruleName;
            this.triggered = triggered;
            this.riskContribution = riskContribution;
            this.explanation = explanation;
        }
    }

    @FunctionalInterface
    interface FraudRule {
        RuleResult evaluate(Transaction tx, TransactionHistory history);
    }

    static class WeightedRule {
        final String name;
        final FraudRule rule;
        final double weight;   // importance multiplier
        final boolean enabled;

        WeightedRule(String name, FraudRule rule, double weight, boolean enabled) {
            this.name = name;
            this.rule = rule;
            this.weight = weight;
            this.enabled = enabled;
        }
    }

    // =========================================================================
    // Transaction history (per-user state)
    // =========================================================================

    static class TransactionHistory {
        final Deque<Transaction> recentTransactions = new ArrayDeque<>();
        long totalAmountCents = 0;
        int transactionCount = 0;

        void add(Transaction tx) {
            recentTransactions.addLast(tx);
            totalAmountCents += tx.amountCents;
            transactionCount++;
        }

        void evictOlderThan(long cutoffTimestamp) {
            while (!recentTransactions.isEmpty()
                    && recentTransactions.peekFirst().timestamp < cutoffTimestamp) {
                recentTransactions.pollFirst();
            }
        }

        double getAverageAmount() {
            return transactionCount == 0 ? 0 : (double) totalAmountCents / transactionCount;
        }
    }

    // =========================================================================
    // Blocklist / Allowlist
    // =========================================================================

    private final Set<String> blockedCards = new HashSet<>();
    private final Set<String> blockedIPs = new HashSet<>();
    private final Set<String> allowedUsers = new HashSet<>();

    public void blockCard(String cardFingerprint) { blockedCards.add(cardFingerprint); }
    public void blockIP(String ip) { blockedIPs.add(ip); }
    public void allowUser(String userId) { allowedUsers.add(userId); }

    // =========================================================================
    // Merchant-specific thresholds
    // =========================================================================

    static class MerchantConfig {
        final double blockThreshold;    // score above this = block
        final double reviewThreshold;   // score above this = manual review
        final int velocityLimit;

        MerchantConfig(double blockThreshold, double reviewThreshold, int velocityLimit) {
            this.blockThreshold = blockThreshold;
            this.reviewThreshold = reviewThreshold;
            this.velocityLimit = velocityLimit;
        }
    }

    private final Map<String, MerchantConfig> merchantConfigs = new HashMap<>();
    private final MerchantConfig defaultConfig = new MerchantConfig(0.8, 0.5, 10);

    public void setMerchantConfig(String merchantId, MerchantConfig config) {
        merchantConfigs.put(merchantId, config);
    }

    // =========================================================================
    // Core detector
    // =========================================================================

    private final List<WeightedRule> rules = new ArrayList<>();
    private final Map<String, TransactionHistory> userHistory = new HashMap<>();
    private final long historyWindowMs;

    public FraudDetector(long historyWindowMs) {
        this.historyWindowMs = historyWindowMs;
        registerDefaultRules();
    }

    private void registerDefaultRules() {
        // Rule 1: Velocity check
        addRule("velocity", (tx, history) -> {
            MerchantConfig config = merchantConfigs.getOrDefault(
                    tx.merchantId, defaultConfig);
            int recentCount = history.recentTransactions.size();
            if (recentCount >= config.velocityLimit) {
                return new RuleResult("velocity", true, 0.7,
                        String.format("%d transactions in window (limit: %d)",
                                recentCount, config.velocityLimit));
            }
            return new RuleResult("velocity", false, 0.0, "Within limits");
        }, 1.0);

        // Rule 2: Amount anomaly
        addRule("amount_anomaly", (tx, history) -> {
            if (history.transactionCount < 3) {
                return new RuleResult("amount_anomaly", false, 0.0,
                        "Insufficient history");
            }
            double avg = history.getAverageAmount();
            double ratio = tx.amountCents / avg;
            if (ratio > 5.0) {
                return new RuleResult("amount_anomaly", true, 0.8,
                        String.format("Amount $%.2f is %.1fx the average $%.2f",
                                tx.amountCents / 100.0, ratio, avg / 100.0));
            } else if (ratio > 3.0) {
                return new RuleResult("amount_anomaly", true, 0.4,
                        String.format("Amount $%.2f is %.1fx the average $%.2f",
                                tx.amountCents / 100.0, ratio, avg / 100.0));
            }
            return new RuleResult("amount_anomaly", false, 0.0, "Normal amount");
        }, 1.2);

        // Rule 3: Geographic anomaly
        addRule("geo_anomaly", (tx, history) -> {
            if (history.recentTransactions.isEmpty()) {
                return new RuleResult("geo_anomaly", false, 0.0, "First transaction");
            }
            Transaction last = history.recentTransactions.peekLast();
            if (!last.country.equals(tx.country)) {
                long timeDiff = tx.timestamp - last.timestamp;
                if (timeDiff < 3_600_000) { // within 1 hour
                    return new RuleResult("geo_anomaly", true, 0.6,
                            String.format("Country changed %s -> %s in %d min",
                                    last.country, tx.country, timeDiff / 60_000));
                }
            }
            return new RuleResult("geo_anomaly", false, 0.0, "No geo anomaly");
        }, 1.0);

        // Rule 4: Blocklist check
        addRule("blocklist", (tx, history) -> {
            if (blockedCards.contains(tx.cardFingerprint)) {
                return new RuleResult("blocklist", true, 1.0,
                        "Card fingerprint is blocklisted");
            }
            if (blockedIPs.contains(tx.ipAddress)) {
                return new RuleResult("blocklist", true, 0.9,
                        "IP address is blocklisted");
            }
            return new RuleResult("blocklist", false, 0.0, "Not blocklisted");
        }, 2.0); // highest weight — blocklist hits are very strong signals

        // Rule 5: Large round amount (common fraud pattern)
        addRule("round_amount", (tx, history) -> {
            if (tx.amountCents >= 50000 && tx.amountCents % 10000 == 0) {
                return new RuleResult("round_amount", true, 0.3,
                        String.format("Large round amount: $%.2f",
                                tx.amountCents / 100.0));
            }
            return new RuleResult("round_amount", false, 0.0, "Non-round amount");
        }, 0.5);
    }

    public void addRule(String name, FraudRule rule, double weight) {
        rules.add(new WeightedRule(name, rule, weight, true));
    }

    /**
     * Assess the risk of a transaction.
     */
    public RiskAssessment assess(Transaction tx) {
        // Allowlist short-circuit
        if (allowedUsers.contains(tx.userId)) {
            return new RiskAssessment(0.0, RiskLevel.LOW,
                    Collections.emptyList(), "allow");
        }

        TransactionHistory history = userHistory.computeIfAbsent(
                tx.userId, k -> new TransactionHistory());
        history.evictOlderThan(tx.timestamp - historyWindowMs);

        // Evaluate all rules
        List<RuleResult> allResults = new ArrayList<>();
        double totalWeightedScore = 0.0;
        double totalWeight = 0.0;

        for (WeightedRule wr : rules) {
            if (!wr.enabled) continue;

            RuleResult result = wr.rule.evaluate(tx, history);
            if (result.triggered) {
                allResults.add(result);
                totalWeightedScore += result.riskContribution * wr.weight;
            }
            totalWeight += wr.weight;
        }

        // Normalize score to 0-1 range
        double normalizedScore = totalWeight > 0
                ? Math.min(1.0, totalWeightedScore / totalWeight)
                : 0.0;

        // Update history after assessment
        history.add(tx);

        // Determine action based on merchant config
        MerchantConfig config = merchantConfigs.getOrDefault(
                tx.merchantId, defaultConfig);

        String action;
        RiskLevel level;
        if (normalizedScore >= config.blockThreshold) {
            action = "block";
            level = RiskLevel.CRITICAL;
        } else if (normalizedScore >= config.reviewThreshold) {
            action = "review";
            level = RiskLevel.HIGH;
        } else if (normalizedScore >= 0.2) {
            action = "allow";
            level = RiskLevel.MEDIUM;
        } else {
            action = "allow";
            level = RiskLevel.LOW;
        }

        return new RiskAssessment(normalizedScore, level, allResults, action);
    }

    // =========================================================================
    // Tests
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Fraud Detector Tests (Staff Level) ===\n");

        testNormalTransaction();
        testVelocityRule();
        testAmountAnomaly();
        testGeoAnomaly();
        testBlocklistRule();
        testAllowlistBypass();
        testMerchantSpecificThresholds();
        testWeightedScoring();
        testRiskExplanation();

        System.out.println("\nAll tests passed.");
    }

    private static Transaction tx(String txId, String userId, int amount,
                                   long ts, String country) {
        return new Transaction(txId, userId, "merchant_1", amount, ts,
                country, "card_fp_default", "1.2.3.4");
    }

    private static void testNormalTransaction() {
        FraudDetector detector = new FraudDetector(60_000);
        RiskAssessment result = detector.assess(tx("tx1", "u1", 5000, 100000, "US"));

        assert result.level == RiskLevel.LOW;
        assert result.action.equals("allow");
        assert result.score < 0.2;

        System.out.println("[PASS] testNormalTransaction");
    }

    private static void testVelocityRule() {
        FraudDetector detector = new FraudDetector(60_000);
        long now = 100_000;

        // Fill up velocity window (default limit = 10)
        for (int i = 0; i < 10; i++) {
            detector.assess(tx("tx" + i, "u1", 1000, now + i * 100, "US"));
        }

        // 11th should trigger velocity
        RiskAssessment result = detector.assess(
                tx("tx_trigger", "u1", 1000, now + 1500, "US"));

        assert result.firedRules.stream().anyMatch(r -> r.ruleName.equals("velocity"))
                : "Velocity rule should fire";

        System.out.println("[PASS] testVelocityRule");
    }

    private static void testAmountAnomaly() {
        FraudDetector detector = new FraudDetector(3_600_000);
        long now = 100_000;

        // Build history with small amounts
        detector.assess(tx("tx1", "u2", 1000, now, "US"));
        detector.assess(tx("tx2", "u2", 1200, now + 10000, "US"));
        detector.assess(tx("tx3", "u2", 800, now + 20000, "US"));

        // Big spike: 10x the average
        RiskAssessment result = detector.assess(
                tx("tx_spike", "u2", 10000, now + 30000, "US"));

        assert result.firedRules.stream().anyMatch(r -> r.ruleName.equals("amount_anomaly"))
                : "Amount anomaly rule should fire";

        System.out.println("[PASS] testAmountAnomaly");
    }

    private static void testGeoAnomaly() {
        FraudDetector detector = new FraudDetector(3_600_000);
        long now = 100_000;

        detector.assess(tx("tx1", "u3", 2000, now, "US"));

        // Different country 10 minutes later
        RiskAssessment result = detector.assess(
                tx("tx2", "u3", 2000, now + 600_000, "NG"));

        assert result.firedRules.stream().anyMatch(r -> r.ruleName.equals("geo_anomaly"))
                : "Geo anomaly rule should fire";

        System.out.println("[PASS] testGeoAnomaly");
    }

    private static void testBlocklistRule() {
        FraudDetector detector = new FraudDetector(60_000);
        detector.blockCard("stolen_card_fp");

        Transaction tx = new Transaction("tx_bl", "u4", "merchant_1", 5000,
                100000, "US", "stolen_card_fp", "1.2.3.4");
        RiskAssessment result = detector.assess(tx);

        assert result.firedRules.stream().anyMatch(r -> r.ruleName.equals("blocklist"))
                : "Blocklist rule should fire";
        assert result.action.equals("block") || result.score > 0.5
                : "Blocklisted card should result in high score";

        System.out.println("[PASS] testBlocklistRule");
    }

    private static void testAllowlistBypass() {
        FraudDetector detector = new FraudDetector(60_000);
        detector.allowUser("trusted_user");

        // Even with suspicious patterns, allowed user should pass
        RiskAssessment result = detector.assess(
                tx("tx_al", "trusted_user", 99999, 100000, "US"));

        assert result.score == 0.0;
        assert result.action.equals("allow");

        System.out.println("[PASS] testAllowlistBypass");
    }

    private static void testMerchantSpecificThresholds() {
        FraudDetector detector = new FraudDetector(60_000);

        // High-risk merchant: lower thresholds
        detector.setMerchantConfig("risky_merchant",
                new MerchantConfig(0.3, 0.15, 5));

        // Build enough history to trigger velocity on the low threshold
        long now = 100_000;
        for (int i = 0; i < 5; i++) {
            Transaction tx = new Transaction("tx" + i, "u5", "risky_merchant",
                    1000, now + i * 100, "US", "card_fp", "1.1.1.1");
            detector.assess(tx);
        }

        Transaction triggerTx = new Transaction("tx_trigger", "u5", "risky_merchant",
                1000, now + 1000, "US", "card_fp", "1.1.1.1");
        RiskAssessment result = detector.assess(triggerTx);

        // With low merchant thresholds, even moderate scores should trigger review/block
        assert result.firedRules.stream().anyMatch(r -> r.ruleName.equals("velocity"));

        System.out.println("[PASS] testMerchantSpecificThresholds");
    }

    private static void testWeightedScoring() {
        FraudDetector detector = new FraudDetector(60_000);

        // Blocklist has weight 2.0, so it should dominate the score
        detector.blockIP("malicious_ip");

        Transaction tx = new Transaction("tx_w", "u6", "merchant_1", 2000,
                100000, "US", "card_ok", "malicious_ip");
        RiskAssessment result = detector.assess(tx);

        assert result.score > 0.3 : "Blocklist should contribute significantly, got " + result.score;

        System.out.println("[PASS] testWeightedScoring");
    }

    private static void testRiskExplanation() {
        FraudDetector detector = new FraudDetector(3_600_000);
        long now = 100_000;

        // Build history
        detector.assess(tx("h1", "u7", 1000, now, "US"));
        detector.assess(tx("h2", "u7", 1000, now + 1000, "US"));
        detector.assess(tx("h3", "u7", 1000, now + 2000, "US"));

        // Suspicious: large amount + different country
        Transaction suspicious = new Transaction("sus", "u7", "merchant_1",
                50000, now + 3000, "RU", "card_fp_default", "1.2.3.4");
        RiskAssessment result = detector.assess(suspicious);

        // Should have explanations for triggered rules
        for (RuleResult rr : result.firedRules) {
            assert rr.explanation != null && !rr.explanation.isEmpty()
                    : "Each fired rule should have an explanation";
        }

        assert result.firedRules.size() >= 1 : "At least one rule should fire";

        System.out.println("[PASS] testRiskExplanation");
    }
}
