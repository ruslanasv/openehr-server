package io.github.ruslanasv.openehr.generator;

import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.TerminologyId;
import io.github.ruslanasv.openehr.generator.ReferenceData.Diagnosis;
import io.github.ruslanasv.openehr.model.minimaldiagnosisv0composition.MinimalDiagnosisV0Composition;
import io.github.ruslanasv.openehr.model.minimaldiagnosisv0composition.definition.ProblemDiagnosisEvaluation;
import io.github.ruslanasv.openehr.model.minimaldiagnosisv0composition.definition.SeverityDefiningCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.ehrbase.openehr.sdk.generator.commons.shareddefinition.Category;
import org.ehrbase.openehr.sdk.generator.commons.shareddefinition.Language;
import org.ehrbase.openehr.sdk.generator.commons.shareddefinition.Setting;
import org.ehrbase.openehr.sdk.generator.commons.shareddefinition.Territory;
import org.ehrbase.openehr.sdk.serialisation.dto.GeneratedDtoToRmConverter;

/** minimal_diagnosis.v0: 1-3 ICD-10 diagnoses, optional onset date and severity. */
class DiagnosisFactory extends AbstractCompositionFactory {

    DiagnosisFactory(GeneratedDtoToRmConverter converter, Instant anchor) {
        super(converter, anchor);
    }

    @Override
    public String templateId() {
        return "minimal_diagnosis.v0";
    }

    @Override
    public Composition create(Random rnd) {
        OffsetDateTime time = randomTime(rnd);
        MinimalDiagnosisV0Composition c = new MinimalDiagnosisV0Composition();
        c.setLanguage(Language.EN);
        c.setTerritory(Territory.LT);
        c.setCategoryDefiningCode(Category.EVENT);
        c.setSettingDefiningCode(Setting.PRIMARY_MEDICAL_CARE);
        c.setComposer(composer(rnd));
        c.setHealthCareFacility(facility(rnd));
        c.setStartTimeValue(time);

        List<Diagnosis> diagnoses = new ArrayList<>(ReferenceData.DIAGNOSES);
        Collections.shuffle(diagnoses, rnd);
        List<ProblemDiagnosisEvaluation> entries = new ArrayList<>();
        for (Diagnosis dx : diagnoses.subList(0, 1 + rnd.nextInt(3))) {
            ProblemDiagnosisEvaluation e = new ProblemDiagnosisEvaluation();
            e.setSubject(new PartySelf());
            e.setLanguage(Language.EN);
            e.setProblemDiagnosisName(new DvCodedText(dx.name(), new CodePhrase(new TerminologyId("ICD10"), dx.icd10())));
            if (rnd.nextDouble() < 0.8) {
                e.setDateTimeOfOnsetValue(time.minusDays(30 + rnd.nextInt(3650)));
            }
            if (rnd.nextDouble() < 0.7) {
                e.setSeverityDefiningCode(SeverityDefiningCode.values()[rnd.nextInt(SeverityDefiningCode.values().length)]);
            }
            entries.add(e);
        }
        c.setProblemDiagnosis(entries);
        return toRm(c);
    }
}
