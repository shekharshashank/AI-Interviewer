import java.util.*;
import java.time.*;
import java.time.temporal.ChronoUnit;

/**
 * Stripe Interview Question #4: Subscription Billing / Proration Calculator
 *
 * Staff-level extensions:
 * - Multiple mid-cycle plan changes (upgrade, then downgrade in same cycle)
 * - Usage-based billing with tiered pricing
 * - Tax calculation on prorated amounts
 * - Coupon/discount application during proration
 * - Proper date-based calculation (not just day fractions)
 * - Generate full invoice with line items
 */
public class SubscriptionProration {

    // =========================================================================
    // Data models
    // =========================================================================

    static class Plan {
        final String id;
        final String name;
        final long priceInCents;
        final BillingInterval interval;

        Plan(String id, String name, long priceInCents, BillingInterval interval) {
            this.id = id;
            this.name = name;
            this.priceInCents = priceInCents;
            this.interval = interval;
        }
    }

    enum BillingInterval { MONTHLY, YEARLY }

    static class Coupon {
        final String id;
        final CouponType type;
        final long value; // percentage (0-100) or fixed amount in cents

        Coupon(String id, CouponType type, long value) {
            this.id = id;
            this.type = type;
            this.value = value;
        }

        enum CouponType { PERCENTAGE, FIXED_AMOUNT }
    }

    static class TaxRate {
        final String jurisdiction;
        final double rate; // e.g. 0.0875 for 8.75%

        TaxRate(String jurisdiction, double rate) {
            this.jurisdiction = jurisdiction;
            this.rate = rate;
        }
    }

    // =========================================================================
    // Invoice line items
    // =========================================================================

    static class InvoiceLineItem {
        final String description;
        final long amountCents; // negative for credits
        final LocalDate periodStart;
        final LocalDate periodEnd;

        InvoiceLineItem(String description, long amountCents,
                        LocalDate periodStart, LocalDate periodEnd) {
            this.description = description;
            this.amountCents = amountCents;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
        }

        @Override
        public String toString() {
            return String.format("  %-50s %s$%.2f  (%s to %s)",
                    description,
                    amountCents < 0 ? "-" : " ",
                    Math.abs(amountCents) / 100.0,
                    periodStart, periodEnd);
        }
    }

    static class Invoice {
        final List<InvoiceLineItem> lineItems = new ArrayList<>();
        long subtotalCents;
        long taxCents;
        long totalCents;
        long discountCents;

        void addLineItem(InvoiceLineItem item) {
            lineItems.add(item);
        }

        void compute(TaxRate tax, Coupon coupon) {
            subtotalCents = lineItems.stream().mapToLong(li -> li.amountCents).sum();

            // Apply discount
            discountCents = 0;
            if (coupon != null && subtotalCents > 0) {
                if (coupon.type == Coupon.CouponType.PERCENTAGE) {
                    discountCents = (subtotalCents * coupon.value) / 100;
                } else {
                    discountCents = Math.min(coupon.value, subtotalCents);
                }
            }

            long taxableAmount = subtotalCents - discountCents;
            taxCents = tax != null ? Math.round(taxableAmount * tax.rate) : 0;
            totalCents = taxableAmount + taxCents;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("Invoice:\n");
            for (InvoiceLineItem li : lineItems) {
                sb.append(li).append("\n");
            }
            sb.append(String.format("  %-50s  $%.2f\n", "Subtotal:", subtotalCents / 100.0));
            if (discountCents > 0) {
                sb.append(String.format("  %-50s -$%.2f\n", "Discount:", discountCents / 100.0));
            }
            if (taxCents > 0) {
                sb.append(String.format("  %-50s  $%.2f\n", "Tax:", taxCents / 100.0));
            }
            sb.append(String.format("  %-50s  $%.2f\n", "TOTAL:", totalCents / 100.0));
            return sb.toString();
        }
    }

    // =========================================================================
    // Core proration: single plan change
    // =========================================================================

