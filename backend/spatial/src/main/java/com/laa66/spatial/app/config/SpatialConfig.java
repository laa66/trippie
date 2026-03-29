package com.laa66.spatial.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.bedatadriven.jackson.datatype.jts.JtsModule;

@Configuration
public class SpatialConfig {

    @Bean
    public JtsModule jtsModule() {
        return new JtsModule();
    }

}
