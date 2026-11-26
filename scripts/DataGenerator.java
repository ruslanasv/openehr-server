import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Test data generator (first version): creates N EHRs with M random compositions each.
 *
 * <pre>
 * java scripts/DataGenerator.java                       # 10 EHRs x 3 compositions
 * java scripts/DataGenerator.java --ehrs 100 --compositions 5 --threads 8 --seed 42
 * java scripts/DataGenerator.java --help
 * </pre>
 *
 * Single-file Java program (JDK 17+, no dependencies), run from the repo root.
 * Patients get subject ids {prefix}-{run}-{nnnn} in namespace "generated", so generated data can be
 * found (and later removed) separately from hand-made test data. Same seed = same data.
 */
public class DataGenerator {

    // ---------- configuration (command line) ----------
    static String base = "http://localhost:8080/ehrbase";
    static String user = "ehrbase-user";
    static String password = "SuperSecretPassword";
    static int ehrs = 10;
    static int compositionsPerEhr = 3;
    static int threads = 4;
    static long seed = System.currentTimeMillis();
    static String prefix = "GEN";
    static final String NAMESPACE = "generated";
    static List<String> templates = List.of("medication_order", "minimal_diagnosis.v0", "minimal_vital_signs.v0");

    static final Map<String, String> TEMPLATE_FILES = Map.of(
            "medication_order", "requests/templates/ckm/medication_order.opt",
            "minimal_diagnosis.v0", "requests/templates/minimal_diagnosis.opt",
            "minimal_vital_signs.v0", "requests/templates/minimal_vital_signs.opt");

    // ---------- reference data ----------
    record Diagnosis(String icd10, String name) {}
    record Drug(String atc, String name, int strengthMg, String form, String directions, String timing, String indication) {}

    static final List<Diagnosis> DIAGNOSES = List.of(
            new Diagnosis("I10", "Essential (primary) hypertension"),
            new Diagnosis("E11.9", "Type 2 diabetes mellitus without complications"),
            new Diagnosis("E78.5", "Hyperlipidaemia, unspecified"),
            new Diagnosis("J45.9", "Asthma, unspecified"),
            new Diagnosis("K21.9", "Gastro-oesophageal reflux disease without oesophagitis"),
            new Diagnosis("F32.9", "Depressive episode, unspecified"),
            new Diagnosis("M54.5", "Low back pain"),
            new Diagnosis("J06.9", "Acute upper respiratory infection, unspecified"),
            new Diagnosis("I48.9", "Atrial fibrillation and atrial flutter, unspecified"),
            new Diagnosis("E03.9", "Hypothyroidism, unspecified"),
            new Diagnosis("N39.0", "Urinary tract infection, site not specified"),
            new Diagnosis("I25.1", "Atherosclerotic heart disease"));

    static final List<Drug> DRUGS = List.of(
            new Drug("A10BA02", "Metformin", 500, "Tablet", "1 tablet twice daily with meals", "PT12H", "Type 2 diabetes mellitus"),
            new Drug("C09AA03", "Lisinopril", 10, "Tablet", "1 tablet once daily in the morning", "P1D", "Essential hypertension"),
            new Drug("C08CA01", "Amlodipine", 5, "Tablet", "1 tablet once daily", "P1D", "Essential hypertension"),
            new Drug("C10AA05", "Atorvastatin", 20, "Tablet", "1 tablet once daily in the evening", "P1D", "Hyperlipidaemia"),
            new Drug("C07AB07", "Bisoprolol", 5, "Tablet", "1 tablet once daily", "P1D", "Atrial fibrillation"),
            new Drug("A02BC01", "Omeprazole", 20, "Capsule", "1 capsule once daily before breakfast", "P1D", "Gastro-oesophageal reflux disease"),
            new Drug("N06AB06", "Sertraline", 50, "Tablet", "1 tablet once daily", "P1D", "Depressive episode"),
            new Drug("M01AE01", "Ibuprofen", 400, "Tablet", "1 tablet up to three times daily after meals", "PT8H", "Low back pain"),
            new Drug("N02BE01", "Paracetamol", 500, "Tablet", "1-2 tablets up to four times daily", "PT6H", "Pain or fever"),
            new Drug("J01CA04", "Amoxicillin", 500, "Capsule", "1 capsule three times daily for 7 days", "PT8H", "Urinary tract infection"));

