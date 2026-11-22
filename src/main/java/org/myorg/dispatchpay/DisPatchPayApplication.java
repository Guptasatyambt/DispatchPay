package org.myorg.dispatchpay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DisPatchPayApplication {

    public static void main(String[] args) {
        SpringApplication.run(DisPatchPayApplication.class, args);
    }

}
