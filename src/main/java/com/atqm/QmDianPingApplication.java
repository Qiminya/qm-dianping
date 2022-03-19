package com.atqm;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Scanner;

@MapperScan("com.atqm.mapper")
@SpringBootApplication
public class QmDianPingApplication {

    public static void main(String[] args) {
        SpringApplication.run(QmDianPingApplication.class, args);
    }

}
