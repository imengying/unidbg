package com.mengying.fqnovel.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient restClient(RestClient.Builder builder, FQDownloadProperties downloadProperties) {
        Duration connectTimeout = safeTimeout(downloadProperties.getUpstream().getConnectTimeoutMs(), Duration.ofSeconds(8));
        Duration readTimeout = safeTimeout(downloadProperties.getUpstream().getReadTimeoutMs(), Duration.ofSeconds(15));

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return builder.requestFactory(factory).build();
    }

    private static Duration safeTimeout(long valueMs, Duration defaultValue) {
        if (valueMs <= 0) {
            return defaultValue;
        }
        if (valueMs > Integer.MAX_VALUE) {
            return Duration.ofMillis(Integer.MAX_VALUE);
        }
        return Duration.ofMillis(valueMs);
    }
}
