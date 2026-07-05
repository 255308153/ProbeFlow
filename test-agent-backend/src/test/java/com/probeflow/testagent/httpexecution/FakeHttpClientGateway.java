package com.probeflow.testagent.httpexecution;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class FakeHttpClientGateway implements HttpClientGateway {

    private final List<HttpClientRequest> requests = new ArrayList<>();
    private HttpClientResponse response = new HttpClientResponse(200, Map.of(), Map.of("ok", true), 1L);

    @Override
    public HttpClientResponse execute(HttpClientRequest request, HttpExecutionOptions options) {
        requests.add(request);
        return response;
    }

    void respondWith(HttpClientResponse response) {
        this.response = response;
    }

    List<HttpClientRequest> requests() {
        return List.copyOf(requests);
    }

    void reset() {
        requests.clear();
        response = new HttpClientResponse(200, Map.of(), Map.of("ok", true), 1L);
    }
}
