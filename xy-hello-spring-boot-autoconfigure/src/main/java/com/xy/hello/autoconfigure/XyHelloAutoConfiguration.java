package com.xy.hello.autoconfigure;

import com.xy.hello.XyHelloService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(XyHelloProperties.class)
public class XyHelloAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public XyHelloService xyHelloService(XyHelloProperties properties) {
        return new XyHelloService(properties.getPrefix());
    }
}
