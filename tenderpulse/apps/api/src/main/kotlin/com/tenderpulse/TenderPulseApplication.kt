package com.tenderpulse

import com.tenderpulse.aggregation.SampleTenderSource
import com.tenderpulse.aggregation.TenderSource
import com.tenderpulse.aggregation.sources.PrazEgpTenderSource
import com.tenderpulse.paypal.PayPalClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.client.RestTemplate

@SpringBootApplication
@EnableScheduling
class TenderPulseApplication

fun main(args: Array<String>) {
    runApplication<TenderPulseApplication>(*args)
}

@Configuration
class SourceConfig {
    @Bean
    fun restTemplate(): RestTemplate = RestTemplate()

    @Bean
    fun sampleSource(): TenderSource = SampleTenderSource()

    @Bean
    fun prazEgpSource(restTemplate: RestTemplate): TenderSource = PrazEgpTenderSource(restTemplate)
}

/**
 * PayPal API client wiring (TP-042). Credentials and the sandbox/live base URL come from
 * environment variables only — see `paypal.*` in `application.yml` and `.env.example` — never
 * hardcoded or committed. Empty-string defaults let the app context boot without them (e.g. in
 * tests, which never exercise `SubscriberService.registerPro`); the real client credentials are
 * required only when `/api/v1/subscribers/pro` is actually called.
 */
@Configuration
class PayPalConfig {
    @Bean
    fun payPalClient(
        restTemplate: RestTemplate,
        @Value("\${paypal.base-url:https://api-m.sandbox.paypal.com}") baseUrl: String,
        @Value("\${paypal.client-id:}") clientId: String,
        @Value("\${paypal.client-secret:}") clientSecret: String
    ): PayPalClient = PayPalClient(
        restTemplate = restTemplate,
        baseUrl = baseUrl,
        clientId = clientId,
        clientSecret = clientSecret
    )
}
