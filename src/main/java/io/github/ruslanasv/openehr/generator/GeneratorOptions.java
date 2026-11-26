package io.github.ruslanasv.openehr.generator;

import java.net.URI;
import java.util.List;

/** Command line options of {@link DataGenerator}. */
public record GeneratorOptions(
        URI baseUri,
        String user,
        String password,
        int ehrs,
        int compositionsPerEhr,
        int threads,
        long seed,
        String prefix,
        List<String> templateIds) {

    public static final String USAGE = """
            Usage: data-generator [options]
              --ehrs N             number of EHRs / patients            (default 10)
              --compositions M     compositions per EHR                 (default 3)
              --threads T          parallel workers                     (default 4)
              --seed S             random seed - same seed, same data   (default: current time)
              --prefix P           subject id prefix                    (default GEN)
              --templates a,b      subset of: %s
              --base URL           EHRbase base URL                     (default http://localhost:8080/ehrbase/)
              --user U --password P
            """.formatted(String.join(",", CompositionFactories.TEMPLATE_IDS));

    /** Thrown for invalid arguments or --help; the message is meant for the user. */
    public static class UsageException extends RuntimeException {
        public UsageException(String message) {
            super(message);
        }
    }

    public static GeneratorOptions parse(String... args) {
        String base = "http://localhost:8080/ehrbase/";
        String user = "ehrbase-user";
        String password = "SuperSecretPassword";
        int ehrs = 10;
        int compositions = 3;
        int threads = 4;
        long seed = System.currentTimeMillis();
        String prefix = "GEN";
        List<String> templates = CompositionFactories.TEMPLATE_IDS;

        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if (option.equals("--help") || option.equals("-h")) {
                throw new UsageException(USAGE);
            }
            if (i + 1 >= args.length) {
                throw new UsageException("Missing value for " + option + "\n\n" + USAGE);
            }
            String value = args[++i];
            switch (option) {
                case "--ehrs" -> ehrs = positive(option, value);
                case "--compositions" -> compositions = positive(option, value);
                case "--threads" -> threads = positive(option, value);
                case "--seed" -> seed = Long.parseLong(value);
                case "--prefix" -> prefix = value;
                case "--templates" -> templates = List.of(value.split(","));
                case "--base" -> base = value.endsWith("/") ? value : value + "/";
                case "--user" -> user = value;
                case "--password" -> password = value;
                default -> throw new UsageException("Unknown option " + option + "\n\n" + USAGE);
            }
        }
        for (String t : templates) {
            if (!CompositionFactories.TEMPLATE_IDS.contains(t)) {
                throw new UsageException("Unknown template " + t + "\n\n" + USAGE);
            }
        }
        return new GeneratorOptions(URI.create(base), user, password, ehrs, compositions, threads, seed, prefix, templates);
    }

    private static int positive(String option, String value) {
        int n = Integer.parseInt(value);
        if (n < 1) {
            throw new UsageException(option + " must be >= 1");
        }
        return n;
    }
}
