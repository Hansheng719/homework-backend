package com.example.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "scheduler.transfer-processor")
public class SchedulerProperties {

    // PENDING transfers processor (existing functionality)
    /**
     * Cron expression for the PENDING transfer processor schedule.
     * Example: "&#42;/5 * * * * ?" for every 5 seconds
     *          "0 &#42;/5 * * * ?" for every 5 minutes
     */
    private String pendingCron;

    /**
     * How old (in seconds) PENDING transfers should be before processing.
     * Only PENDING transfers older than this will be processed.
     */
    private int pendingDelaySeconds;

    /**
     * Maximum number of PENDING transfers to process per execution.
     */
    private int pendingBatchSize;

    // DEBIT_PROCESSING retry processor (new functionality)
    /**
     * Cron expression for the DEBIT_PROCESSING retry processor schedule.
     * Example: "0 &#42;/5 * * * ?" for every 5 minutes
     */
    private String debitProcessingCron;

    /**
     * How old (in seconds) DEBIT_PROCESSING transfers should be before retry.
     * Only transfers stuck in DEBIT_PROCESSING longer than this will be retried.
     */
    private int debitProcessingDelaySeconds;

    /**
     * Maximum number of DEBIT_PROCESSING transfers to retry per execution.
     */
    private int debitProcessingBatchSize;

    // CREDIT_PROCESSING retry processor (new functionality)
    /**
     * Cron expression for the CREDIT_PROCESSING retry processor schedule.
     * Example: "0 &#42;/5 * * * ?" for every 5 minutes
     */
    private String creditProcessingCron;

    /**
     * How old (in seconds) CREDIT_PROCESSING transfers should be before retry.
     * Only transfers stuck in CREDIT_PROCESSING longer than this will be retried.
     */
    private int creditProcessingDelaySeconds;

    /**
     * Maximum number of CREDIT_PROCESSING transfers to retry per execution.
     */
    private int creditProcessingBatchSize;

    // ShedLock lock duration configuration
    /**
     * Maximum lock duration in seconds for PENDING transfers processor.
     * Should match or slightly exceed the job frequency.
     * Prevents concurrent execution across multiple instances.
     * The lock will be automatically released after this duration even if the job crashes.
     */
    private int pendingLockAtMostSeconds;

    /**
     * Minimum lock duration in seconds for PENDING transfers processor.
     * Prevents too-quick re-execution after job completion.
     */
    private int pendingLockAtLeastSeconds;

    /**
     * Maximum lock duration in seconds for DEBIT_PROCESSING retry processor.
     * Should match or slightly exceed the job frequency.
     */
    private int debitProcessingLockAtMostSeconds;

    /**
     * Minimum lock duration in seconds for DEBIT_PROCESSING retry processor.
     * Prevents too-quick re-execution after job completion.
     */
    private int debitProcessingLockAtLeastSeconds;

    /**
     * Maximum lock duration in seconds for CREDIT_PROCESSING retry processor.
     * Should match or slightly exceed the job frequency.
     */
    private int creditProcessingLockAtMostSeconds;

    /**
     * Minimum lock duration in seconds for CREDIT_PROCESSING retry processor.
     * Prevents too-quick re-execution after job completion.
     */
    private int creditProcessingLockAtLeastSeconds;
}
