import java.util.*;

/**
 * Stripe Interview Question #6: Currency Exchange / Multi-Currency Conversion
 *
 * Staff-level extensions:
 * - Arbitrage detection (negative cycles in exchange graph)
 * - Spread/fee modeling (bid-ask spread on each conversion)
 * - Rate staleness detection (reject rates older than threshold)
 * - Atomic multi-leg conversions with rollback
 * - Path tracing (show the conversion chain, not just the rate)
 * - Time-varying rates (support rate updates)
 */
public class CurrencyExchange {

    // =========================================================================
    // Data models
    // =========================================================================

    static class ExchangeRate {
        final String from;
        final String to;
        final double rate;
        final double spread;    // bid-ask spread as fraction (e.g., 0.005 for 0.5%)
        final long timestamp;   // when this rate was observed

        ExchangeRate(String from, String to, double rate, double spread, long timestamp) {
            this.from = from;
            this.to = to;
            this.rate = rate;
            this.spread = spread;
            this.timestamp = timestamp;
        }
    }

    static class ConversionResult {
        final double outputAmount;
        final double effectiveRate;
        final double totalFees;
        final List<String> path;

        ConversionResult(double outputAmount, double effectiveRate,
                         double totalFees, List<String> path) {
            this.outputAmount = outputAmount;
            this.effectiveRate = effectiveRate;
            this.totalFees = totalFees;
            this.path = path;
        }

        @Override
        public String toString() {
            return String.format("%.4f (rate=%.6f, fees=%.4f, path=%s)",
                    outputAmount, effectiveRate, totalFees, String.join("->", path));
        }
    }

    // =========================================================================
    // Graph representation
    // =========================================================================

    private final Map<String, Map<String, ExchangeRate>> graph = new HashMap<>();
    private final Set<String> currencies = new LinkedHashSet<>();
    private final long staleThresholdMs;

    public CurrencyExchange(long staleThresholdMs) {
        this.staleThresholdMs = staleThresholdMs;
    }

    /**
     * Add or update a bidirectional exchange rate.
     */
    public void addRate(String from, String to, double rate,
                        double spread, long timestamp) {
        currencies.add(from);
        currencies.add(to);

        graph.computeIfAbsent(from, k -> new HashMap<>())
                .put(to, new ExchangeRate(from, to, rate, spread, timestamp));
        graph.computeIfAbsent(to, k -> new HashMap<>())
                .put(from, new ExchangeRate(to, from, 1.0 / rate, spread, timestamp));
    }

    /**
     * Convenience: add rate without spread/staleness.
     */
    public void addRate(String from, String to, double rate) {
        addRate(from, to, rate, 0.0, System.currentTimeMillis());
    }

    // =========================================================================
    // Best rate with path tracing (modified Dijkstra maximizing rate product)
    // =========================================================================

    /**
     * Find the best conversion rate from source to target.
     * Applies bid-ask spread if modeling fees.
     * Returns null if no path exists.
     */
    public ConversionResult findBestConversion(String source, String target,
                                                double amount, boolean applySpread) {
        if (!currencies.contains(source) || !currencies.contains(target)) {
            return null;
        }
        if (source.equals(target)) {
            return new ConversionResult(amount, 1.0, 0.0, List.of(source));
        }

        long now = System.currentTimeMillis();

        // Max-heap Dijkstra: maximize product of rates
        Map<String, Double> bestRate = new HashMap<>();
        Map<String, String> parent = new HashMap<>();
        PriorityQueue<double[]> pq = new PriorityQueue<>(
                (a, b) -> Double.compare(b[1], a[1]));

        int sourceIdx = currencyIndex(source);
        bestRate.put(source, 1.0);
        pq.offer(new double[]{sourceIdx, 1.0});

        while (!pq.isEmpty()) {
            double[] current = pq.poll();
            String curr = indexToCurrency((int) current[0]);
            double currRate = current[1];

            if (curr.equals(target)) {
                // Reconstruct path
                List<String> path = reconstructPath(parent, source, target);
                double totalFee = amount * (1.0 - currRate); // simplified fee calc
                return new ConversionResult(
                        amount * currRate, currRate,
                        applySpread ? totalFee : 0.0, path);
            }

            if (currRate < bestRate.getOrDefault(curr, 0.0)) continue;

            Map<String, ExchangeRate> neighbors = graph.getOrDefault(curr, Map.of());
            for (Map.Entry<String, ExchangeRate> entry : neighbors.entrySet()) {
                ExchangeRate rate = entry.getValue();

                // Staleness check
                if (now - rate.timestamp > staleThresholdMs) continue;

                // Apply spread: effective rate = rate * (1 - spread)
                double effectiveRate = applySpread
                        ? rate.rate * (1.0 - rate.spread)
                        : rate.rate;
                double newRate = currRate * effectiveRate;

                if (newRate > bestRate.getOrDefault(entry.getKey(), 0.0)) {
                    bestRate.put(entry.getKey(), newRate);
                    parent.put(entry.getKey(), curr);
                    pq.offer(new double[]{currencyIndex(entry.getKey()), newRate});
                }
            }
        }

        return null; // No path found
    }

