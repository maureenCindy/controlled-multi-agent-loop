package com.tenderpulse

import com.tenderpulse.aggregation.SampleTenderSource
import com.tenderpulse.aggregation.TenderSource
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class TenderPulseApplication

fun main(args: Array<String>) {
    runApplication<TenderPulseApplication>(*args)
}

@Configuration
class SourceConfig {
    @Bean
    fun sampleSource(): TenderSource = SampleTenderSource()
}
