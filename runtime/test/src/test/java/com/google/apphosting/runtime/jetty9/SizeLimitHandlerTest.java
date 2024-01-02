/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.apphosting.runtime.jetty9;

import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.client.util.DeferredContentProvider;
import org.eclipse.jetty.client.util.InputStreamContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.lessThan;

@RunWith(JUnit4.class)
public class SizeLimitHandlerTest extends JavaRuntimeViaHttpBase {

  private static final int MAX_SIZE = 32 * 1024 * 1024;

  @Rule public TemporaryFolder temp = new TemporaryFolder();
  HttpClient httpClient = new HttpClient();

  @Before
  public void copyAppToTemp() throws Exception {
    copyAppToDir("sizelimitee10", temp.getRoot().toPath());
    httpClient.start();
  }

  @After
  public void after() throws Exception
  {
    httpClient.stop();
  }

  @Test
  public void testResponseContentBelowMaxLength() throws Exception {
    try (RuntimeContext<?> runtime = runtimeContext()) {

      assertRequestClassContains(runtime, "ee10");

      long contentLength = MAX_SIZE;
      String url = runtime.jettyUrl("/?size=" + contentLength);
      CompletableFuture<Result> completionListener = new CompletableFuture<>();
      AtomicLong contentReceived = new AtomicLong();
      httpClient.newRequest(url).onResponseContentAsync((response, content, callback) -> {
        contentReceived.addAndGet(content.remaining());
        callback.succeeded();
      }).header("setCustomHeader", "true").send(completionListener::complete);

      Result result = completionListener.get(5, TimeUnit.SECONDS);
      assertThat(result.getResponse().getStatus(), equalTo(HttpStatus.OK_200));
      assertThat(contentReceived.get(), equalTo(contentLength));
      assertThat(result.getResponse().getHeaders().get("custom-header"), equalTo("true"));
    }
  }

  @Test
  public void testResponseContentAboveMaxLength() throws Exception {
    try (RuntimeContext<?> runtime = runtimeContext()) {

      assertRequestClassContains(runtime, "ee10");

      long contentLength = MAX_SIZE + 1;
      String url = runtime.jettyUrl("/?size=" + contentLength);
      ContentResponse response = httpClient.GET(url);
      assertThat(response.getStatus(), equalTo(HttpStatus.INTERNAL_SERVER_ERROR_500));
      assertThat(response.getContentAsString(), containsString("Response body is too large"));
    }
  }

  @Test
  public void testResponseContentBelowMaxLengthGzip() throws Exception {
    try (RuntimeContext<?> runtime = runtimeContext()) {

      assertRequestClassContains(runtime, "ee10");

      long contentLength = MAX_SIZE;
      String url = runtime.jettyUrl("/?size=" + contentLength);
      CompletableFuture<Result> completionListener = new CompletableFuture<>();
      AtomicLong contentReceived = new AtomicLong();
      httpClient.getContentDecoderFactories().clear();
      httpClient.newRequest(url)
        .onResponseContentAsync((response, content, callback) ->
        {
          contentReceived.addAndGet(content.remaining());
          callback.succeeded();
        })
        .header(HttpHeader.ACCEPT_ENCODING, "gzip")
        .send(completionListener::complete);

      Result result = completionListener.get(5, TimeUnit.SECONDS);
      assertThat(result.getResponse().getHeaders().get(HttpHeader.CONTENT_ENCODING), equalTo("gzip"));
      assertThat(result.getResponse().getStatus(), equalTo(HttpStatus.OK_200));
      assertThat(contentReceived.get(), lessThan(contentLength));
    }
  }

  @Test
  public void testResponseContentAboveMaxLengthGzip() throws Exception {
    try (RuntimeContext<?> runtime = runtimeContext()) {

      assertRequestClassContains(runtime, "ee10");

      long contentLength = MAX_SIZE + 1;
      String url = runtime.jettyUrl("/?size=" + contentLength);
      httpClient.getContentDecoderFactories().clear();
      ContentResponse response = httpClient.newRequest(url)
              .header(HttpHeader.ACCEPT_ENCODING, "gzip")
              .send();

      assertThat(response.getStatus(), equalTo(HttpStatus.INTERNAL_SERVER_ERROR_500));
      assertThat(response.getContentAsString(), containsString("Response body is too large"));
    }
  }

  @Test
  public void testRequestContentBelowMaxLength() throws Exception {
    try (RuntimeContext<?> runtime = runtimeContext()) {

      assertRequestClassContains(runtime, "ee10");
      int contentLength = MAX_SIZE;

      byte[] data = new byte[contentLength];
      Arrays.fill(data, (byte)'X');
      ContentProvider content = new ByteBufferContentProvider(BufferUtil.toBuffer(data));
      String url = runtime.jettyUrl("/");
      ContentResponse response = httpClient.newRequest(url).content(content).send();

      assertThat(response.getStatus(), equalTo(HttpStatus.OK_200));
      assertThat(response.getContentAsString(), containsString("RequestContentLength: " + contentLength));
    }
  }

