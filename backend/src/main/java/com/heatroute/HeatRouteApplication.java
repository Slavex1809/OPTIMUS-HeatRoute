package com.heatroute;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@io.swagger.v3.oas.annotations.OpenAPIDefinition(
    info = @io.swagger.v3.oas.annotations.info.Info(title = "OPTIMUS HeatRoute", version = "2.1.0"))
@SpringBootApplication
public class HeatRouteApplication {
  public static void main(String[] args) {
    SpringApplication.run(HeatRouteApplication.class, args);
  }
}
