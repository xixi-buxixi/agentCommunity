package com.pulse.config;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Minimal Spring Boot configuration for web-slice tests in this package.
 *
 * Without it, @WebMvcTest walks up to PulseApplication and inherits its
 * {@code @MapperScan}, which registers every MyBatis mapper and then fails because
 * the slice has no SqlSessionFactory. Security rules can be verified without a
 * database, so the slice should not need one.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
// Deliberately no @ComponentScan: the slice registers exactly the beans it needs
// via @Import on the test class, so unrelated @Configuration classes (RestTemplate,
// schedulers, Redis) cannot drag their dependencies into a database-free slice.
class WebSliceTestApplication {
}
