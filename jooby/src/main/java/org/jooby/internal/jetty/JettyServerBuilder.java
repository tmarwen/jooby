/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jooby.internal.jetty;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.servlet.SessionCookieConfig;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.component.AbstractLifeCycle.AbstractLifeCycleListener;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Slf4jLog;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.server.WebSocketServerFactory;
import org.jooby.Session;
import org.jooby.Session.Store;
import org.jooby.WebSocket;
import org.jooby.WebSocket.Definition;
import org.jooby.internal.RouteHandler;
import org.jooby.internal.WebSocketImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.util.Types;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigException;

public class JettyServerBuilder {

  /** The logging system. */
  private static final Logger log = LoggerFactory.getLogger(org.jooby.internal.Server.class);
  protected static final String WebSocketImpl = null;

  public static Server build(final Config config, final RouteHandler routeHandler)
      throws Exception {
    // jetty URL charset
    System.setProperty("org.eclipse.jetty.util.UrlEncoded.charset",
        config.getString("application.charset"));
    System.setProperty("org.mortbay.log.class", Slf4jLog.class.getName());

    QueuedThreadPool threads = new QueuedThreadPool(config.getInt("jetty.threads.max"),
        config.getInt("jetty.threads.min"));
    threads.setIdleTimeout(config.getInt("jetty.threads.timeout"));

    Server server = new Server(threads);
    // stop is done from Jooby
    server.setStopAtShutdown(false);

    Config $ = config.getConfig("jetty");

    HttpConfiguration httpConfig = configure(new HttpConfiguration(), $.getConfig("http"));

    httpConfig.setSecurePort(config.getInt("application.securePort"));
    httpConfig.setSecureScheme("https");

    // HTTP connector
    ServerConnector http = configure(new ServerConnector(server, new HttpConnectionFactory(
        httpConfig)), $.getConfig("http"));
    http.setPort(config.getInt("application.port"));

    server.addConnector(http);

    String keystorePath = config.getString("ssl.keystore.path");
    Resource keystore = Resource.newClassPathResource(keystorePath);
    if (keystore == null || !keystore.exists()) {
      keystore = Resource.newResource(keystorePath);
    }

    if (keystore.exists()) {
      SslContextFactory sslContextFactory = new SslContextFactory();
      sslContextFactory.setKeyStoreResource(keystore);
      sslContextFactory.setKeyStorePassword(config.getString("ssl.keystore.password"));
      sslContextFactory.setKeyManagerPassword(config.getString("ssl.keymanager.password"));

      // get config from https if present and/or fallback to http
      HttpConfiguration httpsConfig = configure(new HttpConfiguration(httpConfig),
          $.getConfig("https"));

      httpsConfig.addCustomizer(new SecureRequestCustomizer());

      ServerConnector https = configure(new ServerConnector(server,
          new SslConnectionFactory(sslContextFactory, "HTTP/1.1"),
          new HttpConnectionFactory(httpsConfig)),
          $.getConfig("https"));
      https.setPort(config.getInt("application.securePort"));

      server.addConnector(https);
    }

    // contextPath
    String contextPath = config.getString("application.path");

    ContextHandler context = new ContextHandler(contextPath);
    JettyHandler handler = new JettyHandler(routeHandler, config);
    Injector injector = routeHandler.injector();
    Session.Definition sessionDef = injector.getInstance(Session.Definition.class);

    /**
     * Session configuration
     */
    Session.Store store = sessionDef.store();
    Session.Store forwardingStore = fwdStore(store);
    String secret = config.hasPath("application.secret")
        ? config.getString("application.secret") : null;
    JoobySessionManager sessionManager = new JoobySessionManager(forwardingStore, secret);
    Config $session = config.getConfig("application.session");

    // session cookie config
    SessionCookieConfig sessionCookieConfig = sessionManager.getSessionCookieConfig();
    org.jooby.Cookie.Definition cookieDef = sessionDef.cookie();
    sessionCookieConfig.setComment(cookieDef.comment()
        .orElse($session.hasPath("cookie.comment") ? $session.getString("cookie.path") : null)
        );
    sessionCookieConfig.setDomain(cookieDef.domain()
        .orElse($session.hasPath("cookie.domain") ? $session.getString("cookie.domain") : null)
        );
    sessionCookieConfig.setHttpOnly(cookieDef.httpOnly()
        .orElse($session.getBoolean("cookie.httpOnly"))
        );
    sessionCookieConfig.setMaxAge(cookieDef.maxAge()
        .orElse((int) duration($session, "cookie.maxAge", TimeUnit.SECONDS))
        );
    sessionCookieConfig.setName(cookieDef.name()
        .orElse($session.getString("cookie.name"))
        );
    sessionCookieConfig.setPath(cookieDef.path()
        .orElse($session.getString("cookie.path"))
        );
    sessionCookieConfig.setSecure(cookieDef.secure()
        .orElse($session.getBoolean("cookie.secure"))
        );

    // session timeout
    sessionManager.setMaxInactiveInterval(sessionDef.timeout().orElse(
        (int) duration($session, "timeout", TimeUnit.SECONDS))
        );

    sessionManager.setSaveInterval(sessionDef.saveInterval().orElse(
        (int) duration($session, "saveInterval", TimeUnit.SECONDS))
        );

    sessionManager.setPreserveOnStop(sessionDef.preserveOnStop().orElse(
        $session.getBoolean("preserveOnStop"))
        );

    handler.setSessionManager(sessionManager);

    /**
     * Web sockets
     */
    @SuppressWarnings("unchecked")
    Set<WebSocket.Definition> sockets = (Set<Definition>) injector.getInstance(Key.get(Types
        .setOf(WebSocket.Definition.class)));

    if (sockets.size() > 0) {
      WebSocketPolicy policy = configure(new WebSocketPolicy(WebSocketBehavior.SERVER),
          config.getConfig("jetty.ws.policy"));
      WebSocketServerFactory socketServerFactory = new WebSocketServerFactory(policy);
      socketServerFactory.setCreator((req, resp) -> {
     String path = req.getRequestPath();
     for (WebSocket.Definition socketDef : sockets) {
      Optional<WebSocket> matches = socketDef.matches(path);
      if (matches.isPresent()) {
        WebSocketImpl socket = (WebSocketImpl) matches.get();
        return new JettyWebSocketHandler(injector, config, socket);
      }
     }
     return null;
    });

      handler.addBean(socketServerFactory);
    }

    context.setHandler(handler);

    server.setHandler(context);

    server.addLifeCycleListener(new AbstractLifeCycleListener() {
      @Override
      public void lifeCycleStarted(final LifeCycle event) {
        StringBuilder buffer = new StringBuilder(routeHandler.toString());
        if (sockets.size() > 0) {
          buffer.append("\nweb sockets:\n");

          int verbMax = "WS".length(), routeMax = 0, consumesMax = 0, producesMax = 0;
          for (WebSocket.Definition socketDef : sockets) {
            routeMax = Math.max(routeMax, socketDef.pattern().length());

            consumesMax = Math.max(consumesMax, socketDef.consumes().toString().length());

            producesMax = Math.max(producesMax, socketDef.produces().toString().length());
          }

          String format = "  %-" + verbMax + "s %-" + routeMax + "s    %" + consumesMax + "s     %"
              + producesMax + "s\n";

          for (WebSocket.Definition socketDef : sockets) {
            buffer.append(String.format(format, "WS", socketDef.pattern(),
                socketDef.consumes(), socketDef.produces()));
          }
        }
        buffer.append("\nlistening on:\n");
        buffer.append("  http://localhost:").append(config.getInt("application.port"))
            .append(contextPath).append("\n");
        buffer.append("  https://localhost:").append(config.getInt("application.securePort"))
            .append(contextPath).append("\n");

        log.info("\nroutes:\n{}", buffer);
      }
    });

    return server;
  }

