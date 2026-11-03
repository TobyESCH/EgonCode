package dev.egoncode;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class Http {
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private Http() {}

    static Map<String,Object> postJson(String url, String credential, String version, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(3))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (credential != null && !credential.isBlank()) request.header(version == null ? "Authorization" : "x-api-key", version == null ? "Bearer " + credential : credential);
        if (version != null) request.header("anthropic-version", version);
        HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        return Json.map(Json.parse(response.body()));
    }

    static void stream(String url, String credential, String version, Object body, BiConsumer<String,Map<String,Object>> event) throws Exception {
        stream(url,credential,version,body,headers -> {},event);
    }

    static void stream(String url, String credential, String version, Object body,
                       Consumer<HttpHeaders> onHeaders, BiConsumer<String,Map<String,Object>> event) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
                .header("Content-Type","application/json").header("Accept","text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (credential != null && !credential.isBlank()) request.header(version == null ? "Authorization" : "x-api-key", version == null ? "Bearer " + credential : credential);
        if (version != null) request.header("anthropic-version", version);
        HttpResponse<InputStream> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        onHeaders.accept(response.headers());
        if (response.statusCode() / 100 != 2) {
            String error = new String(response.body().readNBytes(4000), StandardCharsets.UTF_8);
            throw new IOException("HTTP " + response.statusCode() + ": " + error);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line, name = ""; StringBuilder data = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty() && !data.toString().equals("[DONE]")) event.accept(name, Json.map(Json.parse(data.toString())));
                    name = ""; data.setLength(0);
                } else if (line.startsWith("event:")) name = line.substring(6).trim();
                else if (line.startsWith("data:")) { if (!data.isEmpty()) data.append('\n'); data.append(line.substring(5).trim()); }
            }
            if (!data.isEmpty() && !data.toString().equals("[DONE]")) event.accept(name, Json.map(Json.parse(data.toString())));
        }
    }
}
