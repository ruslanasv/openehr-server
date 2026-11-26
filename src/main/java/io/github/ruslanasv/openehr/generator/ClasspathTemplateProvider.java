package io.github.ruslanasv.openehr.generator;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.xmlbeans.XmlException;
import org.ehrbase.openehr.sdk.webtemplate.model.WebTemplate;
import org.ehrbase.openehr.sdk.webtemplate.templateprovider.TemplateProvider;
import org.openehr.schemas.v1.OPERATIONALTEMPLATE;
import org.openehr.schemas.v1.TemplateDocument;

/**
 * Serves the operational templates bundled in {@code src/main/resources/templates}.
 * Parsed templates and web templates are cached; safe for concurrent use.
 */
public class ClasspathTemplateProvider implements TemplateProvider {

    /** template id -> classpath resource */
    public static final Map<String, String> RESOURCES = Map.of(
            "medication_order", "/templates/medication_order.opt",
            "minimal_diagnosis.v0", "/templates/minimal_diagnosis.opt",
            "minimal_vital_signs.v0", "/templates/minimal_vital_signs.opt");

    private final Map<String, Optional<OPERATIONALTEMPLATE>> templates = new ConcurrentHashMap<>();
    private final Map<String, Optional<WebTemplate>> webTemplates = new ConcurrentHashMap<>();

    @Override
    public Optional<OPERATIONALTEMPLATE> find(String templateId) {
        return templates.computeIfAbsent(templateId, id -> Optional.ofNullable(RESOURCES.get(id)).map(this::parse));
    }

    @Override
    public Optional<WebTemplate> buildIntrospect(String templateId) {
        return webTemplates.computeIfAbsent(templateId, TemplateProvider.super::buildIntrospect);
    }

    private OPERATIONALTEMPLATE parse(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Template resource not found: " + resource);
            }
            return TemplateDocument.Factory.parse(in).getTemplate();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (XmlException e) {
            throw new IllegalStateException("Invalid template " + resource + ": " + e.getMessage(), e);
        }
    }
}
