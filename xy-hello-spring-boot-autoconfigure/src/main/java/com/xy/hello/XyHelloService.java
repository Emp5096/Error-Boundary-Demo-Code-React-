package com.xy.hello;

public class XyHelloService {

    private final String prefix;

    public XyHelloService(String prefix) {
        this.prefix = prefix;
    }

    public String sayHello(String name) {
        return this.prefix + ", " + name;
    }
}
