package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "scheduler.transfer-processor")
public class SchedulerProperties {

    /**
     * Cron expression for the transfer processor schedule.
     * Example: "&#42;/5 * * * * ?" for every 5 seconds
     *          "0 &#42;/5 * * * ?" for every 5 minutes
     */
    private String cron;

    /**
     * How old (in seconds) transfers should be before processing.
     * Only PENDING transfers older than this will be processed.
     */
    private int delaySeconds;

    /**
     * Maximum number of transfers to process per execution.
     */
    private int batchSize;
}
