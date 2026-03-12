import java.util.*;
import java.util.function.Function;

/**
 * Stripe Interview Question #9: API Versioning / Request Router
 *
 * Staff-level extensions:
 * - Version transformation chain: requests/responses are transformed through
 *   a chain of version-specific migrations (like Stripe's real system)
 * - Pinned version per merchant (stored externally)
 * - Changelog tracking per version
 * - Version lifecycle management (active, deprecated, sunset)
 * - Request/response transformation middleware
 */
public class ApiVersionRouter {

    // =========================================================================
    // Data models
    // =========================================================================

    static class ApiRequest {
        final String path;
        final String method;
        final String version;
        final Map<String, Object> body;
        final Map<String, String> headers;

        ApiRequest(String path, String method, String version,
                   Map<String, Object> body, Map<String, String> headers) {
            this.path = path;
            this.method = method;
            this.version = version;
            this.body = body != null ? new LinkedHashMap<>(body) : new LinkedHashMap<>();
            this.headers = headers != null ? headers : new LinkedHashMap<>();
        }

        /** Create a copy with a modified body. */
        ApiRequest withBody(Map<String, Object> newBody) {
            return new ApiRequest(path, method, version, newBody, headers);
        }
    }

    static class ApiResponse {
        final int statusCode;
        final Map<String, Object> body;
        final Map<String, String> headers;
        final String handledByVersion;

        ApiResponse(int statusCode, Map<String, Object> body,
                    String handledByVersion) {
            this.statusCode = statusCode;
            this.body = body != null ? body : new LinkedHashMap<>();
            this.headers = new LinkedHashMap<>();
            this.handledByVersion = handledByVersion;
        }

        ApiResponse withBody(Map<String, Object> newBody) {
            return new ApiResponse(statusCode, newBody, handledByVersion);
        }

        @Override
        public String toString() {
            return String.format("HTTP %d (version=%s) body=%s",
                    statusCode, handledByVersion, body);
        }
    }

    // =========================================================================
    // Version lifecycle
    // =========================================================================

    enum VersionStatus { ACTIVE, DEPRECATED, SUNSET }

    static class VersionInfo {
        final String version;
        VersionStatus status;
        final String releaseNotes;
        final List<String> changes;
        final Function<ApiRequest, ApiResponse> handler;

        VersionInfo(String version, String releaseNotes,
                    List<String> changes,
                    Function<ApiRequest, ApiResponse> handler) {
            this.version = version;
            this.status = VersionStatus.ACTIVE;
            this.releaseNotes = releaseNotes;
            this.changes = changes;
            this.handler = handler;
        }
    }

    // =========================================================================
    // Version migration (transformation chain)
    // Stripe's actual system: request comes in at version X,
    // gets upgraded through a chain of transforms to reach the latest handler,
    // then the response is downgraded back through the chain.
    // =========================================================================

    @FunctionalInterface
    interface RequestTransformer {
        Map<String, Object> transformRequest(Map<String, Object> body);
    }

    @FunctionalInterface
    interface ResponseTransformer {
        Map<String, Object> transformResponse(Map<String, Object> body);
    }

    static class VersionMigration {
        final String fromVersion;
        final String toVersion;
        final RequestTransformer upgradeRequest;
        final ResponseTransformer downgradeResponse;

        VersionMigration(String fromVersion, String toVersion,
                         RequestTransformer upgradeRequest,
                         ResponseTransformer downgradeResponse) {
            this.fromVersion = fromVersion;
            this.toVersion = toVersion;
            this.upgradeRequest = upgradeRequest;
            this.downgradeResponse = downgradeResponse;
        }
    }

    // =========================================================================
    // Core router
    // =========================================================================

    private final TreeMap<String, VersionInfo> versions = new TreeMap<>();
    private final List<VersionMigration> migrations = new ArrayList<>();
    private final Map<String, String> merchantPinnedVersions = new HashMap<>();
    private String latestVersion;

