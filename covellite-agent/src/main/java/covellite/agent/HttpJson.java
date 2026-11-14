package covellite.agent;

import covellite.core.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Minimal JSON-over-HTTP helper shared by every provider.
 *
 * <p>Uses only {@code java.net.http}; the agent module deliberately has no
 * third-party dependency so it can be compiled into the mod jar without a
 * shading step.</p>
 */
final class HttpJson {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpJson() {
    }

    static Map<String, Object> post(String url, Map<String, String> headers,
                                    Map<String, Object> body, int timeoutMs)
            throws ProviderException {
        HttpRequest.Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
        } catch (IllegalArgumentException e) {
            throw new ProviderException("invalid provider URL: " + url, e);
        }
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (value != null && !value.isBlank()) {
                    builder.header(name, value);
                }
            });
        }

        HttpResponse<String> response;
        try {
            response = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("interrupted while waiting for the model", e);
        } catch (Exception e) {
            throw new ProviderException("network error calling " + url + ": " + e, e);
        }

        String text = response.body() == null ? "" : response.body();
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String detail = text.length() > 400 ? text.substring(0, 400) : text;
            throw new ProviderException("HTTP " + status + " from " + url + ": " + detail);
        }
        if (text.isBlank()) {
            throw new ProviderException("empty response from " + url);
        }
        try {
            return Json.parseObject(text);
        } catch (RuntimeException e) {
            throw new ProviderException("invalid JSON from " + url + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    static java.util.List<Object> list(Object value) {
        return value instanceof java.util.List<?> items ? (java.util.List<Object>) items : java.util.List.of();
    }

    static String string(Object value) {
        return value instanceof String s ? s : "";
    }

    static String trimSlash(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
