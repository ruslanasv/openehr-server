package io.github.ruslanasv.openehr.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratorOptionsTest {

    @Test
    void defaults() {
        GeneratorOptions o = GeneratorOptions.parse();

        assertThat(o.baseUri()).hasToString("http://localhost:8080/ehrbase/");
        assertThat(o.ehrs()).isEqualTo(10);
        assertThat(o.compositionsPerEhr()).isEqualTo(3);
        assertThat(o.templateIds()).isEqualTo(CompositionFactories.TEMPLATE_IDS);
    }

    @Test
    void parsesOptionsAndNormalisesBaseUrl() {
        GeneratorOptions o = GeneratorOptions.parse("--ehrs", "100", "--compositions", "5", "--threads", "8",
                "--seed", "42", "--templates", "minimal_vital_signs.v0", "--base", "http://ehrbase-lb/ehrbase");

        assertThat(o.ehrs()).isEqualTo(100);
        assertThat(o.compositionsPerEhr()).isEqualTo(5);
        assertThat(o.threads()).isEqualTo(8);
        assertThat(o.seed()).isEqualTo(42);
        assertThat(o.templateIds()).isEqualTo(List.of("minimal_vital_signs.v0"));
        assertThat(o.baseUri()).hasToString("http://ehrbase-lb/ehrbase/");
    }

    @Test
    void rejectsUnknownOptionsAndTemplates() {
        assertThatThrownBy(() -> GeneratorOptions.parse("--foo", "1")).hasMessageContaining("Unknown option --foo");
        assertThatThrownBy(() -> GeneratorOptions.parse("--templates", "nope")).hasMessageContaining("Unknown template nope");
        assertThatThrownBy(() -> GeneratorOptions.parse("--ehrs", "0")).hasMessageContaining("must be >= 1");
    }
}
