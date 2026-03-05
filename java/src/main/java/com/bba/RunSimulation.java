package com.bba;

import com.bba.service.GroupLifecycleSimulationService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class RunSimulation {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(RunSimulation.class, args);
        GroupLifecycleSimulationService service = context.getBean(GroupLifecycleSimulationService.class);
        
        try {
            System.out.println("Starting simulation for QHPLIA2023ABBA301...");
            service.runSimulation("QHPLIA2023ABBA301", "202412", "BBA");
            System.out.println("Simulation completed.");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            context.close();
        }
    }
}
