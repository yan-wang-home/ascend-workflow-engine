package com.ascend.workflow.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import io.r2dbc.postgresql.codec.Json;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class JacksonConfig {

    @Bean
    public Module r2dbcJsonModule() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(Json.class, new StdSerializer<>(Json.class) {
            @Override
            public void serialize(Json value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeRawValue(value.asString());
            }
        });
        return module;
    }
}
