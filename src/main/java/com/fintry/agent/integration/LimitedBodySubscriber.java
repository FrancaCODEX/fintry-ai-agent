package com.fintry.agent.integration;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
  private final int limit;
  private final ByteArrayOutputStream body = new ByteArrayOutputStream();
  private final CompletableFuture<byte[]> result = new CompletableFuture<>();
  private Flow.Subscription subscription;

  LimitedBodySubscriber(int limit) {
    this.limit = limit;
  }

  public CompletionStage<byte[]> getBody() {
    return result;
  }

  public void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    subscription.request(1);
  }

  public void onNext(List<ByteBuffer> buffers) {
    for (var buffer : buffers) {
      if (buffer.remaining() > limit - body.size()) {
        subscription.cancel();
        result.completeExceptionally(new java.io.IOException("Resposta excede limite"));
        return;
      }
      var bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      body.writeBytes(bytes);
    }
    subscription.request(1);
  }

  public void onError(Throwable error) {
    result.completeExceptionally(error);
  }

  public void onComplete() {
    result.complete(body.toByteArray());
  }
}
