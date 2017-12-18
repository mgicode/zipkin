/**
 * Copyright 2015-2017 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package zipkin.storage;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import zipkin.Annotation;
import zipkin.BinaryAnnotation;
import zipkin.Span;
import zipkin.internal.CallbackCaptor;
import zipkin.internal.MergeById;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin.Constants.CLIENT_RECV;
import static zipkin.Constants.CLIENT_SEND;
import static zipkin.Constants.SERVER_RECV;
import static zipkin.Constants.SERVER_SEND;
import static zipkin.TestObjects.APP_ENDPOINT;
import static zipkin.TestObjects.TODAY;
import static zipkin.TestObjects.WEB_ENDPOINT;

/**
 * Base test for when {@link StorageComponent.Builder#strictTraceId(boolean) strictTraceId ==
 * true}.
 *
 * <p>Subtypes should create a connection to a real backend, even if that backend is in-process.
 */
public abstract class StrictTraceIdFalseTest {

  /** Should maintain state between multiple calls within a test. */
  protected abstract StorageComponent storage();

  protected SpanStore store() {
    return storage().spanStore();
  }

  /** Blocks until the callback completes to allow read-your-writes consistency during tests. */
  protected void accept(Span... spans) {
    CallbackCaptor<Void> captor = new CallbackCaptor<>();
    storage().asyncSpanConsumer().accept(asList(spans), captor);
    captor.get(); // block on result
  }

  /** Clears store between tests. */
  @Before
  public abstract void clear() throws IOException;

  @Test
  public void getTraces_128BitTraceId_mixed() {
    List<Span> trace = MergeById.apply(acceptMixedTrace());

    assertThat(store().getTraces(QueryRequest.builder().build()))
      .containsExactly(trace);

    // search by 128-bit side's service and data
    assertThat(store().getTraces(QueryRequest.builder()
      .serviceName(WEB_ENDPOINT.serviceName)
      .addAnnotation("squirrel").build()))
      .containsExactly(trace);

    // search by 64-bit side's service and data
    assertThat(store().getTraces(QueryRequest.builder()
      .serviceName(APP_ENDPOINT.serviceName)
      .addBinaryAnnotation("foo", "bar")
      .build()))
      .containsExactly(trace);
  }

  @Test
  public void getTrace_retrieves128bitTraceIdByLower64Bits_mixed() {
    List<Span> trace = acceptMixedTrace();

    assertThat(store().getRawTrace(0L, trace.get(0).traceId))
      .containsOnlyElementsOf(trace);
    assertThat(store().getRawTrace(trace.get(0).traceIdHigh, trace.get(0).traceId))
      .containsOnlyElementsOf(trace);
  }

  private List<Span> acceptMixedTrace() {
    // Root started with 128-bit, but others downgraded to 64-bit trace IDs
    List<Span> trace = Arrays.asList(Span.builder().traceIdHigh(-1L).traceId(1L).id(1L).name("get")
        .timestamp(TODAY * 1000).duration(350_000L)
        .addAnnotation(Annotation.create(TODAY * 1000, SERVER_RECV, WEB_ENDPOINT))
        .addAnnotation(Annotation.create((TODAY + 300) * 1000, "squirrel", WEB_ENDPOINT))
        .addAnnotation(Annotation.create((TODAY + 350) * 1000, SERVER_SEND, WEB_ENDPOINT))
        .build(),
      Span.builder().traceIdHigh(-1L).traceId(1L).parentId(1L).id(2L).name("get")
        .timestamp((TODAY + 50) * 1000).duration(250_000L)
        .addAnnotation(Annotation.create((TODAY + 50) * 1000, CLIENT_SEND, WEB_ENDPOINT))
        .addAnnotation(Annotation.create((TODAY + 300) * 1000, CLIENT_RECV, WEB_ENDPOINT))
        .build(),
      Span.builder().traceId(1L).parentId(1L).id(2L).name("get")
        .timestamp((TODAY + 100) * 1000).duration(150_000L)
        .addAnnotation(Annotation.create((TODAY + 100) * 1000, SERVER_RECV, APP_ENDPOINT))
        .addAnnotation(Annotation.create((TODAY + 250) * 1000, SERVER_SEND, APP_ENDPOINT))
        .addBinaryAnnotation(BinaryAnnotation.create("foo", "bar", APP_ENDPOINT))
        .build()
    );

    // send separately, similar to how would occur normally
    accept(trace.get(2));
    accept(trace.get(1));
    accept(trace.get(0));
    return trace;
  }
}
