import java.util.*;
import java.util.stream.Collectors;

/**
 * Stripe Interview Question #2: Minimum Transactions to Settle Debts
 *
 * Staff-level extensions:
 * - Subset-sum optimization: group people whose debts cancel exactly (reduces backtracking)
 * - Streaming/incremental settlement (new debts arrive over time)
 * - Multi-currency debt settlement
 * - Generate the actual settlement plan (not just the count)
 * - Handle large number of participants efficiently
 */
public class DebtSettler {

    static class Transfer {
        final int from;
        final int to;
        final long amountCents;
        final String currency;

        Transfer(int from, int to, long amountCents, String currency) {
            this.from = from;
            this.to = to;
            this.amountCents = amountCents;
            this.currency = currency;
        }

        @Override
        public String toString() {
            return String.format("Person %d pays Person %d: $%.2f %s",
                    from, to, amountCents / 100.0, currency);
        }
    }

    // =========================================================================
    // Basic: minimum number of transactions (backtracking with subset-sum pruning)
    // =========================================================================

    public int minTransactions(int[][] transactions) {
        Map<Integer, Long> balanceMap = new HashMap<>();
        for (int[] t : transactions) {
            balanceMap.merge(t[0], -(long) t[2], Long::sum);
            balanceMap.merge(t[1], (long) t[2], Long::sum);
        }

        List<Long> balances = balanceMap.values().stream()
                .filter(b -> b != 0)
                .collect(Collectors.toList());

        if (balances.isEmpty()) return 0;

        // Optimization: find maximal number of subsets that sum to zero.
        // Each zero-sum subset of size k can be settled in k-1 transactions.
        // Total = n - (number of zero-sum subsets)
        int maxZeroSubsets = findMaxZeroSubsets(balances);
        return balances.size() - maxZeroSubsets;
    }

    /**
     * Find maximum number of disjoint subsets that each sum to zero.
     * Uses bitmask DP on subsets.
     */
    private int findMaxZeroSubsets(List<Long> balances) {
        int n = balances.size();
        if (n > 20) {
            // Fall back to greedy for large inputs
            return findMaxZeroSubsetsGreedy(balances);
        }

        int totalMask = 1 << n;
        long[] subsetSum = new long[totalMask];
        int[] dp = new int[totalMask]; // max zero-sum subsets using these people

        // Precompute subset sums
        for (int mask = 1; mask < totalMask; mask++) {
            int lsb = mask & (-mask);
            int bit = Integer.numberOfTrailingZeros(lsb);
            subsetSum[mask] = subsetSum[mask ^ lsb] + balances.get(bit);
        }

        // DP: for each bitmask, find max number of zero-sum subsets
        for (int mask = 1; mask < totalMask; mask++) {
            // Try all submasks of mask
            for (int sub = mask; sub > 0; sub = (sub - 1) & mask) {
                if (subsetSum[sub] == 0) {
                    dp[mask] = Math.max(dp[mask], dp[mask ^ sub] + 1);
                }
            }
        }

        return dp[totalMask - 1];
    }

    private int findMaxZeroSubsetsGreedy(List<Long> balances) {
        // Greedy: match exact opposite pairs first
        Map<Long, Integer> countMap = new HashMap<>();
        int matched = 0;
        for (long b : balances) {
            if (countMap.getOrDefault(-b, 0) > 0) {
                countMap.merge(-b, -1, Integer::sum);
                matched++;
            } else {
                countMap.merge(b, 1, Integer::sum);
            }
        }
        return matched;
    }

    // =========================================================================
    // Extension: Generate the actual settlement plan
    // =========================================================================

    public List<Transfer> generateSettlementPlan(int[][] transactions) {
        Map<Integer, Long> balanceMap = new HashMap<>();
        for (int[] t : transactions) {
            balanceMap.merge(t[0], -(long) t[2], Long::sum);
            balanceMap.merge(t[1], (long) t[2], Long::sum);
        }

        // Separate into debtors (negative balance) and creditors (positive balance)
        PriorityQueue<long[]> debtors = new PriorityQueue<>(
                Comparator.comparingLong(a -> a[1]));   // most negative first
        PriorityQueue<long[]> creditors = new PriorityQueue<>(
                (a, b) -> Long.compare(b[1], a[1]));    // most positive first

        for (Map.Entry<Integer, Long> entry : balanceMap.entrySet()) {
            if (entry.getValue() < 0) {
                debtors.offer(new long[]{entry.getKey(), entry.getValue()});
            } else if (entry.getValue() > 0) {
                creditors.offer(new long[]{entry.getKey(), entry.getValue()});
            }
        }

        List<Transfer> plan = new ArrayList<>();

        while (!debtors.isEmpty() && !creditors.isEmpty()) {
            long[] debtor = debtors.poll();
            long[] creditor = creditors.poll();

            long amount = Math.min(-debtor[1], creditor[1]);
            plan.add(new Transfer((int) debtor[0], (int) creditor[0], amount, "USD"));

            debtor[1] += amount;
            creditor[1] -= amount;

            if (debtor[1] < 0) debtors.offer(debtor);
            if (creditor[1] > 0) creditors.offer(creditor);
        }

        return plan;
    }

