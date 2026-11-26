package io.github.ruslanasv.openehr.generator;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.apache.http.HttpHeaders;
import org.apache.http.client.HttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.ehrbase.openehr.sdk.client.openehrclient.OpenEhrClientConfig;
import org.ehrbase.openehr.sdk.client.openehrclient.defaultrestclient.DefaultRestClient;
import org.ehrbase.openehr.sdk.webtemplate.templateprovider.TemplateProvider;

/** Creates openEHR SDK REST clients for EHRbase. */
public final class EhrbaseClients {

    private EhrbaseClients() {}

    /**
     * SDK client with HTTP basic auth (sent pre-emptively on every request) and a connection pool
     * large enough for {@code parallelRequests} concurrent calls (Apache's default is only 2 per host).
     */
    public static DefaultRestClient create(GeneratorOptions options, TemplateProvider templateProvider) {
        String credentials = Base64.getEncoder()
                .encodeToString((options.user() + ":" + options.password()).getBytes(StandardCharsets.UTF_8));
        HttpClient httpClient = HttpClientBuilder.create()
                .setDefaultHeaders(List.of(new BasicHeader(HttpHeaders.AUTHORIZATION, "Basic " + credentials)))
                .setMaxConnPerRoute(options.threads())
                .setMaxConnTotal(options.threads())
                .build();
        return new DefaultRestClient(new OpenEhrClientConfig(options.baseUri()), templateProvider, httpClient);
    }
}
