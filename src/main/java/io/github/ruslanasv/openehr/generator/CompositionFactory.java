package io.github.ruslanasv.openehr.generator;

import com.nedap.archie.rm.composition.Composition;
import java.util.Random;

/** Builds random, template-valid compositions for one template. */
public interface CompositionFactory {

    String templateId();

    /** Same {@code rnd} state produces the same composition. */
    Composition create(Random rnd);
}