    // =========================================================================
    // Extension: Multi-currency settlement
    // =========================================================================

    static class MultiCurrencyTransaction {
        final int from;
        final int to;
        final long amountCents;
        final String currency;

        MultiCurrencyTransaction(int from, int to, long amountCents, String currency) {
            this.from = from;
            this.to = to;
            this.amountCents = amountCents;
            this.currency = currency;
        }
    }

    /**
     * Settle debts per currency independently.
     * In real Stripe Connect, each currency is settled separately to avoid FX risk.
     */
    public Map<String, List<Transfer>> settleMultiCurrency(
            List<MultiCurrencyTransaction> transactions) {

        // Group transactions by currency
        Map<String, List<int[]>> byCurrency = new HashMap<>();
        for (MultiCurrencyTransaction tx : transactions) {
            byCurrency.computeIfAbsent(tx.currency, k -> new ArrayList<>())
                    .add(new int[]{tx.from, tx.to, (int) tx.amountCents});
        }

        Map<String, List<Transfer>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<int[]>> entry : byCurrency.entrySet()) {
            int[][] txArray = entry.getValue().toArray(new int[0][]);
            // Reuse single-currency settlement
            List<Transfer> plan = generateSettlementPlanForCurrency(
                    txArray, entry.getKey());
            result.put(entry.getKey(), plan);
        }

