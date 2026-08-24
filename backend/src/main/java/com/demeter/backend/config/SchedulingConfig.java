package com.demeter.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SchedulingConfig {

    @Bean
    ThreadPoolTaskScheduler taskScheduler(SchedulingProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.poolSize());
        scheduler.setThreadNamePrefix("demeter-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(Math.toIntExact(properties.awaitTermination().toSeconds()));
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    @ConfigurationProperties(prefix = "demeter.scheduler")
    public record SchedulingProperties(int poolSize, Duration awaitTermination) {

        public SchedulingProperties {
            if (poolSize < 1 || poolSize > 64) {
                throw new IllegalArgumentException("Scheduler pool size must be between 1 and 64");
            }
            if (awaitTermination == null
                    || awaitTermination.isZero()
                    || awaitTermination.isNegative()
                    || awaitTermination.toSeconds() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Scheduler await termination is outside the supported range");
            }
        }
    }
}
