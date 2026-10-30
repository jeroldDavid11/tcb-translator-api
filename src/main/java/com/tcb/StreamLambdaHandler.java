package com.tcb;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler;
import com.amazonaws.serverless.proxy.spring.SpringBootProxyHandlerBuilder;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;

public class StreamLambdaHandler implements RequestHandler<AwsProxyRequest, AwsProxyResponse> {
  private static SpringBootLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> handler;

  static {
    try {
      handler = new SpringBootProxyHandlerBuilder<AwsProxyRequest>()
          .defaultProxy()                           // <- IMPORTANT for REST API
          .springBootApplication(TcbTranslatorApiApplication.class)
          .buildAndInitialize();
    } catch (ContainerInitializationException e) {
      throw new RuntimeException("Could not initialize Spring Boot application", e);
    }
  }

  @Override
  public AwsProxyResponse handleRequest(AwsProxyRequest input, Context context) {
    return handler.proxy(input, context);
  }
}