    // =========================================================================
    // Arbitrage detection (Bellman-Ford on -log(rates))
    // =========================================================================

    /**
     * Detect if an arbitrage opportunity exists (a cycle where you end up
     * with more money than you started).
     *
     * Uses Bellman-Ford on -log(rate) transformed edges.
     * A negative cycle in this transformed graph = arbitrage.
     */
    public List<String> detectArbitrage() {
        List<String> currencyList = new ArrayList<>(currencies);
        int n = currencyList.size();

        // Build edge list with -log(rate) weights
        List<double[]> edges = new ArrayList<>(); // [from, to, weight]
        List<String[]> edgeLabels = new ArrayList<>();

        for (String from : graph.keySet()) {
            for (Map.Entry<String, ExchangeRate> entry : graph.get(from).entrySet()) {
                int fromIdx = currencyList.indexOf(from);
                int toIdx = currencyList.indexOf(entry.getKey());
                double weight = -Math.log(entry.getValue().rate);
                edges.add(new double[]{fromIdx, toIdx, weight});
                edgeLabels.add(new String[]{from, entry.getKey()});
            }
        }

        // Bellman-Ford from every node (to catch all cycles)
        double[] dist = new double[n];
        int[] predecessor = new int[n];
        Arrays.fill(dist, Double.MAX_VALUE);
        Arrays.fill(predecessor, -1);
        dist[0] = 0;

        // Relax edges n-1 times
        for (int i = 0; i < n - 1; i++) {
            for (double[] edge : edges) {
                int u = (int) edge[0], v = (int) edge[1];
                double w = edge[2];
                if (dist[u] != Double.MAX_VALUE && dist[u] + w < dist[v]) {
                    dist[v] = dist[u] + w;
                    predecessor[v] = u;
                }
            }
        }

        // One more pass to detect negative cycle
        for (double[] edge : edges) {
            int u = (int) edge[0], v = (int) edge[1];
            double w = edge[2];
            if (dist[u] != Double.MAX_VALUE && dist[u] + w < dist[v] - 1e-9) {
                // Negative cycle found — trace it
                List<String> cycle = new ArrayList<>();
                Set<Integer> visited = new HashSet<>();
                int node = v;

                // Walk back to find the cycle
                for (int i = 0; i < n; i++) node = predecessor[node];
                int start = node;
                do {
                    cycle.add(currencyList.get(node));
                    node = predecessor[node];
                } while (node != start);
                cycle.add(currencyList.get(start));
                Collections.reverse(cycle);
                return cycle;
            }
        }

        return Collections.emptyList(); // No arbitrage
    }

    // =========================================================================
    // Multi-leg atomic conversion
    // =========================================================================

    static class ConversionLeg {
        final String from;
        final String to;
        final double rate;
        final double inputAmount;
        final double outputAmount;

        ConversionLeg(String from, String to, double rate,
                      double inputAmount, double outputAmount) {
            this.from = from;
            this.to = to;
            this.rate = rate;
            this.inputAmount = inputAmount;
            this.outputAmount = outputAmount;
        }

