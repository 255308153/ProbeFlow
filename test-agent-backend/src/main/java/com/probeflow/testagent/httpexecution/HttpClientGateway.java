package com.probeflow.testagent.httpexecution;

public interface HttpClientGateway {

    HttpClientResponse execute(HttpClientRequest request, HttpExecutionOptions options);
}
