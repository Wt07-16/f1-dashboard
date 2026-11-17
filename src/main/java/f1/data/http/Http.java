package f1.data.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import f1.domain.DataException;
import f1.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 统一 HTTP 封装:超时、UA、错误转成可读的 {@link DataException}。
 * 所有数据源(远端)都走这里,换网络库只改这一个文件。
 */
public final class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {
    }

    /** GET 并把响应体解析为指定类型;非 200 / 网络失败 / 解析失败都抛 DataException */
    public static <T> T getJson(String url, Class<T> type) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "f1-dashboard/0.1 (JavaFX desktop)")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DataException("网络请求失败(请检查网络连接) → " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DataException("网络请求被中断 → " + url, e);
        }
        if (response.statusCode() != 200) {
            throw new DataException("数据源返回异常 HTTP " + response.statusCode() + " → " + url);
        }
        try {
            return Json.mapper().readValue(response.body(), type);
        } catch (JsonProcessingException e) {
            throw new DataException("数据解析失败 → " + url, e);
        }
    }
}
