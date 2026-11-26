package io.github.ruslanasv.openehr.generator;

import static org.assertj.core.api.Assertions.assertThat;

import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datavalues.DvCodedText;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.ehrbase.openehr.sdk.serialisation.jsonencoding.CanonicalJson;
import org.ehrbase.openehr.sdk.validation.ConstraintViolation;
import org.ehrbase.openehr.sdk.validation.LocatableValidator;
import org.ehrbase.openehr.sdk.webtemplate.model.WebTemplate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

/** Offline checks of the generated compositions - no EHRbase needed. */
class CompositionFactoriesTest {

    private static final Instant ANCHOR = Instant.parse("2026-11-26T10:00:00Z");
    private static final ClasspathTemplateProvider TEMPLATES = new ClasspathTemplateProvider();
    private static final CompositionFactories FACTORIES = new CompositionFactories(TEMPLATES, ANCHOR);

    static List<String> templateIds() {
        return CompositionFactories.TEMPLATE_IDS;
    }

    @ParameterizedTest
    @MethodSource("templateIds")
    void generatedCompositionsAreValidAgainstTheirTemplate(String templateId) {
        WebTemplate webTemplate = TEMPLATES.buildIntrospect(templateId).orElseThrow();
        LocatableValidator validator = new LocatableValidator();
        for (int seed = 0; seed < 50; seed++) {
            Composition composition = FACTORIES.get(templateId).create(new Random(seed));

            List<ConstraintViolation> violations = validator.validate(composition, webTemplate);

            assertThat(violations).as("seed %d", seed).isEmpty();
            assertThat(composition.getArchetypeDetails().getTemplateId().getValue()).isEqualTo(templateId);
        }
    }

    @Test
    void validatorDetectsMissingMandatoryElement() {
        Composition composition = FACTORIES.get("minimal_diagnosis.v0").create(new Random(1));
        ItemTree tree = (ItemTree) composition.itemAtPath("/content[openEHR-EHR-EVALUATION.problem_diagnosis.v1]/data[at0001]");
        tree.getItems().removeIf(item -> item.getArchetypeNodeId().equals("at0002")); // Problem/Diagnosis name, 1..1

        List<ConstraintViolation> violations = new LocatableValidator()
                .validate(composition, TEMPLATES.buildIntrospect("minimal_diagnosis.v0").orElseThrow());

        assertThat(violations).isNotEmpty();
        assertThat(violations.toString()).contains("at0002");
    }

    @ParameterizedTest
    @MethodSource("templateIds")
    void sameSeedGivesSameComposition(String templateId) {
        CanonicalJson json = new CanonicalJson();
        String first = json.marshal(FACTORIES.get(templateId).create(new Random(42)));
        String second = json.marshal(FACTORIES.get(templateId).create(new Random(42)));
        String other = json.marshal(FACTORIES.get(templateId).create(new Random(43)));

        assertThat(second).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void medicationItemCarriesAtcCode() {
        for (int seed = 0; seed < 20; seed++) {
            Composition composition = FACTORIES.get("medication_order").create(new Random(seed));

            Element item = (Element) composition.itemAtPath(
                    "/content[openEHR-EHR-INSTRUCTION.medication_order.v3]/activities[at0001]/description[at0002]/items[at0070]");

            assertThat(item.getValue()).isInstanceOf(DvCodedText.class);
            DvCodedText coded = (DvCodedText) item.getValue();
            assertThat(coded.getDefiningCode().getTerminologyId().getValue()).isEqualTo("ATC");
            assertThat(ReferenceData.DRUGS).anyMatch(d -> d.atc().equals(coded.getDefiningCode().getCodeString())
                    && d.label().equals(coded.getValue()));
        }
    }

    @Test
    void diastolicStaysBelowSystolic() {
        String bp = "/content[openEHR-EHR-OBSERVATION.blood_pressure.v2]/data[at0001]/events[at0006]/data[at0003]/items[%s]/value/magnitude";
        for (int seed = 0; seed < 200; seed++) {
            Composition c = FACTORIES.get("minimal_vital_signs.v0").create(new Random(seed));

            double systolic = (Double) c.itemAtPath(bp.formatted("at0004"));
            double diastolic = (Double) c.itemAtPath(bp.formatted("at0005"));

            assertThat(diastolic).as("seed %d", seed).isLessThanOrEqualTo(systolic - 20);
        }
    }
}
