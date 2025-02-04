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

package com.google.apphosting.runtime.jetty;

import com.google.apphosting.base.AppVersionKey;
import com.google.apphosting.base.protos.AppinfoPb;
import com.google.apphosting.base.protos.RuntimePb.UPRequest;
import com.google.apphosting.base.protos.RuntimePb.UPResponse;
import com.google.apphosting.runtime.AppVersion;
import com.google.apphosting.runtime.JettyConstants;
import com.google.apphosting.runtime.MutableUpResponse;
import com.google.apphosting.runtime.ServletEngineAdapter;
import com.google.apphosting.runtime.jetty.delegate.DelegateConnector;
import com.google.apphosting.runtime.jetty.delegate.impl.DelegateRpcExchange;
import com.google.apphosting.runtime.jetty.proxy.JettyHttpProxy;
import com.google.apphosting.utils.config.AppEngineConfigException;
import com.google.apphosting.utils.config.AppYaml;
import com.google.common.flogger.GoogleLogger;
import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * This is an implementation of ServletEngineAdapter that uses the third-party Jetty servlet engine.
 *
 */
public class JettyServletEngineAdapter implements ServletEngineAdapter {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();
  private static final String DEFAULT_APP_YAML_PATH = "/WEB-INF/appengine-generated/app.yaml";
  private static final int MIN_THREAD_POOL_THREADS = 0;
  private static final int MAX_THREAD_POOL_THREADS = 100;
  private static final long MAX_RESPONSE_SIZE = 32 * 1024 * 1024;

  /**
   * If Legacy Mode is tunred on, then Jetty is configured to be more forgiving of bad requests and
   * to act more in the style of Jetty-9.3
   */
  public static final boolean LEGACY_MODE =
      Boolean.getBoolean("com.google.apphosting.runtime.jetty94.LEGACY_MODE");

  private AppVersionKey lastAppVersionKey;

  static {
    // Set legacy system property to dummy value because external libraries (google-auth-library-java)
    // test if this value is null to decide whether it is Java 7 runtime.
    System.setProperty("org.eclipse.jetty.util.log.class", "DEPRECATED");

  }

  private Server server;
  private DelegateConnector rpcConnector;
  private AppVersionHandler appVersionHandler;

  public JettyServletEngineAdapter() {
  }

  private static AppYaml getAppYaml(ServletEngineAdapter.Config runtimeOptions) {
    String applicationPath = runtimeOptions.fixedApplicationPath();
    File appYamlFile = new File(applicationPath + DEFAULT_APP_YAML_PATH);

    AppYaml appYaml = null;
    try {
      appYaml = AppYaml.parse(new InputStreamReader(new FileInputStream(appYamlFile), UTF_8));
    } catch (FileNotFoundException | AppEngineConfigException e) {
      logger.atWarning().log("Failed to load app.yaml file at location %s - %s",
          appYamlFile.getPath(), e.getMessage());
    }
    return appYaml;
  }

  @Override
  public void start(String serverInfo, ServletEngineAdapter.Config runtimeOptions) {
    QueuedThreadPool threadPool =
        new QueuedThreadPool(MAX_THREAD_POOL_THREADS, MIN_THREAD_POOL_THREADS);
    // Try to enable virtual threads if requested and on java21:
    if (Boolean.getBoolean("appengine.use.virtualthreads")
        && "java21".equals(System.getenv("GAE_RUNTIME"))) {
      threadPool.setVirtualThreadsExecutor(VirtualThreads.getDefaultVirtualThreadsExecutor());
      logger.atInfo().log("Configuring Appengine web server virtual threads.");
    }

    // The server.getDefaultStyleSheet() returns is returning null because of some classloading issue,
    // so we get the StyleSheet here to ensure it returns the correct value.
    Resource styleSheet = ResourceFactory.root().newResource(getClass().getClassLoader().getResource("jetty-dir.css"));
    server =
        new Server(threadPool) {
          @Override
          public InvocationType getInvocationType() {
            return InvocationType.BLOCKING;
          }

          @Override
          public Resource getDefaultStyleSheet() {
            return styleSheet;
          }
        };

    rpcConnector = new DelegateConnector(server, "RPC") {
      @Override
      public void run(Runnable runnable) {
        // Override this so that it does the initial run in the same thread.
        // Currently, we block until completion in serviceRequest() so no point starting new thread.
        runnable.run();
      }
    };

    server.addConnector(rpcConnector);
    AppVersionHandlerFactory appVersionHandlerFactory = AppVersionHandlerFactory.newInstance(server, serverInfo);
    appVersionHandler = new AppVersionHandler(appVersionHandlerFactory);

    if (!"java8".equals(System.getenv("GAE_RUNTIME"))) {
      CoreSizeLimitHandler sizeLimitHandler = new CoreSizeLimitHandler(-1, MAX_RESPONSE_SIZE);
      sizeLimitHandler.setHandler(appVersionHandler);
      server.setHandler(sizeLimitHandler);
    } else {
      server.setHandler(appVersionHandler);
    }

    if (runtimeOptions.useJettyHttpProxy()) {
      server.setAttribute("com.google.apphosting.runtime.jetty.appYaml",
              JettyServletEngineAdapter.getAppYaml(runtimeOptions));
      JettyHttpProxy.startServer(runtimeOptions);
    }

    try {
      server.start();
    } catch (Exception ex) {
      // TODO: Should we have a wrapper exception for this
      // type of thing in ServletEngineAdapter?
      throw new RuntimeException(ex);
    }
  }

