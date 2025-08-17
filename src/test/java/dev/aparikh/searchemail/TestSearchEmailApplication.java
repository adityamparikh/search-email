package dev.aparikh.searchemail;

import org.springframework.boot.SpringApplication;

public class TestSearchEmailApplication {

    public static void main(String[] args) {
        SpringApplication.from(SearchEmailApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