    /**
     * Calculate proration for a plan change using actual dates.
     */
    public Invoice prorateChange(Plan oldPlan, Plan newPlan,
                                  LocalDate cycleStart, LocalDate cycleEnd,
                                  LocalDate changeDate,
                                  TaxRate tax, Coupon coupon) {
        if (changeDate.isBefore(cycleStart) || changeDate.isAfter(cycleEnd)) {
            throw new IllegalArgumentException("Change date must be within billing cycle");
        }

        long totalDays = ChronoUnit.DAYS.between(cycleStart, cycleEnd);
        long usedDays = ChronoUnit.DAYS.between(cycleStart, changeDate);
        long remainingDays = totalDays - usedDays;

        Invoice invoice = new Invoice();

        // Credit for unused portion of old plan
        long credit = (oldPlan.priceInCents * remainingDays) / totalDays;
        invoice.addLineItem(new InvoiceLineItem(
                "Credit: Unused time on " + oldPlan.name,
                -credit, changeDate, cycleEnd));

        // Charge for remaining portion of new plan
        long charge = (newPlan.priceInCents * remainingDays) / totalDays;
        invoice.addLineItem(new InvoiceLineItem(
                "Charge: Remaining time on " + newPlan.name,
                charge, changeDate, cycleEnd));

        invoice.compute(tax, coupon);
        return invoice;
    }

    // =========================================================================
    // Extension: Multiple plan changes in one cycle
    // =========================================================================

    static class PlanChange {
        final Plan plan;
        final LocalDate effectiveDate;

        PlanChange(Plan plan, LocalDate effectiveDate) {
            this.plan = plan;
            this.effectiveDate = effectiveDate;
        }
    }

    /**
     * Handle multiple plan changes within a single billing cycle.
     * Example: Started on Basic, upgraded to Pro on day 10, downgraded to Basic on day 20.
     */
    public Invoice prorateMultipleChanges(Plan initialPlan,
                                           List<PlanChange> changes,
                                           LocalDate cycleStart,
                                           LocalDate cycleEnd,
                                           TaxRate tax, Coupon coupon) {
        // Sort changes by date
        List<PlanChange> sorted = new ArrayList<>(changes);
        sorted.sort(Comparator.comparing(c -> c.effectiveDate));

        Invoice invoice = new Invoice();
        long totalDays = ChronoUnit.DAYS.between(cycleStart, cycleEnd);

        // Build timeline: [(start, end, plan)]
        List<Object[]> segments = new ArrayList<>();
        Plan currentPlan = initialPlan;
        LocalDate segmentStart = cycleStart;

        for (PlanChange change : sorted) {
            if (change.effectiveDate.isAfter(segmentStart)) {
                segments.add(new Object[]{segmentStart, change.effectiveDate, currentPlan});
            }
            segmentStart = change.effectiveDate;
            currentPlan = change.plan;
        }
        // Final segment
        if (segmentStart.isBefore(cycleEnd)) {
            segments.add(new Object[]{segmentStart, cycleEnd, currentPlan});
        }

        // Generate line items for each segment
        for (Object[] segment : segments) {
            LocalDate start = (LocalDate) segment[0];
            LocalDate end = (LocalDate) segment[1];
            Plan plan = (Plan) segment[2];

            long segmentDays = ChronoUnit.DAYS.between(start, end);
            long amount = (plan.priceInCents * segmentDays) / totalDays;

            invoice.addLineItem(new InvoiceLineItem(
                    plan.name + " (" + segmentDays + " days)",
                    amount, start, end));
        }

        // Subtract what was originally charged for the full cycle
        invoice.addLineItem(new InvoiceLineItem(
                "Credit: Original " + initialPlan.name + " charge",
                -initialPlan.priceInCents, cycleStart, cycleEnd));

        invoice.compute(tax, coupon);
        return invoice;
    }

    // =========================================================================
    // Extension: Usage-based billing with tiers
    // =========================================================================

    static class UsageTier {
        final long upToUnits;   // Long.MAX_VALUE for unlimited
        final long centsPerUnit;

        UsageTier(long upToUnits, long centsPerUnit) {
            this.upToUnits = upToUnits;
            this.centsPerUnit = centsPerUnit;
        }
    }

