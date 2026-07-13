package com.api.tqt.ws;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class WatingSystemApplication {

    public static void main(String[] args) {

        SpringApplication.run(WatingSystemApplication.class, args);
    }

}
