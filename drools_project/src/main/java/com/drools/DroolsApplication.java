package com.drools;

import com.drools.model.Order;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class DroolsApplication {

    public static void main(String[] args) {
        SpringApplication.run(DroolsApplication.class, args);
    }

    @Bean
    public CommandLineRunner demo(KieContainer kieContainer) {
        return args -> {
            KieSession session = kieContainer.newKieSession();
            Order order = new Order();
            order.setAmout(1500);
            session.insert(order);
            session.fireAllRules();
            session.dispose();
            System.out.println("订单金额：" + order.getAmout() + "，增加积分：" + order.getScore());
        };
    }
}