    /**
     * Register a new API version with its handler.
     */
    public void registerVersion(String version, String releaseNotes,
                                 List<String> changes,
                                 Function<ApiRequest, ApiResponse> handler) {
        versions.put(version, new VersionInfo(version, releaseNotes, changes, handler));
        if (latestVersion == null || version.compareTo(latestVersion) > 0) {
            latestVersion = version;
        }
    }

    /**
     * Register a migration between two consecutive versions.
     */
    public void registerMigration(String fromVersion, String toVersion,
                                   RequestTransformer upgradeRequest,
                                   ResponseTransformer downgradeResponse) {
        migrations.add(new VersionMigration(
                fromVersion, toVersion, upgradeRequest, downgradeResponse));
    }

    /**
     * Pin a merchant to a specific API version.
     */
    public void pinMerchant(String merchantId, String version) {
        merchantPinnedVersions.put(merchantId, version);
    }

    /**
     * Set version lifecycle status.
     */
    public void setVersionStatus(String version, VersionStatus status) {
        VersionInfo info = versions.get(version);
        if (info != null) {
            info.status = status;
        }
    }

    /**
     * Route a request through the version transformation chain.
     *
     * Strategy:
     * 1. Determine the requested version (explicit, pinned, or default)
     * 2. Upgrade the request through the migration chain to the latest version
     * 3. Execute the latest version's handler
     * 4. Downgrade the response back through the chain to the requested version
     */
    public ApiResponse route(ApiRequest request, String merchantId) {
        // Resolve version
        String requestedVersion = resolveVersion(request.version, merchantId);
        if (requestedVersion == null) {
            return new ApiResponse(400,
                    Map.of("error", "No API version could be determined"), null);
        }

        // Check if version exists
        if (!versions.containsKey(requestedVersion) && versions.floorKey(requestedVersion) == null) {
            return new ApiResponse(400,
                    Map.of("error", "Unsupported API version: " + requestedVersion), null);
        }

        // Check if version is sunset
        VersionInfo versionInfo = versions.get(requestedVersion);
        if (versionInfo != null && versionInfo.status == VersionStatus.SUNSET) {
            return new ApiResponse(410,
                    Map.of("error", "API version " + requestedVersion + " has been sunset"), null);
        }

        // Build migration chain from requested version to latest
        List<VersionMigration> chain = buildMigrationChain(requestedVersion, latestVersion);

        // Upgrade request through chain
        Map<String, Object> currentBody = new LinkedHashMap<>(request.body);
        for (VersionMigration migration : chain) {
            currentBody = migration.upgradeRequest.transformRequest(currentBody);
        }

        // Execute latest version handler
        ApiRequest upgradedRequest = request.withBody(currentBody);
        VersionInfo latestInfo = versions.get(latestVersion);
        ApiResponse response = latestInfo.handler.apply(upgradedRequest);

        // Downgrade response through chain (in reverse)
        Map<String, Object> responseBody = new LinkedHashMap<>(response.body);
        for (int i = chain.size() - 1; i >= 0; i--) {
            responseBody = chain.get(i).downgradeResponse.transformResponse(responseBody);
        }

        ApiResponse finalResponse = response.withBody(responseBody);

        // Add deprecation warning header if applicable
        if (versionInfo != null && versionInfo.status == VersionStatus.DEPRECATED) {
            finalResponse.headers.put("Stripe-Deprecation-Warning",
                    "Version " + requestedVersion + " is deprecated. Please upgrade.");
        }

        return finalResponse;
    }

    /**
     * Simple route without merchant context.
     */
    public ApiResponse route(ApiRequest request) {
        return route(request, null);
    }

    private String resolveVersion(String explicitVersion, String merchantId) {
        if (explicitVersion != null) return explicitVersion;
        if (merchantId != null && merchantPinnedVersions.containsKey(merchantId)) {
            return merchantPinnedVersions.get(merchantId);
        }
        return latestVersion;
    }

