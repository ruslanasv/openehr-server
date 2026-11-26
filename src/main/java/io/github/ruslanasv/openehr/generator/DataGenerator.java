package io.github.ruslanasv.openehr.generator;

import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.ehr.EhrStatus;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.GenericId;
import com.nedap.archie.rm.support.identification.PartyRef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.ehrbase.openehr.sdk.client.openehrclient.defaultrestclient.DefaultRestClient;

/**
 * Test data generator: creates N EHRs with M random compositions each through the openEHR SDK REST client.
 *
 * <p>Compositions are built from entity classes generated from the templates by the SDK's
 * generator-maven-plugin. Patients get subject ids {@code PREFIX-RUN-NNNN} in namespace
 * {@value #NAMESPACE}; the same seed produces the same data.
 */
public class DataGenerator {

    public static final String NAMESPACE = "generated";

    private final GeneratorOptions options;
    private final DefaultRestClient client;
    private final CompositionFactories factories;
    private final Map<String, List<Long>> timings = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> createdPerTemplate = new ConcurrentHashMap<>();
    private final AtomicInteger createdEhrs = new AtomicInteger();
    private final AtomicInteger retries = new AtomicInteger();
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());

    DataGenerator(GeneratorOptions options) {
        this.options = options;
        ClasspathTemplateProvider templateProvider = new ClasspathTemplateProvider();
        this.client = EhrbaseClients.create(options, templateProvider);
        this.factories = new CompositionFactories(templateProvider, Instant.now());
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT); // "10.3 s", not "10,3 s" on a Lithuanian Windows
        GeneratorOptions options;
        try {
            options = GeneratorOptions.parse(args);
        } catch (GeneratorOptions.UsageException e) {
            System.out.println(e.getMessage());
            System.exit(args.length > 0 && (args[0].equals("--help") || args[0].equals("-h")) ? 0 : 2);
            return;
        }
        boolean ok = new DataGenerator(options).run();
        System.exit(ok ? 0 : 1);
    }

    boolean run() throws Exception {
        String run = Long.toString(System.currentTimeMillis() / 1000 % 1_000_000, 36).toUpperCase();
        System.out.printf("Generating %d EHRs x %d compositions, %d threads, seed %d, templates %s%n",
                options.ehrs(), options.compositionsPerEhr(), options.threads(), options.seed(), options.templateIds());
        System.out.printf("Server %s, subject ids %s-%s-0001..%04d (namespace '%s')%n",
                options.baseUri(), options.prefix(), run, options.ehrs(), NAMESPACE);

        for (String templateId : options.templateIds()) {
            timed("ensure template", () -> {
                client.templateEndpoint().ensureExistence(templateId); // uploads it if the server lacks it
                return null;
            });
        }

        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(options.threads());
        AtomicInteger done = new AtomicInteger();
        int step = Math.max(1, options.ehrs() / 10);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= options.ehrs(); i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                try {
                    // per-patient Random: same seed -> same data regardless of thread scheduling
                    generatePatient(new Random(options.seed() * 31 + n), "%s-%s-%04d".formatted(options.prefix(), run, n));
                } catch (Exception e) {
                    errors.add("patient " + n + ": " + e.getMessage());
                }
                int d = done.incrementAndGet();
                if (d % step == 0 || d == options.ehrs()) {
                    System.out.printf("  %d/%d EHRs%n", d, options.ehrs());
                }
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        printSummary((System.nanoTime() - start) / 1e9);
        return errors.isEmpty();
    }

    private void generatePatient(Random rnd, String subjectId) {
        UUID ehrId = timed("create EHR", () -> client.ehrEndpoint().createEhr(ehrStatus(subjectId)));
        createdEhrs.incrementAndGet();
        for (int j = 0; j < options.compositionsPerEhr(); j++) {
            String templateId = options.templateIds().get(rnd.nextInt(options.templateIds().size()));
            Composition composition = factories.get(templateId).create(rnd);
            timed("create composition", () -> client.compositionEndpoint(ehrId).mergeRaw(composition));
            createdPerTemplate.computeIfAbsent(templateId, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    static EhrStatus ehrStatus(String subjectId) {
        EhrStatus status = new EhrStatus();
        status.setArchetypeNodeId("openEHR-EHR-EHR_STATUS.generic.v1");
        status.setName(new DvText("EHR Status"));
        status.setSubject(new PartySelf(new PartyRef(new GenericId(subjectId, "id_scheme"), NAMESPACE, "PERSON")));
        status.setQueryable(true);
        status.setModifiable(true);
        return status;
    }

    /** Times the call; retries EHRbase's transient 409 "... created concurrently ..." (see {@link #isConcurrentCreation}). */
    private <T> T timed(String operation, Supplier<T> call) {
        long t0 = System.nanoTime();
        try {
            for (int attempt = 1; ; attempt++) {
                try {
                    return call.get();
                } catch (RuntimeException e) {
                    if (attempt >= MAX_ATTEMPTS || !isConcurrentCreation(e)) {
                        throw e;
                    }
                    retries.incrementAndGet();
                }
            }
        } finally {
            timings.computeIfAbsent(operation, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add((System.nanoTime() - t0) / 1_000_000);
        }
    }

    private static final int MAX_ATTEMPTS = 3;

    /**
     * On an empty database EHRbase creates its internal record of the API user on the first write; parallel first
     * writes race and all but one fail with 409 "User already created concurrently by different request". Nothing is
     * stored for the failed request, so it is safe to retry.
     */
    static boolean isConcurrentCreation(Throwable e) {
        return e.getMessage() != null && e.getMessage().contains("409") && e.getMessage().contains("created concurrently");
    }

    private void printSummary(double seconds) {
        int compositions = createdPerTemplate.values().stream().mapToInt(AtomicInteger::get).sum();
        System.out.printf("%nCreated %d EHRs and %d compositions %s in %.1f s (%.1f compositions/s), %d errors, %d retries%n",
                createdEhrs.get(), compositions, new TreeMap<>(createdPerTemplate), seconds, compositions / seconds,
                errors.size(), retries.get());
        errors.stream().limit(5).forEach(e -> System.out.println("  ! " + e));
        System.out.printf("%n| %-20s | %5s | %5s | %5s | %5s | %5s |%n", "operation (ms)", "n", "avg", "p50", "p95", "max");
        System.out.println("|----------------------|-------|-------|-------|-------|-------|");
        new TreeMap<>(timings).forEach((op, list) -> {
            List<Long> v = new ArrayList<>(list);
            Collections.sort(v);
            System.out.printf("| %-20s | %5d | %5.0f | %5d | %5d | %5d |%n", op, v.size(),
                    v.stream().mapToLong(Long::longValue).average().orElse(0), percentile(v, 50), percentile(v, 95),
                    v.get(v.size() - 1));
        });
        System.out.println("\nFind generated patients with AQL:\n  SELECT e/ehr_id/value, e/ehr_status/subject/external_ref/id/value"
                + " FROM EHR e WHERE e/ehr_status/subject/external_ref/namespace = '" + NAMESPACE + "'");
    }

    private static long percentile(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
    }
}
