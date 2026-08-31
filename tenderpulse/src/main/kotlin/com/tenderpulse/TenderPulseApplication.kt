package com.tenderpulse

import com.tenderpulse.aggregation.SampleTenderSource
import com.tenderpulse.aggregation.TenderSource
import com.tenderpulse.aggregation.sources.PrazEgpTenderSource
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
