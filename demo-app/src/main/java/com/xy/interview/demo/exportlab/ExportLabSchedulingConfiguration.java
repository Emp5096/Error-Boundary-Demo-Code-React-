package com.xy.interview.demo.exportlab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        name = "export-lab.metrics-scheduling-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class ExportLabSchedulingConfiguration {
}
