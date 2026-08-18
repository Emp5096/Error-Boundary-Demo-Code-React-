# Custom Spring Boot Starter Demo

这个仓库演示一个最小可运行的自定义 Spring Boot starter。

## 模块说明

```text
custom-starter-demo-parent
├── xy-hello-spring-boot-autoconfigure
├── xy-hello-spring-boot-starter
└── demo-app
```

`xy-hello-spring-boot-autoconfigure` 放真正的自动配置代码：

```text
xy-hello-spring-boot-autoconfigure/src/main/java/com/xy/hello/XyHelloService.java
xy-hello-spring-boot-autoconfigure/src/main/java/com/xy/hello/autoconfigure/XyHelloProperties.java
xy-hello-spring-boot-autoconfigure/src/main/java/com/xy/hello/autoconfigure/XyHelloAutoConfiguration.java
```

最关键的文件也放在这个模块里：

```text
xy-hello-spring-boot-autoconfigure/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

文件内容是一行自动配置类全限定名：

```text
com.xy.hello.autoconfigure.XyHelloAutoConfiguration
```

`xy-hello-spring-boot-starter` 本身只做依赖聚合，它依赖：

```text
xy-hello-spring-boot-autoconfigure
spring-boot-starter
```

`demo-app` 只需要依赖：

```text
xy-hello-spring-boot-starter
```

## 为什么 imports 文件放在 autoconfigure 模块

Spring Boot 启动时会扫描 classpath 上每个 jar 里的：

```text
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

所以这个文件必须被打进提供自动配置的 jar。这里提供自动配置的是：

```text
xy-hello-spring-boot-autoconfigure
```

不是 `demo-app`，也通常不是只有 pom 的 `starter` 模块。

## 运行测试

```bash
mvn -pl demo-app -am test
```

测试会证明 `demo-app` 通过 starter 自动获得了 `XyHelloService`。

## 打包并运行 demo

```bash
mvn -pl demo-app -am package
java -jar demo-app/target/demo-app-1.0.0-SNAPSHOT.jar
```

你会看到类似输出：

```text
Hello from custom starter, demo-app
```
