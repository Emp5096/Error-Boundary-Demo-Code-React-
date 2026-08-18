package com.xy.interview.demo;

import com.xy.hello.XyHelloService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = DemoStarterImportTest.TestApplication.class,
        properties = "xy.hello.prefix=Test starter"
)
class DemoStarterImportTest {

    @Autowired
    private XyHelloService xyHelloService;

    @Test
    void importsCustomStarterAutoConfiguration() {
        assertThat(xyHelloService.sayHello("demo")).isEqualTo("Test starter, demo");
    }

    @SpringBootApplication
    static class TestApplication {
    }
}