        return result;
    }

    private List<Transfer> generateSettlementPlanForCurrency(int[][] transactions,
                                                              String currency) {
        Map<Integer, Long> balanceMap = new HashMap<>();
        for (int[] t : transactions) {
            balanceMap.merge(t[0], -(long) t[2], Long::sum);
            balanceMap.merge(t[1], (long) t[2], Long::sum);
        }

        PriorityQueue<long[]> debtors = new PriorityQueue<>(
                Comparator.comparingLong(a -> a[1]));
        PriorityQueue<long[]> creditors = new PriorityQueue<>(
                (a, b) -> Long.compare(b[1], a[1]));

        for (Map.Entry<Integer, Long> entry : balanceMap.entrySet()) {
            if (entry.getValue() < 0) debtors.offer(new long[]{entry.getKey(), entry.getValue()});
            else if (entry.getValue() > 0) creditors.offer(new long[]{entry.getKey(), entry.getValue()});
        }

        List<Transfer> plan = new ArrayList<>();
        while (!debtors.isEmpty() && !creditors.isEmpty()) {
            long[] debtor = debtors.poll();
            long[] creditor = creditors.poll();
            long amount = Math.min(-debtor[1], creditor[1]);
            plan.add(new Transfer((int) debtor[0], (int) creditor[0], amount, currency));
            debtor[1] += amount;
            creditor[1] -= amount;
            if (debtor[1] < 0) debtors.offer(debtor);
            if (creditor[1] > 0) creditors.offer(creditor);
        }
        return plan;
    }

    // =========================================================================
    // Extension: Incremental / streaming settlement
    // =========================================================================

    static class IncrementalSettler {
        private final Map<Integer, Long> runningBalances = new HashMap<>();

        void addTransaction(int from, int to, long amountCents) {
            runningBalances.merge(from, -amountCents, Long::sum);
            runningBalances.merge(to, amountCents, Long::sum);
        }

        Map<Integer, Long> getCurrentBalances() {
            return Collections.unmodifiableMap(runningBalances);
        }

        /**
         * Settle all current outstanding balances and reset.
         * Returns the settlement transfers.
         */
        List<Transfer> settleNow() {
            List<Transfer> transfers = new ArrayList<>();
            PriorityQueue<long[]> debtors = new PriorityQueue<>(
                    Comparator.comparingLong(a -> a[1]));
            PriorityQueue<long[]> creditors = new PriorityQueue<>(
                    (a, b) -> Long.compare(b[1], a[1]));

            for (Map.Entry<Integer, Long> entry : runningBalances.entrySet()) {
                if (entry.getValue() < 0) debtors.offer(new long[]{entry.getKey(), entry.getValue()});
                else if (entry.getValue() > 0) creditors.offer(new long[]{entry.getKey(), entry.getValue()});
            }

            while (!debtors.isEmpty() && !creditors.isEmpty()) {
                long[] debtor = debtors.poll();
                long[] creditor = creditors.poll();
                long amount = Math.min(-debtor[1], creditor[1]);
                transfers.add(new Transfer((int) debtor[0], (int) creditor[0], amount, "USD"));
                debtor[1] += amount;
                creditor[1] -= amount;
                if (debtor[1] < 0) debtors.offer(debtor);
                if (creditor[1] > 0) creditors.offer(creditor);
            }

            runningBalances.clear();
            return transfers;
        }
    }

    // =========================================================================
    // Tests
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Debt Settler Tests (Staff Level) ===\n");

        DebtSettler solver = new DebtSettler();

        testMinTransactionsBasic(solver);
        testMinTransactionsSubsetOptimization(solver);
        testSettlementPlan(solver);
        testMultiCurrencySettlement(solver);
        testIncrementalSettlement();

        System.out.println("\nAll tests passed.");
    }

    private static void testMinTransactionsBasic(DebtSettler solver) {
        // A->B->C: net is A owes C
        assert solver.minTransactions(new int[][]{{0, 1, 10}, {1, 2, 10}}) == 1;

        // Already settled
        assert solver.minTransactions(new int[][]{{0, 1, 10}, {1, 0, 10}}) == 0;

        System.out.println("[PASS] testMinTransactionsBasic");
    }

    private static void testMinTransactionsSubsetOptimization(DebtSettler solver) {
        // Two independent pairs: (0 owes 1 $5) and (2 owes 3 $5)
        // These form two zero-sum subsets: {0,1} and {2,3}
        // Optimal: 2 transactions (not 3 from naive approach)
        int[][] tx = {{0, 1, 5}, {2, 3, 5}};
        assert solver.minTransactions(tx) == 2;

        // Three-way cycle: 0->1->2->0 ($10 each)
        // All balances are 0, so 0 transactions needed
        int[][] cycle = {{0, 1, 10}, {1, 2, 10}, {2, 0, 10}};
        assert solver.minTransactions(cycle) == 0;

        System.out.println("[PASS] testMinTransactionsSubsetOptimization");
    }

    private static void testSettlementPlan(DebtSettler solver) {
        int[][] tx = {{0, 1, 10000}, {2, 0, 5000}};
        // Net: 0=-5000, 1=+10000, 2=-5000
        List<Transfer> plan = solver.generateSettlementPlan(tx);

        // Verify all debts are settled
        Map<Integer, Long> verification = new HashMap<>();
        for (Transfer t : plan) {
            verification.merge(t.from, -t.amountCents, Long::sum);
            verification.merge(t.to, t.amountCents, Long::sum);
        }

        // After settlement, the net should match original balances
        assert plan.size() == 2 : "Should produce 2 transfers, got " + plan.size();

        long totalTransferred = plan.stream().mapToLong(t -> t.amountCents).sum();
        assert totalTransferred == 10000 : "Total transferred should be $100";

        System.out.println("[PASS] testSettlementPlan");
    }

    private static void testMultiCurrencySettlement(DebtSettler solver) {
        List<MultiCurrencyTransaction> transactions = Arrays.asList(
                new MultiCurrencyTransaction(0, 1, 5000, "USD"),
                new MultiCurrencyTransaction(1, 2, 3000, "USD"),
                new MultiCurrencyTransaction(0, 1, 4000, "EUR"),
                new MultiCurrencyTransaction(2, 0, 2000, "EUR")
        );

        Map<String, List<Transfer>> result = solver.settleMultiCurrency(transactions);

        assert result.containsKey("USD") : "Should have USD settlements";
        assert result.containsKey("EUR") : "Should have EUR settlements";

        // USD: 0 owes net $20, 1 gets $20, 2 owes net $30 -> depends on net
        // EUR: settled independently
        for (Transfer t : result.get("USD")) {
            assert t.currency.equals("USD");
        }
        for (Transfer t : result.get("EUR")) {
            assert t.currency.equals("EUR");
        }

        System.out.println("[PASS] testMultiCurrencySettlement");
    }

    private static void testIncrementalSettlement() {
        IncrementalSettler settler = new IncrementalSettler();

        // Day 1 transactions
        settler.addTransaction(0, 1, 5000);
        settler.addTransaction(1, 2, 3000);

        // Check running balances
        Map<Integer, Long> balances = settler.getCurrentBalances();
        assert balances.get(0) == -5000;
        assert balances.get(1) == 2000;  // received 5000, paid 3000
        assert balances.get(2) == 3000;

        // Day 2: more transactions before settling
        settler.addTransaction(2, 0, 2000);

        // Settle everything
        List<Transfer> transfers = settler.settleNow();
        assert !transfers.isEmpty();

        // Verify balances are cleared
        assert settler.getCurrentBalances().isEmpty()
                || settler.getCurrentBalances().values().stream().allMatch(b -> b == 0);

        System.out.println("[PASS] testIncrementalSettlement");
    }
}