    private List<VersionMigration> buildMigrationChain(String from, String to) {
        if (from.equals(to)) return Collections.emptyList();

        List<VersionMigration> chain = new ArrayList<>();
        String current = from;

        while (!current.equals(to)) {
            boolean found = false;
            for (VersionMigration m : migrations) {
                if (m.fromVersion.equals(current)) {
                    chain.add(m);
                    current = m.toVersion;
                    found = true;
                    break;
                }
            }
            if (!found) break; // No more migrations available
        }

        return chain;
    }

    /**
     * Get changelog between two versions.
     */
    public List<String> getChangelog(String fromVersion, String toVersion) {
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, VersionInfo> entry : versions.subMap(
                fromVersion, false, toVersion, true).entrySet()) {
            changes.add("--- " + entry.getKey() + " ---");
            changes.addAll(entry.getValue().changes);
        }
        return changes;
    }

    public List<String> getActiveVersions() {
        return versions.values().stream()
                .filter(v -> v.status == VersionStatus.ACTIVE)
                .map(v -> v.version)
                .collect(java.util.stream.Collectors.toList());
    }

    // =========================================================================
    // Tests
    // =========================================================================

    public static void main(String[] args) {
        System.out.println("=== API Version Router Tests (Staff Level) ===\n");

        testDirectRouting();
        testVersionTransformationChain();
        testMerchantPinnedVersion();
        testDeprecationWarning();
        testSunsetVersion();
        testChangelog();
        testDefaultToLatest();

        System.out.println("\nAll tests passed.");
    }

    private static ApiVersionRouter buildRouter() {
        ApiVersionRouter router = new ApiVersionRouter();

        // Version 1: charge.source (old field name)
        router.registerVersion("2022-11-15",
                "Initial version",
                List.of("charge object uses 'source' field"),
                req -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("id", "ch_123");
                    resp.put("payment_method", req.body.getOrDefault("payment_method", "pm_card"));
                    resp.put("amount", req.body.getOrDefault("amount", 0));
                    resp.put("status", "succeeded");
                    return new ApiResponse(200, resp, "2023-10-16");
                });

        // Version 2: charge.payment_method (renamed from source)
        router.registerVersion("2023-08-16",
                "Rename source to payment_method",
                List.of("charge.source renamed to charge.payment_method",
                        "Added metadata field to charge"),
                req -> new ApiResponse(200, Map.of(), "2023-08-16")); // not used directly

        // Version 3: latest - adds status enum
        router.registerVersion("2023-10-16",
                "Status field becomes enum",
                List.of("charge.status is now an enum: succeeded|failed|pending",
                        "Added created_at timestamp"),
                req -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("id", "ch_123");
                    resp.put("payment_method", req.body.getOrDefault("payment_method", "pm_card"));
                    resp.put("amount", req.body.getOrDefault("amount", 0));
                    resp.put("status", "succeeded");
                    resp.put("created_at", "2023-10-16T00:00:00Z");
                    return new ApiResponse(200, resp, "2023-10-16");
                });

        // Migration: 2022-11-15 -> 2023-08-16
        router.registerMigration("2022-11-15", "2023-08-16",
                // Upgrade request: rename 'source' to 'payment_method'
                body -> {
                    Map<String, Object> upgraded = new LinkedHashMap<>(body);
                    if (upgraded.containsKey("source")) {
                        upgraded.put("payment_method", upgraded.remove("source"));
                    }
                    return upgraded;
                },
                // Downgrade response: rename 'payment_method' back to 'source'
                body -> {
                    Map<String, Object> downgraded = new LinkedHashMap<>(body);
                    if (downgraded.containsKey("payment_method")) {
                        downgraded.put("source", downgraded.remove("payment_method"));
                    }
                    downgraded.remove("metadata"); // didn't exist in v1
                    return downgraded;
                });

        // Migration: 2023-08-16 -> 2023-10-16
        router.registerMigration("2023-08-16", "2023-10-16",
                body -> body, // No request changes needed
                // Downgrade response: remove created_at (didn't exist in v2)
                body -> {
                    Map<String, Object> downgraded = new LinkedHashMap<>(body);
                    downgraded.remove("created_at");
                    return downgraded;
                });

        return router;
    }

    private static void testDirectRouting() {
        ApiVersionRouter router = buildRouter();

        ApiRequest request = new ApiRequest("/v1/charges", "POST",
                "2023-10-16",
                Map.of("payment_method", "pm_visa", "amount", 5000),
                null);

        ApiResponse response = router.route(request);

        assert response.statusCode == 200;
        assert response.body.containsKey("payment_method");
        assert response.body.containsKey("created_at") : "Latest version should have created_at";

        System.out.println("[PASS] testDirectRouting");
    }

    private static void testVersionTransformationChain() {
        ApiVersionRouter router = buildRouter();

        // Request using v1 field name "source"
        ApiRequest request = new ApiRequest("/v1/charges", "POST",
                "2022-11-15",
                Map.of("source", "src_visa", "amount", 5000),
                null);

        ApiResponse response = router.route(request);

        assert response.statusCode == 200;
        // Response should be downgraded: 'payment_method' -> 'source'
        assert response.body.containsKey("source")
                : "V1 response should have 'source', got: " + response.body.keySet();
        assert !response.body.containsKey("payment_method")
                : "V1 response should NOT have 'payment_method'";
        assert !response.body.containsKey("created_at")
                : "V1 response should NOT have 'created_at'";

        System.out.println("[PASS] testVersionTransformationChain");
    }

    private static void testMerchantPinnedVersion() {
        ApiVersionRouter router = buildRouter();
        router.pinMerchant("merchant_legacy", "2022-11-15");

        // No explicit version — should use merchant's pinned version
        ApiRequest request = new ApiRequest("/v1/charges", "POST",
                null,
                Map.of("source", "src_visa", "amount", 3000),
                null);

        ApiResponse response = router.route(request, "merchant_legacy");

        assert response.statusCode == 200;
        assert response.body.containsKey("source")
                : "Pinned to v1, should get 'source' in response";

        System.out.println("[PASS] testMerchantPinnedVersion");
    }

    private static void testDeprecationWarning() {
        ApiVersionRouter router = buildRouter();
        router.setVersionStatus("2022-11-15", VersionStatus.DEPRECATED);

        ApiRequest request = new ApiRequest("/v1/charges", "POST",
                "2022-11-15", Map.of("source", "src_visa", "amount", 1000), null);

        ApiResponse response = router.route(request);

        assert response.statusCode == 200 : "Deprecated versions should still work";
        assert response.headers.containsKey("Stripe-Deprecation-Warning")
                : "Should include deprecation warning header";

        System.out.println("[PASS] testDeprecationWarning");
    }

    private static void testSunsetVersion() {
        ApiVersionRouter router = buildRouter();
        router.setVersionStatus("2022-11-15", VersionStatus.SUNSET);

        ApiRequest request = new ApiRequest("/v1/charges", "POST",
                "2022-11-15", Map.of("source", "src_visa"), null);

        ApiResponse response = router.route(request);

        assert response.statusCode == 410 : "Sunset versions should return 410 Gone";

        System.out.println("[PASS] testSunsetVersion");
    }

    private static void testChangelog() {
        ApiVersionRouter router = buildRouter();

        List<String> changes = router.getChangelog("2022-11-15", "2023-10-16");

        assert changes.size() > 2 : "Should have changelog entries";
        assert changes.stream().anyMatch(c -> c.contains("payment_method"))
                : "Changelog should mention payment_method rename";

        System.out.println("[PASS] testChangelog");
    }

    private static void testDefaultToLatest() {
        ApiVersionRouter router = buildRouter();

        // No version specified, no merchant — should use latest
        ApiRequest request = new ApiRequest("/v1/charges", "POST",
                null, Map.of("payment_method", "pm_visa", "amount", 2000), null);

        ApiResponse response = router.route(request);

        assert response.statusCode == 200;
        assert response.body.containsKey("created_at") : "Latest version has created_at";

        System.out.println("[PASS] testDefaultToLatest");
    }
}
