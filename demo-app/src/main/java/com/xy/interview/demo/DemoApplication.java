package com.xy.interview.demo;

import com.xy.hello.XyHelloService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean
    CommandLineRunner helloRunner(XyHelloService xyHelloService) {
        return args -> System.out.println(xyHelloService.sayHello("demo-app"));
    }
}
