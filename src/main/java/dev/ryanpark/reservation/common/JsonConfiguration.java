package dev.ryanpark.reservation.common;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JsonConfiguration {
  @Bean
  Jackson2ObjectMapperBuilderCustomizer strictNumbers() {
    // Quantities and cents must not be silently truncated or converted from strings.
    return builder ->
        builder
            .featuresToDisable(
                DeserializationFeature.ACCEPT_FLOAT_AS_INT, MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .featuresToEnable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
  }
}
