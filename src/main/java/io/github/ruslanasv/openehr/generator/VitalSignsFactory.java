package io.github.ruslanasv.openehr.generator;

import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.generic.PartySelf;
import io.github.ruslanasv.openehr.model.minimalvitalsignsv0composition.MinimalVitalSignsV0Composition;
import io.github.ruslanasv.openehr.model.minimalvitalsignsv0composition.definition.BloodPressureAnyEventPointEvent;
import io.github.ruslanasv.openehr.model.minimalvitalsignsv0composition.definition.BloodPressureObservation;
import io.github.ruslanasv.openehr.model.minimalvitalsignsv0composition.definition.BodyTemperatureAnyEventPointEvent;
import io.github.ruslanasv.openehr.model.minimalvitalsignsv0composition.definition.BodyTemperatureObservation;
import io.github.ruslanasv.openehr.model.minimalvitalsignsv0composition.definition.BodyWeightAnyEventPointEvent;
import io.github.ruslanasv.openehr.model.minimalvitalsignsv0composition.definition.BodyWeightObservation;
import io.github.ruslanasv.openehr.model.minimalvitalsignsv0composition.definition.PulseHeartBeatAnyEventPointEvent;
import io.github.ruslanasv.openehr.model.minimalvitalsignsv0composition.definition.PulseHeartBeatObservation;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Random;
import org.ehrbase.openehr.sdk.generator.commons.shareddefinition.Category;
import org.ehrbase.openehr.sdk.generator.commons.shareddefinition.Language;
import org.ehrbase.openehr.sdk.generator.commons.shareddefinition.Setting;
import org.ehrbase.openehr.sdk.generator.commons.shareddefinition.Territory;
import org.ehrbase.openehr.sdk.serialisation.dto.GeneratedDtoToRmConverter;

/**
 * minimal_vital_signs.v0: blood pressure always; pulse (90%), temperature (60%), weight (50%).
 * Values are normally distributed around typical adult values; diastolic stays below systolic.
 */
class VitalSignsFactory extends AbstractCompositionFactory {

    VitalSignsFactory(GeneratedDtoToRmConverter converter, Instant anchor) {
        super(converter, anchor);
    }

    @Override
    public String templateId() {
        return "minimal_vital_signs.v0";
    }

    @Override
    public Composition create(Random rnd) {
        OffsetDateTime time = randomTime(rnd);
        MinimalVitalSignsV0Composition c = new MinimalVitalSignsV0Composition();
        c.setLanguage(Language.EN);
        c.setTerritory(Territory.LT);
        c.setCategoryDefiningCode(Category.EVENT);
        c.setSettingDefiningCode(Setting.PRIMARY_MEDICAL_CARE);
        c.setComposer(composer(rnd));
        c.setHealthCareFacility(facility(rnd));
        c.setStartTimeValue(time);

        double systolic = Math.round(gauss(rnd, 130, 15, 90, 200));
        BloodPressureAnyEventPointEvent bpEvent = new BloodPressureAnyEventPointEvent();
        bpEvent.setTimeValue(time);
        bpEvent.setSystolicMagnitude(systolic);
        bpEvent.setSystolicUnits("mm[Hg]");
        bpEvent.setDiastolicMagnitude((double) Math.round(gauss(rnd, 82, 10, 50, systolic - 20)));
        bpEvent.setDiastolicUnits("mm[Hg]");
        BloodPressureObservation bp = new BloodPressureObservation();
        bp.setSubject(new PartySelf());
        bp.setLanguage(Language.EN);
        bp.setOriginValue(time);
        bp.setAnyEvent(List.of(bpEvent));
        c.setBloodPressure(bp);

        if (rnd.nextDouble() < 0.9) {
            PulseHeartBeatAnyEventPointEvent ev = new PulseHeartBeatAnyEventPointEvent();
            ev.setTimeValue(time);
            ev.setRateMagnitude((double) Math.round(gauss(rnd, 76, 11, 45, 140)));
            ev.setRateUnits("/min");
            PulseHeartBeatObservation o = new PulseHeartBeatObservation();
            o.setSubject(new PartySelf());
            o.setLanguage(Language.EN);
            o.setOriginValue(time);
            o.setAnyEvent(List.of(ev));
            c.setPulseHeartBeat(o);
        }
        if (rnd.nextDouble() < 0.6) {
            BodyTemperatureAnyEventPointEvent ev = new BodyTemperatureAnyEventPointEvent();
            ev.setTimeValue(time);
            ev.setTemperatureMagnitude(round1(gauss(rnd, 36.8, 0.4, 35.5, 40.5)));
            ev.setTemperatureUnits("Cel");
            BodyTemperatureObservation o = new BodyTemperatureObservation();
            o.setSubject(new PartySelf());
            o.setLanguage(Language.EN);
            o.setOriginValue(time);
            o.setAnyEvent(List.of(ev));
            c.setBodyTemperature(o);
        }
        if (rnd.nextDouble() < 0.5) {
            BodyWeightAnyEventPointEvent ev = new BodyWeightAnyEventPointEvent();
            ev.setTimeValue(time);
            ev.setWeightMagnitude(round1(gauss(rnd, 80, 15, 45, 160)));
            ev.setWeightUnits("kg");
            BodyWeightObservation o = new BodyWeightObservation();
            o.setSubject(new PartySelf());
            o.setLanguage(Language.EN);
            o.setOriginValue(time);
            o.setAnyEvent(List.of(ev));
            c.setBodyWeight(o);
        }
        return toRm(c);
    }
}
