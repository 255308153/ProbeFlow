package com.probeflow.testagent.httpexecution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;

public class FakeHttpClientGateway implements HttpClientGateway {

    private final List<HttpClientRequest> requests = new ArrayList<>();
    private final Deque<Object> outcomes = new ArrayDeque<>();
    private HttpClientResponse response = new HttpClientResponse(200, Map.of(), Map.of("ok", true), 1L);
    private RuntimeException failure;

    @Override
    public HttpClientResponse execute(HttpClientRequest request, HttpExecutionOptions options) {
        requests.add(request);
        if (!outcomes.isEmpty()) {
            var outcome = outcomes.removeFirst();
            if (outcome instanceof RuntimeException exception) {
                throw exception;
            }
            return (HttpClientResponse) outcome;
        }
        if (failure != null) {
            throw failure;
        }
        return response;
    }

    public void respondWith(HttpClientResponse response) {
        this.response = response;
        this.failure = null;
        this.outcomes.clear();
    }

    public void respondWithSequence(HttpClientResponse... responses) {
        outcomes.clear();
        failure = null;
        outcomes.addAll(Arrays.asList(responses));
    }

    public void failWith(RuntimeException failure) {
        this.failure = failure;
        this.outcomes.clear();
    }

    public List<HttpClientRequest> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        requests.clear();
        outcomes.clear();
        response = new HttpClientResponse(200, Map.of(), Map.of("ok", true), 1L);
        failure = null;
    }
}