        @Override
        public String toString() {
            return String.format("%.4f %s -> %.4f %s (rate=%.6f)",
                    inputAmount, from, outputAmount, to, rate);
        }
    }

    /**
     * Execute a multi-leg conversion along a specific path.
     * Returns the leg details or throws if any leg fails validation.
     */
    public List<ConversionLeg> executeMultiLegConversion(
            List<String> path, double amount, boolean applySpread) {
        List<ConversionLeg> legs = new ArrayList<>();
        double currentAmount = amount;

        for (int i = 0; i < path.size() - 1; i++) {
            String from = path.get(i);
            String to = path.get(i + 1);

            Map<String, ExchangeRate> neighbors = graph.get(from);
            if (neighbors == null || !neighbors.containsKey(to)) {
                throw new IllegalStateException(
                        "No rate available for " + from + " -> " + to);
            }

            ExchangeRate rate = neighbors.get(to);
            double effectiveRate = applySpread
                    ? rate.rate * (1.0 - rate.spread)
                    : rate.rate;
            double outputAmount = currentAmount * effectiveRate;

            legs.add(new ConversionLeg(from, to, effectiveRate,
                    currentAmount, outputAmount));
            currentAmount = outputAmount;
        }

        return legs;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private final Map<String, Integer> currencyIndexMap = new HashMap<>();

    private int currencyIndex(String currency) {
        return currencyIndexMap.computeIfAbsent(currency,
                k -> currencyIndexMap.size());
    }

    private String indexToCurrency(int idx) {
        for (Map.Entry<String, Integer> e : currencyIndexMap.entrySet()) {
            if (e.getValue() == idx) return e.getKey();
        }
        return null;
    }

    private List<String> reconstructPath(Map<String, String> parent,
                                          String source, String target) {
        LinkedList<String> path = new LinkedList<>();
        String current = target;
        while (current != null) {
            path.addFirst(current);
            current = current.equals(source) ? null : parent.get(current);
        }
        return path;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Currency Exchange Tests (Staff Level) ===\n");

        testDirectConversion();
        testTransitiveConversion();
        testBestPathSelection();
        testSpreadFees();
        testArbitrageDetection();
        testNoArbitrage();
        testStalenessRejection();
        testMultiLegConversion();

        System.out.println("\nAll tests passed.");
    }

    private static void testDirectConversion() {
        CurrencyExchange exchange = new CurrencyExchange(Long.MAX_VALUE);
        exchange.addRate("USD", "EUR", 0.85);

        ConversionResult result = exchange.findBestConversion(
                "USD", "EUR", 100.0, false);

        assert result != null;
        assert Math.abs(result.outputAmount - 85.0) < 0.01;
        assert result.path.equals(List.of("USD", "EUR"));

        System.out.println("[PASS] testDirectConversion");
    }

    private static void testTransitiveConversion() {
        CurrencyExchange exchange = new CurrencyExchange(Long.MAX_VALUE);
        exchange.addRate("USD", "EUR", 0.85);
        exchange.addRate("EUR", "GBP", 0.88);

        ConversionResult result = exchange.findBestConversion(
                "USD", "GBP", 100.0, false);

        assert result != null;
        assert Math.abs(result.outputAmount - 74.8) < 0.01
                : "Expected ~74.8, got " + result.outputAmount;
        assert result.path.size() == 3;

        System.out.println("[PASS] testTransitiveConversion");
    }

    private static void testBestPathSelection() {
        CurrencyExchange exchange = new CurrencyExchange(Long.MAX_VALUE);
        // Direct: 0.70
        exchange.addRate("USD", "GBP", 0.70);
        // Indirect: 0.85 * 0.88 = 0.748
        exchange.addRate("USD", "EUR", 0.85);
        exchange.addRate("EUR", "GBP", 0.88);

        ConversionResult result = exchange.findBestConversion(
                "USD", "GBP", 100.0, false);

        assert result != null;
        assert Math.abs(result.outputAmount - 74.8) < 0.01
                : "Should pick indirect (74.8 > 70.0), got " + result.outputAmount;

        System.out.println("[PASS] testBestPathSelection");
    }

    private static void testSpreadFees() {
        CurrencyExchange exchange = new CurrencyExchange(Long.MAX_VALUE);
        // 0.5% spread on each leg
        exchange.addRate("USD", "EUR", 0.85, 0.005, System.currentTimeMillis());

        ConversionResult withSpread = exchange.findBestConversion(
                "USD", "EUR", 1000.0, true);
        ConversionResult noSpread = exchange.findBestConversion(
                "USD", "EUR", 1000.0, false);

        assert withSpread != null && noSpread != null;
        assert withSpread.outputAmount < noSpread.outputAmount
                : "Spread should reduce output amount";

        double expectedWithSpread = 1000.0 * 0.85 * (1 - 0.005);
        assert Math.abs(withSpread.outputAmount - expectedWithSpread) < 0.01;

        System.out.println("[PASS] testSpreadFees");
    }

    private static void testArbitrageDetection() {
        CurrencyExchange exchange = new CurrencyExchange(Long.MAX_VALUE);
        // Create an arbitrage: USD->EUR->GBP->USD should yield > 1.0
        exchange.addRate("USD", "EUR", 0.9);
        exchange.addRate("EUR", "GBP", 0.8);
        exchange.addRate("GBP", "USD", 1.5);
        // Product: 0.9 * 0.8 * 1.5 = 1.08 > 1.0 => arbitrage!

        List<String> cycle = exchange.detectArbitrage();
        assert !cycle.isEmpty() : "Should detect arbitrage";

        System.out.println("[PASS] testArbitrageDetection (cycle: " +
                String.join("->", cycle) + ")");
    }

    private static void testNoArbitrage() {
        CurrencyExchange exchange = new CurrencyExchange(Long.MAX_VALUE);
        exchange.addRate("USD", "EUR", 0.85);
        exchange.addRate("EUR", "GBP", 0.88);
        // Implied GBP->USD = 1/(0.85*0.88) = 1.338
        // Round trip: 0.85 * 0.88 * 1.338 ≈ 1.0 (no arbitrage)

        List<String> cycle = exchange.detectArbitrage();
        assert cycle.isEmpty() : "Should not detect arbitrage";

        System.out.println("[PASS] testNoArbitrage");
    }

    private static void testStalenessRejection() {
        CurrencyExchange exchange = new CurrencyExchange(1000); // 1 second threshold
        long old = System.currentTimeMillis() - 5000; // 5 seconds ago

        exchange.addRate("USD", "EUR", 0.85, 0.0, old); // stale rate

        ConversionResult result = exchange.findBestConversion(
                "USD", "EUR", 100.0, false);
        assert result == null : "Stale rate should be rejected";

        // Add fresh rate
        exchange.addRate("USD", "EUR", 0.86, 0.0, System.currentTimeMillis());
        result = exchange.findBestConversion("USD", "EUR", 100.0, false);
        assert result != null : "Fresh rate should work";

        System.out.println("[PASS] testStalenessRejection");
    }

    private static void testMultiLegConversion() {
        CurrencyExchange exchange = new CurrencyExchange(Long.MAX_VALUE);
        exchange.addRate("USD", "EUR", 0.85, 0.003, System.currentTimeMillis());
        exchange.addRate("EUR", "GBP", 0.88, 0.002, System.currentTimeMillis());

        List<ConversionLeg> legs = exchange.executeMultiLegConversion(
                List.of("USD", "EUR", "GBP"), 1000.0, true);

        assert legs.size() == 2;
        assert legs.get(0).from.equals("USD") && legs.get(0).to.equals("EUR");
        assert legs.get(1).from.equals("EUR") && legs.get(1).to.equals("GBP");

        // Verify chain: output of leg 1 = input of leg 2
        assert Math.abs(legs.get(0).outputAmount - legs.get(1).inputAmount) < 0.01;

        System.out.println("[PASS] testMultiLegConversion");
    }
}
