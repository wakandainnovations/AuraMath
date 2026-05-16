package com.lit.fire.flame;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AuraMathApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuraMathApplication.class, args);
    }

}
