package io.kestra.plugin.amis.apps.testing;

import io.kestra.core.models.validations.ModelValidator;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.ApplicationContextConfiguration;
import io.micronaut.context.DefaultApplicationContext;
import io.micronaut.context.event.ApplicationEventPublisherFactory;
import io.micronaut.inject.BeanConfiguration;
import io.micronaut.inject.BeanDefinitionReference;
import io.micronaut.inject.provider.JakartaProviderBeanDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class HostModelContext extends DefaultApplicationContext {
    private final List<BeanDefinitionReference> references = new ArrayList<>();

    public HostModelContext() throws ReflectiveOperationException {
        super((ApplicationContextConfiguration) ApplicationContext.builder()
            .enableDefaultPropertySources(false).environmentPropertySource(false).deduceEnvironment(false)
            .deduceCloudEnvironment(false).environments("amis-model-test").banner(false).allowEmptyProviders(true)
            .properties(Map.of("micronaut.validator.enabled", true)));
        for (String definitionName : List.of(
            "io.kestra.core.models.validations.$ModelValidator$Definition",
            "io.kestra.core.validations.factory.$CustomValidatorFactoryProvider$Definition",
            "io.kestra.core.validations.factory.$CustomValidatorFactoryProvider$ValidatorFactory0$Definition",
            "io.kestra.core.models.property.$PropertyValueExtractor$Definition",
            "io.micronaut.configuration.hibernate.validator.$MicronautHibernateValidator$Definition",
            "io.micronaut.validation.validator.$DefaultValidatorConfiguration$Definition",
            "io.micronaut.validation.validator.constraints.$DefaultInternalConstraintValidatorFactory$Definition",
            "io.micronaut.configuration.hibernate.validator.$DefaultConstraintValidatorFactory$Definition",
            "io.kestra.core.validations.validator.$FlowValidator$Definition",
            "io.kestra.core.services.$DefaultNamespaceService$Definition")) {
            if (getClass().getClassLoader().getResource("META-INF/micronaut/io.micronaut.inject.BeanDefinitionReference/" + definitionName) == null) {
                throw new IllegalStateException("Missing published validation bean index: " + definitionName);
            }
            references.add((BeanDefinitionReference<?>) Class.forName(definitionName).getConstructor().newInstance());
        }
        references.add(new ApplicationEventPublisherFactory<>());
        references.add(new JakartaProviderBeanDefinition());
        start();
    }

    public ModelValidator modelValidator() {
        return getBean(ModelValidator.class);
    }

    @Override
    protected List<BeanDefinitionReference> resolveBeanDefinitionReferences() {
        return List.copyOf(references);
    }

    @Override
    protected List<BeanDefinitionReference> resolveBeanDefinitionReferences(Predicate<BeanDefinitionReference> predicate) {
        return references.stream().filter(reference -> predicate == null || predicate.test(reference)).toList();
    }

    @Override
    protected Iterable<BeanConfiguration> resolveBeanConfigurations() {
        return List.of();
    }
}
