package io.github.ruslanasv.openehr.generator;

import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.encapsulated.DvParsable;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.TerminologyId;
import io.github.ruslanasv.openehr.generator.ReferenceData.Drug;
import io.github.ruslanasv.openehr.model.medicationordercomposition.MedicationOrderComposition;
import io.github.ruslanasv.openehr.model.medicationordercomposition.definition.MedicationCluster;
import io.github.ruslanasv.openehr.model.medicationordercomposition.definition.MedicationFormElement;
import io.github.ruslanasv.openehr.model.medicationordercomposition.definition.MedicationOrderClinicalIndicationElement;
import io.github.ruslanasv.openehr.model.medicationordercomposition.definition.MedicationOrderInstruction;
import io.github.ruslanasv.openehr.model.medicationordercomposition.definition.MedicationOrderOrderActivity;
import io.github.ruslanasv.openehr.model.medicationordercomposition.definition.MedicationOrderRouteElement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import org.ehrbase.openehr.sdk.generator.commons.shareddefinition.Category;
import org.ehrbase.openehr.sdk.generator.commons.shareddefinition.Language;
import org.ehrbase.openehr.sdk.generator.commons.shareddefinition.Setting;
import org.ehrbase.openehr.sdk.generator.commons.shareddefinition.Territory;
import org.ehrbase.openehr.sdk.serialisation.dto.GeneratedDtoToRmConverter;

/**
 * medication_order (CKM INSTRUCTION.medication_order.v3): one order of a common drug.
 * The medication item is ATC coded (the template constrains it to DV_CODED_TEXT with terminology ATC).
 */
class MedicationOrderFactory extends AbstractCompositionFactory {

    MedicationOrderFactory(GeneratedDtoToRmConverter converter, Instant anchor) {
        super(converter, anchor);
    }

    @Override
    public String templateId() {
        return "medication_order";
    }

    @Override
    public Composition create(Random rnd) {
        OffsetDateTime time = randomTime(rnd);
        OffsetDateTime startDay = time.truncatedTo(ChronoUnit.DAYS);
        Drug drug = pick(rnd, ReferenceData.DRUGS);

        MedicationOrderComposition c = new MedicationOrderComposition();
        c.setLanguage(Language.EN);
        c.setTerritory(Territory.LT);
        c.setCategoryDefiningCode(Category.EVENT);
        c.setSettingDefiningCode(Setting.PRIMARY_MEDICAL_CARE);
        c.setComposer(composer(rnd));
        c.setHealthCareFacility(facility(rnd));
        c.setStartTimeValue(time);

        MedicationCluster medication = new MedicationCluster();
        MedicationFormElement form = new MedicationFormElement();
        form.setValue(drug.form());
        medication.setForm(List.of(form));
        medication.setStrengthConcentrationMagnitude((double) drug.strengthMg());
        medication.setStrengthConcentrationUnits("mg");

        MedicationOrderRouteElement route = new MedicationOrderRouteElement();
        route.setValue("Oral");
        MedicationOrderClinicalIndicationElement indication = new MedicationOrderClinicalIndicationElement();
        indication.setValue(drug.indication());

        MedicationOrderOrderActivity order = new MedicationOrderOrderActivity();
        order.setMedicationItem(new DvCodedText(drug.label(), new CodePhrase(new TerminologyId("ATC"), drug.atc())));
        order.setMedication(medication);
        order.setRoute(List.of(route));
        order.setOverallDirectionsDescriptionValue(drug.directions());
        order.setClinicalIndication(List.of(indication));
        order.setOrderStartDateTimeValue(startDay);
        order.setTiming(new DvParsable("R/" + startDay.plusDays(1).withHour(8) + "/" + drug.timingInterval(), "timing"));
        order.setActionArchetypeId("/.*/");

        MedicationOrderInstruction instruction = new MedicationOrderInstruction();
        instruction.setSubject(new PartySelf());
        instruction.setLanguage(Language.EN);
        instruction.setNarrativeValue(drug.label() + ": " + drug.directions());
        instruction.setOrder(List.of(order));
        c.setMedicationOrder(instruction);

        return toRm(c);
    }
}