    /**
     * Calculate cost for usage-based billing with graduated tiers.
     * Example: first 1000 API calls at $0.01, next 9000 at $0.005, rest at $0.001
     */
    public long calculateTieredUsage(List<UsageTier> tiers, long totalUnits) {
        long totalCost = 0;
        long unitsRemaining = totalUnits;
        long previousTierEnd = 0;

        for (UsageTier tier : tiers) {
            if (unitsRemaining <= 0) break;

            long tierCapacity = tier.upToUnits == Long.MAX_VALUE
                    ? unitsRemaining
                    : tier.upToUnits - previousTierEnd;
            long unitsInTier = Math.min(unitsRemaining, tierCapacity);

            totalCost += unitsInTier * tier.centsPerUnit;
            unitsRemaining -= unitsInTier;
            previousTierEnd = tier.upToUnits;
        }

        return totalCost;
    }

    /**
     * Prorate usage-based charges when changing plans mid-cycle.
     * Usage accrued before the change is billed on the old plan's tiers.
     * Usage after the change is billed on the new plan's tiers.
     */
    public long[] prorateUsageBilling(List<UsageTier> oldTiers,
                                       List<UsageTier> newTiers,
                                       long usageBeforeChange,
                                       long usageAfterChange) {
        long oldCost = calculateTieredUsage(oldTiers, usageBeforeChange);
        long newCost = calculateTieredUsage(newTiers, usageAfterChange);
        return new long[]{oldCost, newCost, oldCost + newCost};
    }

    // =========================================================================
    // Tests
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== Subscription Proration Tests (Staff Level) ===\n");

        SubscriptionProration calc = new SubscriptionProration();

        testSimpleUpgrade(calc);
        testDowngradeWithRefund(calc);
        testWithTaxAndDiscount(calc);
        testMultiplePlanChanges(calc);
        testTieredUsageBilling(calc);
        testProratedUsageBilling(calc);
        testEdgeCaseChangeOnFirstDay(calc);
        testEdgeCaseChangeOnLastDay(calc);

