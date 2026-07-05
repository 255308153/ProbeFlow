package com.probeflow.testagent.httpexecution;

import org.springframework.stereotype.Component;

@Component
public class NoopHttpClientGateway implements HttpClientGateway {

    @Override
    public HttpClientResponse execute(HttpClientRequest request, HttpExecutionOptions options) {
        throw new IllegalStateException("No HTTP client gateway configured for execution");
    }
}
