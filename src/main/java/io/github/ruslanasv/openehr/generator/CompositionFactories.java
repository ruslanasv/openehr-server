package io.github.ruslanasv.openehr.generator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.ehrbase.openehr.sdk.serialisation.dto.GeneratedDtoToRmConverter;
import org.ehrbase.openehr.sdk.serialisation.walker.defaultvalues.DefaultValues;
import org.ehrbase.openehr.sdk.webtemplate.templateprovider.TemplateProvider;

/** Registry of the composition factories, one per supported template. */
public final class CompositionFactories {

    public static final List<String> TEMPLATE_IDS =
            List.of("medication_order", "minimal_diagnosis.v0", "minimal_vital_signs.v0");

    private final Map<String, CompositionFactory> factories;

    /** @param anchor generated times lie within the year before this instant (fixed in tests) */
    public CompositionFactories(TemplateProvider templateProvider, Instant anchor) {
        GeneratedDtoToRmConverter converter = new GeneratedDtoToRmConverter(templateProvider, dto -> new DefaultValues());
        factories = Stream.of(
                        new MedicationOrderFactory(converter, anchor),
                        new DiagnosisFactory(converter, anchor),
                        new VitalSignsFactory(converter, anchor))
                .collect(Collectors.toMap(CompositionFactory::templateId, Function.identity()));
    }

    public CompositionFactory get(String templateId) {
        CompositionFactory factory = factories.get(templateId);
        if (factory == null) {
            throw new IllegalArgumentException("No composition factory for template " + templateId);
        }
        return factory;
    }
}
