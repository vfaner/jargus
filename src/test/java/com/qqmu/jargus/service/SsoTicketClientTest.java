package com.qqmu.jargus.service;

import com.qqmu.jargus.entity.SsoConfig;
import com.qqmu.jargus.exception.SsoLoginException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsoTicketClientTest {

    private HttpServer server;
    private String baseUrl;
    private final SsoTicketClient client = new SsoTicketClient();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastMethod.set(exchange.getRequestMethod());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath();
            if ("/redirect".equals(path)) {
                exchange.getResponseHeaders().add("Location", "/elsewhere");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }
            if ("/large".equals(path)) {
                byte[] body = ("{\"pad\":\"" + "x".repeat(70 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
                return;
            }
            if ("/error".equals(path)) {
                send(exchange, 500, "{\"success\":false}");
                return;
            }
            if ("/missing".equals(path)) {
                send(exchange, 404, "{}");
                return;
            }
            send(exchange, 200, "{\"success\":true,\"username\":\"alice\"}");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void getAppendsEncodedTicketAndKeepsExistingQuery() {
        SsoConfig config = config("/validate?tenant=acme", "GET");
        Map<String, Object> result = client.validate(config, "a+b c");

        assertEquals("tenant=acme&ticket=a%2Bb+c", lastQuery.get());
        assertEquals("GET", lastMethod.get());
        assertEquals("alice", result.get("username"));
    }

    @Test
    void postSendsFormBody() {
        SsoConfig config = config("/validate", "POST");
        config.setTicketParam("st");
        client.validate(config, "tok");

        assertEquals("POST", lastMethod.get());
        assertEquals("st=tok", lastBody.get());
        assertEquals(null, lastQuery.get());
    }

    @Test
    void doesNotFollowRedirects() {
        SsoConfig config = config("/redirect", "GET");
        SsoLoginException e = assertThrows(SsoLoginException.class,
                () -> client.validate(config, "t"));
        assertTrue(e.getMessage().contains("302"));
    }

    @Test
    void rejectsNon2xx() {
        SsoConfig config = config("/error", "GET");
        assertThrows(SsoLoginException.class, () -> client.validate(config, "t"));
    }

    @Test
    void rejectsOversizedResponse() {
        SsoConfig config = config("/large", "GET");
        SsoLoginException e = assertThrows(SsoLoginException.class,
                () -> client.validate(config, "t"));
        assertTrue(e.getMessage().contains("过大"));
    }

    @Test
    void rejectsNonJsonBody() {
        server.createContext("/text", exchange -> send(exchange, 200, "not json"));
        SsoConfig config = config("/text", "GET");
        assertThrows(SsoLoginException.class, () -> client.validate(config, "t"));
    }

    @Test
    void probeTreats401AsReachable() {
        server.createContext("/auth", exchange -> send(exchange, 401, "{}"));
        SsoConfig config = config("/auth", "GET");
        SsoTicketClient.ProbeResult result = client.probe(config);
        assertTrue(result.reachable());
        assertEquals(401, result.status());
    }

    @Test
    void probeTreats404AsMisconfigured() {
        SsoConfig config = config("/missing", "GET");
        SsoTicketClient.ProbeResult result = client.probe(config);
        assertFalse(result.reachable());
        assertEquals(404, result.status());
    }

    private SsoConfig config(String path, String method) {
        SsoConfig config = SsoConfigService.defaults();
        config.setValidateUrl(baseUrl + path);
        config.setValidateMethod(method);
        config.setTimeoutMs(3000);
        return config;
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
