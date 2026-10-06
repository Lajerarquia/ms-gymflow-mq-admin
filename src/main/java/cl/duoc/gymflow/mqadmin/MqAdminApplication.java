package cl.duoc.gymflow.mqadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MqAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(MqAdminApplication.class, args);
    }
}