  @Override
  public void stop() {
    try {
      server.stop();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public void addAppVersion(AppVersion appVersion) throws FileNotFoundException {
    appVersionHandler.addAppVersion(appVersion);
  }

  @Override
  public void deleteAppVersion(AppVersion appVersion) {
    appVersionHandler.removeAppVersion(appVersion.getKey());
  }

  /**
   * Sets the {@link com.google.apphosting.runtime.SessionStoreFactory} that will be used to create
   * the list of {@link com.google.apphosting.runtime.SessionStore SessionStores} to which the HTTP
   * Session will be stored, if sessions are enabled. This method must be invoked after {@link #start(String, Config)}.
   */
  @Override
  public void setSessionStoreFactory(com.google.apphosting.runtime.SessionStoreFactory factory) {
    appVersionHandler.setSessionStoreFactory(factory);
  }

  @Override
  public void serviceRequest(UPRequest upRequest, MutableUpResponse upResponse) throws Exception {
    if (upRequest.getHandler().getType() != AppinfoPb.Handler.HANDLERTYPE.CGI_BIN_VALUE) {
      upResponse.setError(UPResponse.ERROR.UNKNOWN_HANDLER_VALUE);
      upResponse.setErrorMessage("Unsupported handler type: " + upRequest.getHandler().getType());
      return;
    }

    // Optimise this adaptor assuming one deployed appVersionKey, so use the last one if it matches
    // and only check the handler is available if we see a new/different key.
    AppVersionKey appVersionKey = AppVersionKey.fromUpRequest(upRequest);
    AppVersionKey lastVersionKey = lastAppVersionKey;

    if (lastVersionKey != null) {
      // We already have created the handler on the previous request, so no need to do another getHandler().
      // The two AppVersionKeys must be the same as we only support one app version.
      if (!Objects.equals(appVersionKey, lastVersionKey)) {
        upResponse.setError(UPResponse.ERROR.UNKNOWN_APP_VALUE);
        upResponse.setErrorMessage("Unknown app: " + appVersionKey);
        return;
      }
    }
    else {
      if (!appVersionHandler.ensureHandler(appVersionKey)) {
        upResponse.setError(UPResponse.ERROR.UNKNOWN_APP_VALUE);
        upResponse.setErrorMessage("Unknown app: " + appVersionKey);
        return;
      }

      lastAppVersionKey = appVersionKey;
    }

    // TODO: lots of compliance modes to handle.
    HttpConfiguration httpConfiguration = rpcConnector.getHttpConfiguration();
    httpConfiguration.setSendDateHeader(false);
    httpConfiguration.setSendServerVersion(false);
    httpConfiguration.setSendXPoweredBy(false);
    if (LEGACY_MODE) {
      httpConfiguration.setRequestCookieCompliance(CookieCompliance.RFC2965);
      httpConfiguration.setResponseCookieCompliance(CookieCompliance.RFC2965);
      httpConfiguration.setUriCompliance(UriCompliance.LEGACY);
    }

    DelegateRpcExchange rpcExchange = new DelegateRpcExchange(upRequest, upResponse);
    rpcExchange.setAttribute(JettyConstants.APP_VERSION_KEY_REQUEST_ATTR, appVersionKey);
    rpcConnector.service(rpcExchange);
    try {
      rpcExchange.awaitResponse();
    } catch (Throwable t) {
      Throwable error = t;
      if (error instanceof ExecutionException) {
        error = error.getCause();
      }

      upResponse.setError(UPResponse.ERROR.UNEXPECTED_ERROR_VALUE);
      upResponse.setErrorMessage("Unexpected Error: " + error);
    }
  }
}
