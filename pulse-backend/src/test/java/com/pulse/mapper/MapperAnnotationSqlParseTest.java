package com.pulse.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Startup smoke test: every mapper's annotation SQL must actually parse.
 *
 * This exists because of a production-stopping bug that 233 unit tests could not see. A
 * bare {@code <>} inside a {@code <script>} annotation is invalid XML, so MyBatis failed
 * to build that mapper, the bean failed, and the entire Spring context refused to start -
 * while every service test stayed green, because they all mock their mappers. Mocked
 * mappers never parse a line of SQL.
 *
 * The check needs no database: MyBatis parses annotation SQL when the mapper interface is
 * registered, long before a connection is opened. The DataSource below is a placeholder
 * that is never asked for one.
 *
 * If a malformed statement is ever added again, this test is what turns "the deployment
 * does not come up" into "mvn test is red".
 */
class MapperAnnotationSqlParseTest {

    private static final String MAPPER_PACKAGE = "com.pulse.mapper";

    /**
     * Guard against the test quietly passing because it found nothing to check.
     */
    private static final int MINIMUM_EXPECTED_MAPPERS = 15;

    @Test
    void everyMapperAnnotationSqlIsParseable() {
        List<Class<?>> mappers = findMapperInterfaces();

        assertThat(mappers)
                .as("mapper scan found nothing - the test would pass without checking anything")
                .hasSizeGreaterThanOrEqualTo(MINIMUM_EXPECTED_MAPPERS);

        Configuration configuration = newOfflineConfiguration();
        List<String> failures = new ArrayList<>();
        for (Class<?> mapper : mappers) {
            try {
                configuration.addMapper(mapper);
            } catch (Exception e) {
                // Collect them all: one broken statement should not hide the next.
                failures.add(mapper.getSimpleName() + " -> " + rootCause(e));
            }
        }

        assertThat(failures)
                .as("these mappers have unparseable annotation SQL and would stop the "
                        + "application from starting (a bare < or > inside <script> is the "
                        + "usual cause - escape it as &lt; / &gt;)")
                .isEmpty();

        assertThat(configuration.getMappedStatements())
                .as("registering the mappers produced no statements at all")
                .isNotEmpty();
    }

    /**
     * MyBatis Plus's configuration, because the mappers extend {@code BaseMapper} and its
     * injected statements are built by MP's own annotation builder.
     */
    private Configuration newOfflineConfiguration() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        // Never connects: a driver-less DataSource is enough for statement parsing, and
        // keeps this test independent of any running MySQL.
        configuration.setEnvironment(new Environment("annotation-sql-parse",
                new JdbcTransactionFactory(), new SimpleDriverDataSource()));
        return configuration;
    }

    /**
     * Every {@code @Mapper} interface in the mapper package.
     */
    private List<Class<?>> findMapperInterfaces() {
        // The default scanner skips interfaces; mappers are nothing but interfaces.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                        return definition.getMetadata().isIndependent();
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(Mapper.class));

        Set<BeanDefinition> definitions = scanner.findCandidateComponents(MAPPER_PACKAGE);
        List<Class<?>> mappers = new ArrayList<>(definitions.size());
        for (BeanDefinition definition : definitions) {
            try {
                mappers.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Scanned a mapper that cannot be loaded: "
                        + definition.getBeanClassName(), e);
            }
        }
        mappers.sort(java.util.Comparator.comparing(Class::getName));
        return mappers;
    }

    private String rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }
}