    static final List<String> CLINICIANS = List.of("Dr. Jonas Jonaitis", "Dr. Asta Petraitienė", "Dr. Tomas Kazlauskas", "Nurse Ona Onaitė");
    static final List<String> FACILITIES = List.of("Vilnius Clinic", "Kaunas Health Centre", "Klaipėda Family Clinic");
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    // ---------- runtime ----------
    static HttpClient http;
    static String authHeader;
    static final Map<String, List<Long>> timings = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> createdPerTemplate = new ConcurrentHashMap<>();
    static final AtomicInteger createdEhrs = new AtomicInteger();
    static final List<String> errors = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);   // "10.3 s", not "10,3 s" on a Lithuanian Windows
        parseArgs(args);
        http =HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
        authHeader = "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
        String run = Long.toString(System.currentTimeMillis() / 1000 % 1_000_000, 36).toUpperCase();

        System.out.printf("Generating %d EHRs x %d compositions, %d threads, seed %d, templates %s%n",
                ehrs, compositionsPerEhr, threads, seed, templates);
        System.out.printf("Server %s, subject ids %s-%s-0001..%04d (namespace '%s')%n", base, prefix, run, ehrs, NAMESPACE);

        uploadTemplates();

        long start = System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger done = new AtomicInteger();
        int step = Math.max(1, ehrs / 10);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 1; i <= ehrs; i++) {
            int n = i;
            futures.add(pool.submit(() -> {
                try {
                    generatePatient(new Random(seed * 31 + n), String.format("%s-%s-%04d", prefix, run, n));
                } catch (Exception e) {
                    errors.add("patient " + n + ": " + e.getMessage());
                }
                int d = done.incrementAndGet();
                if (d % step == 0 || d == ehrs) System.out.printf("  %d/%d EHRs%n", d, ehrs);
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();
        printSummary((System.nanoTime() - start) / 1e9);
        if (!errors.isEmpty()) System.exit(1);
    }

    // ---------- generation ----------
    static void generatePatient(Random rnd, String subjectId) throws Exception {
        String ehrStatus = """
                {"_type":"EHR_STATUS","archetype_node_id":"openEHR-EHR-EHR_STATUS.generic.v1","name":{"value":"EHR Status"},
                 "subject":{"_type":"PARTY_SELF","external_ref":{"id":{"_type":"GENERIC_ID","value":"%s","scheme":"id_scheme"},
                 "namespace":"%s","type":"PERSON"}},"is_queryable":true,"is_modifiable":true}""".formatted(subjectId, NAMESPACE);
        HttpResponse<String> r = send("create EHR", request("/rest/openehr/v1/ehr")
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .header("Prefer", "return=representation").POST(BodyPublishers.ofString(ehrStatus)), 201);
        Matcher m = Pattern.compile("\"ehr_id\"\\s*:\\s*\\{[^}]*\"value\"\\s*:\\s*\"([0-9a-f-]{36})\"").matcher(r.body());
        if (!m.find()) throw new IllegalStateException("no ehr_id in response");
        String ehrId = m.group(1);
        createdEhrs.incrementAndGet();

        for (int j = 0; j < compositionsPerEhr; j++) {
            String template = templates.get(rnd.nextInt(templates.size()));
            Map<String, Object> flat = switch (template) {
                case "medication_order" -> medicationOrder(rnd);
                case "minimal_diagnosis.v0" -> diagnosis(rnd);
                case "minimal_vital_signs.v0" -> vitalSigns(rnd);
                default -> throw new IllegalArgumentException("no generator for template " + template);
            };
            send("create composition", request("/rest/openehr/v1/ehr/" + ehrId + "/composition?templateId=" + template + "&format=FLAT")
                    .header("Content-Type", "application/json").header("Prefer", "return=minimal")
                    .POST(BodyPublishers.ofString(toJson(flat))), 201, 204);
            createdPerTemplate.computeIfAbsent(template, k -> new AtomicInteger()).incrementAndGet();
        }
    }

    /** Common composition context; time = random moment in the last 365 days. */
    static Map<String, Object> context(Random rnd, LocalDateTime time) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("ctx/language", "en");
        f.put("ctx/territory", "LT");
        f.put("ctx/composer_name", pick(rnd, CLINICIANS));
        f.put("ctx/health_care_facility|name", pick(rnd, FACILITIES));
        f.put("ctx/time", ISO.format(time));
        f.put("ctx/setting|code", "228");
        return f;
    }

    static LocalDateTime randomTime(Random rnd) {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES).minusMinutes(rnd.nextInt(365 * 24 * 60));
    }

    static Map<String, Object> diagnosis(Random rnd) {
        LocalDateTime time = randomTime(rnd);
        Map<String, Object> f = context(rnd, time);
        List<Diagnosis> dx = new ArrayList<>(DIAGNOSES);
        Collections.shuffle(dx, rnd);
        int count = 1 + rnd.nextInt(3);
        for (int k = 0; k < count; k++) {
            String p = "diagnosis/problem_diagnosis:" + k + "/";
            f.put(p + "problem_diagnosis_name|code", dx.get(k).icd10());
            f.put(p + "problem_diagnosis_name|value", dx.get(k).name());
            f.put(p + "problem_diagnosis_name|terminology", "ICD10");
            if (rnd.nextDouble() < 0.8) f.put(p + "date_time_of_onset", ISO.format(time.minusDays(30 + rnd.nextInt(3650))));
            if (rnd.nextDouble() < 0.7) f.put(p + "severity|code", "at00" + (47 + rnd.nextInt(3)));   // at0047..at0049
        }
        return f;
    }

    static Map<String, Object> vitalSigns(Random rnd) {
        LocalDateTime time = randomTime(rnd);
        Map<String, Object> f = context(rnd, time);
        String t = ISO.format(time);
        int systolic = (int) clamp(gauss(rnd, 130, 15), 90, 200);
        int diastolic = (int) clamp(gauss(rnd, 82, 10), 50, systolic - 20);
        quantity(f, "vital_signs/blood_pressure/any_event:0/", t, "systolic", systolic, "mm[Hg]");
        f.put("vital_signs/blood_pressure/any_event:0/diastolic|magnitude", diastolic);
        f.put("vital_signs/blood_pressure/any_event:0/diastolic|unit", "mm[Hg]");
        if (rnd.nextDouble() < 0.9)
            quantity(f, "vital_signs/pulse_heart_beat/any_event:0/", t, "rate", (int) clamp(gauss(rnd, 76, 11), 45, 140), "/min");
        if (rnd.nextDouble() < 0.6)
            quantity(f, "vital_signs/body_temperature/any_event:0/", t, "temperature", round1(clamp(gauss(rnd, 36.8, 0.4), 35.5, 40.5)), "Cel");
        if (rnd.nextDouble() < 0.5)
            quantity(f, "vital_signs/body_weight/any_event:0/", t, "weight", round1(clamp(gauss(rnd, 80, 15), 45, 160)), "kg");
        return f;
    }

    static Map<String, Object> medicationOrder(Random rnd) {
        LocalDateTime time = randomTime(rnd);
        Map<String, Object> f = context(rnd, time);
        Drug d = pick(rnd, DRUGS);
        String p = "medication_order/medication_order/order:0/";
        String name = d.name() + " " + d.strengthMg() + " mg " + d.form().toLowerCase();
        f.put(p + "medication_item|value", name);
        f.put(p + "medication_item|code", d.atc());
        f.put(p + "medication_item|terminology", "ATC");
        f.put(p + "medication/form:0", d.form());
        f.put(p + "medication/strength_concentration|magnitude", d.strengthMg());
        f.put(p + "medication/strength_concentration|unit", "mg");
        f.put(p + "route:0", "Oral");
        f.put(p + "overall_directions_description", d.directions());
        f.put(p + "clinical_indication:0", d.indication());
        f.put(p + "order_details/order_start_date_time", ISO.format(time.truncatedTo(ChronoUnit.DAYS)));
        f.put(p + "timing", "R/" + ISO.format(time.truncatedTo(ChronoUnit.DAYS).plusDays(1).withHour(8)) + "/" + d.timing());
        f.put(p + "timing|formalism", "timing");
        f.put("medication_order/medication_order/narrative", name + ": " + d.directions());
        return f;
    }

    static void quantity(Map<String, Object> f, String event, String time, String element, Number value, String unit) {
        f.put(event + "time", time);
        f.put(event + element + "|magnitude", value);
        f.put(event + element + "|unit", unit);
    }

    // ---------- HTTP ----------
    static void uploadTemplates() throws Exception {
        for (String t : templates) {
            Path file = Path.of(Objects.requireNonNull(TEMPLATE_FILES.get(t), "unknown template " + t));
            if (!Files.exists(file)) throw new IllegalStateException(file + " not found - run from the repo root");
            HttpResponse<String> r = send("upload template", request("/rest/openehr/v1/definition/template/adl1.4")
                    .header("Content-Type", "application/xml").POST(BodyPublishers.ofFile(file)), 201, 409);
            System.out.printf("Template %-24s %s%n", t, r.statusCode() == 201 ? "uploaded" : "already present");
        }
    }

    static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).header("Authorization", authHeader);
    }

    static HttpResponse<String> send(String operation, HttpRequest.Builder builder, int... expected) throws Exception {
        long t0 = System.nanoTime();
        HttpResponse<String> r = http.send(builder.build(), BodyHandlers.ofString());
        timings.computeIfAbsent(operation, k -> Collections.synchronizedList(new ArrayList<>())).add((System.nanoTime() - t0) / 1_000_000);
        for (int code : expected) if (r.statusCode() == code) return r;
        String body = r.body() == null ? "" : r.body();
        throw new IllegalStateException(operation + " -> HTTP " + r.statusCode() + " " + body.substring(0, Math.min(300, body.length())));
    }

    // ---------- output ----------
    static void printSummary(double seconds) {
        int compositions = createdPerTemplate.values().stream().mapToInt(AtomicInteger::get).sum();
        System.out.printf("%nCreated %d EHRs and %d compositions %s in %.1f s (%.1f compositions/s), %d errors%n",
                createdEhrs.get(), compositions, new TreeMap<>(createdPerTemplate), seconds, compositions / seconds, errors.size());
        errors.stream().limit(5).forEach(e -> System.out.println("  ! " + e));
        System.out.printf("%n| %-20s | %5s | %5s | %5s | %5s | %5s |%n", "operation (ms)", "n", "avg", "p50", "p95", "max");
        System.out.println("|----------------------|-------|-------|-------|-------|-------|");
        timings.forEach((op, list) -> {
            List<Long> v = new ArrayList<>(list);
            Collections.sort(v);
            System.out.printf("| %-20s | %5d | %5.0f | %5d | %5d | %5d |%n", op, v.size(),
                    v.stream().mapToLong(Long::longValue).average().orElse(0), pct(v, 50), pct(v, 95), v.get(v.size() - 1));
        });
        System.out.println("\nFind generated patients with AQL:\n  SELECT e/ehr_id/value, e/ehr_status/subject/external_ref/id/value FROM EHR e"
                + " WHERE e/ehr_status/subject/external_ref/namespace = '" + NAMESPACE + "'");
    }

    // ---------- small utilities ----------
    static long pct(List<Long> sorted, int p) { return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1)); }
    static <T> T pick(Random rnd, List<T> list) { return list.get(rnd.nextInt(list.size())); }
    static double gauss(Random rnd, double mean, double sd) { return mean + rnd.nextGaussian() * sd; }
    static double clamp(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
    static double round1(double v) { return Math.round(v * 10) / 10.0; }

    static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        map.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(',');
            sb.append(quote(k)).append(':').append(v instanceof Number ? v.toString() : quote(String.valueOf(v)));
        });
        return sb.append('}').toString();
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }

    static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--help") || a.equals("-h")) { usage(); System.exit(0); }
            if (i + 1 >= args.length) { System.err.println("Missing value for " + a); usage(); System.exit(2); }
            String v = args[++i];
            switch (a) {
                case "--ehrs" -> ehrs = Integer.parseInt(v);
                case "--compositions" -> compositionsPerEhr = Integer.parseInt(v);
                case "--threads" -> threads = Integer.parseInt(v);
                case "--seed" -> seed = Long.parseLong(v);
                case "--prefix" -> prefix = v;
                case "--templates" -> templates = List.of(v.split(","));
                case "--base" -> base = v.replaceAll("/+$", "");
                case "--user" -> user = v;
                case "--password" -> password = v;
                default -> { System.err.println("Unknown option " + a); usage(); System.exit(2); }
            }
        }
    }

    static void usage() {
        System.out.println("""
                Usage: java scripts/DataGenerator.java [options]     (run from the repo root, JDK 17+)
                  --ehrs N             number of EHRs / patients            (default 10)
                  --compositions M     compositions per EHR                 (default 3)
                  --threads T          parallel workers                     (default 4)
                  --seed S             random seed - same seed, same data   (default: current time)
                  --prefix P           subject id prefix                    (default GEN)
                  --templates a,b      subset of: medication_order,minimal_diagnosis.v0,minimal_vital_signs.v0
                  --base URL           (default http://localhost:8080/ehrbase)
                  --user U --password P
                """);
    }
}
