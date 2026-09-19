package dev.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.http.HttpClient;
import java.time.Clock;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(PaymentProperties.class)
public class ApplicationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    HttpClient providerHttpClient(PaymentProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.providerTimeout()).build();
    }
}
