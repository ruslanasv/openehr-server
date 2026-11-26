package io.github.ruslanasv.openehr.generator;

import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.generic.PartyIdentified;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Random;
import org.ehrbase.openehr.sdk.serialisation.dto.GeneratedDtoToRmConverter;

/**
 * Shared helpers: random clinical context and conversion of generated SDK entities
 * (see target/generated-sources/ehrbase-generator) to RM compositions.
 */
abstract class AbstractCompositionFactory implements CompositionFactory {

    static final ZoneId ZONE = ZoneId.of("Europe/Vilnius");

    private final GeneratedDtoToRmConverter converter;
    private final Instant anchor;

    /** @param anchor generated times lie within the year before this instant */
    AbstractCompositionFactory(GeneratedDtoToRmConverter converter, Instant anchor) {
        this.converter = converter;
        this.anchor = anchor.truncatedTo(ChronoUnit.MINUTES);
    }

    Composition toRm(Object entity) {
        return (Composition) converter.toRMObject(entity);
    }

    OffsetDateTime randomTime(Random rnd) {
        return anchor.minus(rnd.nextInt(365 * 24 * 60), ChronoUnit.MINUTES).atZone(ZONE).toOffsetDateTime();
    }

    static PartyIdentified composer(Random rnd) {
        return new PartyIdentified(null, pick(rnd, ReferenceData.CLINICIANS), null);
    }

    static PartyIdentified facility(Random rnd) {
        return new PartyIdentified(null, pick(rnd, ReferenceData.FACILITIES), null);
    }

    static <T> T pick(Random rnd, List<T> list) {
        return list.get(rnd.nextInt(list.size()));
    }

    static double gauss(Random rnd, double mean, double sd, double min, double max) {
        return Math.max(min, Math.min(max, mean + rnd.nextGaussian() * sd));
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