  @Test
  public void testRequestContentAboveMaxLength() throws Exception {
    try (RuntimeContext<?> runtime = runtimeContext()) {

      assertRequestClassContains(runtime, "ee10");
      int contentLength = MAX_SIZE + 1;

      CompletableFuture<Result> completionListener = new CompletableFuture<>();
      byte[] data = new byte[contentLength];
      Arrays.fill(data, (byte)'X');
      Utf8StringBuilder received = new Utf8StringBuilder();
      ContentProvider content = new ByteBufferContentProvider(BufferUtil.toBuffer(data));
      String url = runtime.jettyUrl("/");
      httpClient.newRequest(url).content(content)
        .onResponseContentAsync((response, content1, callback) ->
        {
          received.append(content1);
          callback.succeeded();
        })
        .send(completionListener::complete);

      Result result = completionListener.get(5, TimeUnit.SECONDS);
      assertThat(result.getResponse().getStatus(), equalTo(HttpStatus.PAYLOAD_TOO_LARGE_413));
      assertThat(received.toString(), containsString("Request body is too large"));
    }
  }

  @Test
  public void testRequestContentBelowMaxLengthGzip() throws Exception {
    try (RuntimeContext<?> runtime = runtimeContext()) {

      assertRequestClassContains(runtime, "ee10");
      int contentLength = MAX_SIZE;

      CompletableFuture<Result> completionListener = new CompletableFuture<>();
      byte[] data = new byte[contentLength];
      Arrays.fill(data, (byte)'X');
      Utf8StringBuilder received = new Utf8StringBuilder();
      ContentProvider content = new InputStreamContentProvider(gzip(data));

      String url = runtime.jettyUrl("/");
      httpClient.newRequest(url).content(content)
        .onResponseContentAsync((response, content1, callback) ->
        {
          received.append(content1);
          callback.succeeded();
        })
        .header(HttpHeader.CONTENT_ENCODING, "gzip")
        .send(completionListener::complete);

      Result result = completionListener.get(5, TimeUnit.SECONDS);
      assertThat(result.getResponse().getStatus(), equalTo(HttpStatus.OK_200));
      assertThat(received.toString(), containsString("RequestContentLength: " + contentLength));
    }
  }

  @Test
  public void testRequestContentAboveMaxLengthGzip() throws Exception {
    try (RuntimeContext<?> runtime = runtimeContext()) {

      assertRequestClassContains(runtime, "ee10");
      int contentLength = MAX_SIZE + 1;

      CompletableFuture<Result> completionListener = new CompletableFuture<>();
      byte[] data = new byte[contentLength];
      Arrays.fill(data, (byte)'X');
      Utf8StringBuilder received = new Utf8StringBuilder();
      ContentProvider content = new InputStreamContentProvider(gzip(data));

      String url = runtime.jettyUrl("/");
      httpClient.newRequest(url).content(content)
              .onResponseContentAsync((response, content1, callback) ->
              {
                received.append(content1);
                callback.succeeded();
              })
              .header(HttpHeader.CONTENT_ENCODING, "gzip")
              .send(completionListener::complete);

      Result result = completionListener.get(5, TimeUnit.SECONDS);
      assertThat(result.getResponse().getStatus(), equalTo(HttpStatus.PAYLOAD_TOO_LARGE_413));
      assertThat(received.toString(), containsString("Request body is too large"));
    }
  }

  @Test
  public void testResponseContentLengthHeader() throws Exception {
    try (RuntimeContext<?> runtime = runtimeContext()) {

      assertRequestClassContains(runtime, "ee10");

      long contentLength = MAX_SIZE + 1;
      String url = runtime.jettyUrl("/?setContentLength=" + contentLength);
      httpClient.getContentDecoderFactories().clear();
      ContentResponse response = httpClient.newRequest(url).send();

      assertThat(response.getStatus(), equalTo(HttpStatus.INTERNAL_SERVER_ERROR_500));
      assertThat(response.getContentAsString(), containsString("Response body is too large"));
    }
  }

  @Test
  public void testRequestContentLengthHeader() throws Exception {
    try (RuntimeContext<?> runtime = runtimeContext()) {

       assertRequestClassContains(runtime, "ee10");

      CompletableFuture<Result> completionListener = new CompletableFuture<>();
      DeferredContentProvider provider = new DeferredContentProvider();
      int contentLength = MAX_SIZE + 1;
      String url = runtime.jettyUrl("/");
      Utf8StringBuilder received = new Utf8StringBuilder();
      httpClient.newRequest(url)
              .header(HttpHeader.CONTENT_LENGTH, Long.toString(contentLength))
              .header("foo", "bar")
              .content(provider)
              .onResponseContentAsync((response, content, callback) ->
              {
                received.append(content);
                callback.succeeded();
                provider.close();
              })
              .send(completionListener::complete);

      Result result = completionListener.get(5, TimeUnit.SECONDS);
      Response response = result.getResponse();
      assertThat(response.getStatus(), equalTo(HttpStatus.PAYLOAD_TOO_LARGE_413));
      assertThat(received.toString(), containsString("Request body is too large"));
    }
  }

  private RuntimeContext<?> runtimeContext() throws Exception {
    RuntimeContext.Config<?> config =
        RuntimeContext.Config.builder().setApplicationPath(temp.getRoot().toString()).build();
    return RuntimeContext.create(config);
  }

  private void assertRequestClassContains(RuntimeContext<?> runtime, String match) throws Exception
  {
    String runtimeUrl = runtime.jettyUrl("/?getRequestClass=true");
    ContentResponse response = httpClient.GET(runtimeUrl);
    assertThat(response.getContentAsString(), containsString(match));
  }

  private static InputStream gzip(byte[] data) throws IOException {
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
      gzipOutputStream.write(data);
    }
    return new ByteArrayInputStream(byteArrayOutputStream.toByteArray());
  }
}