        System.out.println("\nAll tests passed.");
    }

    private static void testSimpleUpgrade(SubscriptionProration calc) {
        Plan basic = new Plan("basic", "Basic", 3000, BillingInterval.MONTHLY);
        Plan pro = new Plan("pro", "Pro", 6000, BillingInterval.MONTHLY);

        Invoice invoice = calc.prorateChange(basic, pro,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 1, 16), null, null);

        // 15 remaining out of 30 days
        assert invoice.lineItems.size() == 2;
        assert invoice.lineItems.get(0).amountCents < 0 : "First item should be credit";
        assert invoice.lineItems.get(1).amountCents > 0 : "Second item should be charge";
        assert invoice.totalCents > 0 : "Upgrade should result in net charge";

        System.out.println("[PASS] testSimpleUpgrade");
    }

    private static void testDowngradeWithRefund(SubscriptionProration calc) {
        Plan pro = new Plan("pro", "Pro", 6000, BillingInterval.MONTHLY);
        Plan basic = new Plan("basic", "Basic", 3000, BillingInterval.MONTHLY);

        Invoice invoice = calc.prorateChange(pro, basic,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 1, 16), null, null);

        assert invoice.totalCents < 0 : "Downgrade should result in credit";

        System.out.println("[PASS] testDowngradeWithRefund");
    }

    private static void testWithTaxAndDiscount(SubscriptionProration calc) {
        Plan basic = new Plan("basic", "Basic", 3000, BillingInterval.MONTHLY);
        Plan pro = new Plan("pro", "Pro", 6000, BillingInterval.MONTHLY);
        TaxRate tax = new TaxRate("CA", 0.0875);
        Coupon coupon = new Coupon("SAVE20", Coupon.CouponType.PERCENTAGE, 20);

        Invoice invoice = calc.prorateChange(basic, pro,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 1, 16), tax, coupon);

        assert invoice.taxCents > 0 : "Should have tax applied";
        assert invoice.discountCents > 0 : "Should have discount applied";
        assert invoice.totalCents < invoice.subtotalCents : "Total should be less after discount";

        System.out.println("[PASS] testWithTaxAndDiscount");
    }

    private static void testMultiplePlanChanges(SubscriptionProration calc) {
        Plan basic = new Plan("basic", "Basic", 3000, BillingInterval.MONTHLY);
        Plan pro = new Plan("pro", "Pro", 6000, BillingInterval.MONTHLY);
        Plan enterprise = new Plan("ent", "Enterprise", 12000, BillingInterval.MONTHLY);

        LocalDate cycleStart = LocalDate.of(2024, 1, 1);
        LocalDate cycleEnd = LocalDate.of(2024, 1, 31);

        List<PlanChange> changes = Arrays.asList(
                new PlanChange(pro, LocalDate.of(2024, 1, 11)),       // upgrade on day 10
                new PlanChange(enterprise, LocalDate.of(2024, 1, 21)) // upgrade again on day 20
        );

        Invoice invoice = calc.prorateMultipleChanges(basic, changes,
                cycleStart, cycleEnd, null, null);

        // Should have: Basic(10d) + Pro(10d) + Enterprise(11d) - Basic(full credit)
        assert invoice.lineItems.size() == 4 : "Expected 4 line items, got " + invoice.lineItems.size();

        // Net should be positive (upgraded twice)
        assert invoice.totalCents > 0 : "Multiple upgrades should be net positive";

        System.out.println("[PASS] testMultiplePlanChanges");
    }

    private static void testTieredUsageBilling(SubscriptionProration calc) {
        List<UsageTier> tiers = Arrays.asList(
                new UsageTier(1000, 10),            // first 1000: $0.10/unit
                new UsageTier(10000, 5),             // next 9000: $0.05/unit
                new UsageTier(Long.MAX_VALUE, 1)     // rest: $0.01/unit
        );

        // 500 units: all in tier 1
        assert calc.calculateTieredUsage(tiers, 500) == 5000; // 500 * 10

        // 1500 units: 1000 in tier 1 + 500 in tier 2
        assert calc.calculateTieredUsage(tiers, 1500) == 12500; // 10000 + 2500

        // 15000 units: 1000*10 + 9000*5 + 5000*1
        long cost = calc.calculateTieredUsage(tiers, 15000);
        assert cost == 60000 : "Expected 60000, got " + cost; // 10000 + 45000 + 5000

        System.out.println("[PASS] testTieredUsageBilling");
    }

    private static void testProratedUsageBilling(SubscriptionProration calc) {
        List<UsageTier> oldTiers = Arrays.asList(
                new UsageTier(1000, 10),
                new UsageTier(Long.MAX_VALUE, 5)
        );
        List<UsageTier> newTiers = Arrays.asList(
                new UsageTier(5000, 3),
                new UsageTier(Long.MAX_VALUE, 1)
        );

        // 800 units before change, 2000 units after
        long[] result = calc.prorateUsageBilling(oldTiers, newTiers, 800, 2000);

        assert result[0] == 8000 : "Old plan: 800 * 10 = 8000";
        assert result[1] == 6000 : "New plan: 2000 * 3 = 6000";
        assert result[2] == 14000 : "Total: 14000";

        System.out.println("[PASS] testProratedUsageBilling");
    }

    private static void testEdgeCaseChangeOnFirstDay(SubscriptionProration calc) {
        Plan basic = new Plan("basic", "Basic", 3000, BillingInterval.MONTHLY);
        Plan pro = new Plan("pro", "Pro", 6000, BillingInterval.MONTHLY);

        Invoice invoice = calc.prorateChange(basic, pro,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 1, 1), null, null);

        // Full credit + full charge
        assert invoice.lineItems.get(0).amountCents == -3000 : "Full credit for Basic";
        assert invoice.lineItems.get(1).amountCents == 6000 : "Full charge for Pro";

        System.out.println("[PASS] testEdgeCaseChangeOnFirstDay");
    }

    private static void testEdgeCaseChangeOnLastDay(SubscriptionProration calc) {
        Plan basic = new Plan("basic", "Basic", 3000, BillingInterval.MONTHLY);
        Plan pro = new Plan("pro", "Pro", 6000, BillingInterval.MONTHLY);

        Invoice invoice = calc.prorateChange(basic, pro,
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31),
                LocalDate.of(2024, 1, 31), null, null);

        // 0 remaining days = no credit, no charge
        assert invoice.totalCents == 0 : "No proration on last day";

        System.out.println("[PASS] testEdgeCaseChangeOnLastDay");
    }
}