  private static Store fwdStore(final Session.Store store) {
    return new Session.Store() {

      @Override
      public Session get(final Session.Builder builder) throws Exception {
        return store.get(builder);
      }

      @Override
      public void save(final Session session, final SaveReason reason) throws Exception {
        store.save(session, reason);
        ((JoobySession) session).setLastSave(System.currentTimeMillis());
      }

      @Override
      public void delete(final String id) throws Exception {
        store.delete(id);
      }

      @Override
      public String generateID(final long seed) {
        return store.generateID(seed);
      }
    };
  }

  private static long duration(final Config config, final String name, final TimeUnit unit) {
    try {
    return config.getLong(name);
    } catch (ConfigException.WrongType ex) {
      return config.getDuration(name, unit);
    }
  }

  private static <T> T configure(final T target, final Config config) {
    Class<?> clazz = target.getClass();
    Set<Method> methods = Arrays.stream(clazz.getMethods())
        .filter(m -> m.getName().startsWith("set") && m.getParameterCount() == 1)
        .collect(org.jooby.fn.Collectors.toSet());

    /**
     * Generate set method name.
     */
    Function<String, String> dotted = name -> {
      StringBuilder methodName = new StringBuilder();
      int start = "set".length();
      methodName.append(Character.toLowerCase(name.charAt(start)));
      for(int i = start + 1; i < name.length(); i++) {
        char ch = name.charAt(i);
        if (Character.isUpperCase(ch)) {
          methodName.append('.').append(Character.toLowerCase(ch));
        } else {
          methodName.append(ch);
        }
      }

      return methodName.toString();
    };
    Function<String, String> direct = name -> {
      StringBuilder methodName = new StringBuilder();
      int start = "set".length();
      methodName.append(Character.toLowerCase(name.charAt(start)));
      methodName.append(name.substring(start + 1));

      return methodName.toString();
    };

    for (Method method : methods) {
      String name = dotted.apply(method.getName());
      if (!config.hasPath(name)) {
        name = direct.apply(method.getName());
        if (!config.hasPath(name)) {
          continue;
        }
      }
      try {
        Class<?> paramType = method.getParameterTypes()[0];
        final Object value;
        if (paramType == int.class) {
          value = config.getInt(name);
        } else if (paramType == long.class) {
          value = config.getLong(name);
        } else if (paramType == boolean.class) {
          value = config.getBoolean(name);
        } else {
          value = config.getString(name);
        }
        method.invoke(target, value);
      } catch (IllegalAccessException | InvocationTargetException ex) {
        throw new IllegalArgumentException("Bad property value jetty." + name, ex);
      }
    }
    return target;
  }

}
