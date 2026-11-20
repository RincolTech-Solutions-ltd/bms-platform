package com.rincoltech.bms.core.onboarding.internal;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A fake of the two Cloudflare API resources the address setup uses (issue #155, ADR-131), on the loopback: the zone's DNS
 * records (list by name, create, refusing a second record of a name as Cloudflare refuses a second CNAME) and the tunnel's
 * configuration (read, replace). Each step can be made to answer an error status or to answer too late. Every request's
 * method, path and Authorization header are kept, so a test can say what was asked and with what.
 */
final class FakeCloudflare implements AutoCloseable {

    static final String ZONE = "0123456789abcdef0123456789abcdef";
    static final String ACCOUNT = "fedcba9876543210fedcba9876543210";
    static final String TUNNEL = "11111111-2222-3333-4444-555555555555";
    static final String TOKEN = "test-cf-token-fabricated-0001";

    final ObjectMapper mapper = new ObjectMapper();
    final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    final List<String> authorizations = Collections.synchronizedList(new ArrayList<>());
    final List<ObjectNode> records = Collections.synchronizedList(new ArrayList<>());
    /** Step ("GET dns", "POST dns", "GET tunnel", "PUT tunnel") to the status it answers instead. */
    final Map<String, Integer> failures = new ConcurrentHashMap<>();
    /** Steps that answer only after this many milliseconds. */
    final Map<String, Long> delays = new ConcurrentHashMap<>();
    /** Steps that send their headers and the start of the body, then stall for this many milliseconds (issue #965). */
    final Map<String, Long> stalls = new ConcurrentHashMap<>();

    volatile ObjectNode config;
    private final HttpServer server;
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();

    FakeCloudflare() throws IOException {
        config = (ObjectNode) mapper.readTree("""
                {"ingress": [
                  {"hostname": "existing-bms-staging.example.test", "service": "http://proxy:8080", "originRequest": {}},
                  {"service": "http_status:404"}
                ], "warp-routing": {"enabled": false}}
                """);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(handlers);
        server.start();
    }

    URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/client/v4/");
    }

    /** The ingress rules' hostnames in order, "catch-all" for a rule without one. */
    List<String> ingressHosts() {
        List<String> hosts = new ArrayList<>();
        JsonNode ingress = config.path("ingress");
        for (int i = 0; i < ingress.size(); i++) {
            hosts.add(ingress.get(i).path("hostname").asString("catch-all"));
        }
        return hosts;
    }

    long count(String prefix) {
        return requests.stream().filter(r -> r.startsWith(prefix)).count();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getRawPath();
            String query = exchange.getRequestURI().getRawQuery();
            requests.add(method + " " + path + (query == null ? "" : "?" + query));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] in = exchange.getRequestBody().readAllBytes();
            String dns = "/client/v4/zones/" + ZONE + "/dns_records";
            String tunnel = "/client/v4/accounts/" + ACCOUNT + "/cfd_tunnel/" + TUNNEL + "/configurations";
            String step = method + (path.equals(dns) ? " dns" : path.equals(tunnel) ? " tunnel" : " other");
            Long delay = delays.get(step);
            if (delay != null) {
                Thread.sleep(delay);
            }
            Long stall = stalls.get(step);
            if (stall != null) {
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                out.write("{\"success\":true,".getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(stall);
                return;
            }
            Integer failure = failures.get(step);
            if (failure != null) {
                answer(
                        exchange,
                        failure,
                        "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"fabricated\"}]}");
                return;
            }
            switch (step) {
                case "GET dns" -> {
                    // Only the exact match filter (issue #965): a bare name= is refused, so the tests prove it.
                    if (query == null || !query.startsWith("name.exact=")) {
                        answer(
                                exchange,
                                400,
                                "{\"success\":false,\"errors\":[{\"code\":9999,\"message\":\"filter\"}]}");
                        return;
                    }
                    String name = URLDecoder.decode(query.substring("name.exact=".length()), StandardCharsets.UTF_8);
                    ArrayNode result = mapper.createArrayNode();
                    synchronized (records) {
                        records.stream()
                                .filter(r -> r.path("name").asString("").equals(name))
                                .forEach(result::add);
                    }
                    answer(exchange, 200, envelope(result));
                }
                case "POST dns" -> {
                    ObjectNode record = (ObjectNode) mapper.readTree(in);
                    synchronized (records) {
                        if (records.stream()
                                .anyMatch(r -> r.path("name")
                                        .asString("")
                                        .equals(record.path("name").asString("")))) {
                            answer(
                                    exchange,
                                    400,
                                    "{\"success\":false,\"errors\":[{\"code\":81053,\"message\":\"exists\"}]}");
                            return;
                        }
                        record.put("id", "rec" + records.size());
                        records.add(record);
                    }
                    answer(exchange, 200, envelope(record));
                }
                case "GET tunnel" -> {
                    ObjectNode result = mapper.createObjectNode();
                    result.put("tunnel_id", TUNNEL);
                    result.set("config", config);
                    answer(exchange, 200, envelope(result));
                }
                case "PUT tunnel" -> {
                    config = (ObjectNode) mapper.readTree(in).path("config");
                    answer(exchange, 200, envelope(mapper.createObjectNode()));
                }
                default ->
                    answer(exchange, 404, "{\"success\":false,\"errors\":[{\"code\":7003,\"message\":\"no route\"}]}");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String envelope(JsonNode result) {
        ObjectNode answer = mapper.createObjectNode();
        answer.put("success", true);
        answer.putArray("errors");
        answer.set("result", result);
        return mapper.writeValueAsString(answer);
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } catch (IOException e) {
            // The client gave up (a timeout test): nothing to answer.
        }
    }

    @Override
    public void close() {
        server.stop(0);
        handlers.shutdownNow();
    }
}
