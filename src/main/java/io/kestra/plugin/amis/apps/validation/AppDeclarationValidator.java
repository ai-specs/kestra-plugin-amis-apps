package io.kestra.plugin.amis.apps.validation;

import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.plugin.amis.apps.ApiTrigger;
import io.kestra.plugin.amis.apps.PageTrigger;
import io.kestra.plugin.amis.apps.domain.ApiConfiguration;
import io.kestra.plugin.amis.apps.domain.Identifiers;
import io.kestra.plugin.amis.apps.domain.SchemaReference;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class AppDeclarationValidator implements ConstraintValidator<ValidAppDeclaration, AbstractTrigger> {
    @Override
    public boolean isValid(AbstractTrigger declaration, ConstraintValidatorContext context) {
        if (declaration == null) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        return switch (declaration) {
            case PageTrigger page -> {
                boolean pageId = check("pageId", () -> Identifiers.id(page.getPageId()), context);
                boolean schema = check("schema", () -> SchemaReference.parse(page.getSchema()), context);
                yield pageId && schema;
            }
            case ApiTrigger api -> {
                boolean apiId = check("apiId", () -> Identifiers.id(api.getApiId()), context);
                boolean configuration = check("syncTimeout", () -> new ApiConfiguration(api.getMode(), api.getSyncTimeout()), context);
                boolean resultOutput = check("resultOutput", () -> Identifiers.outputId(api.getResultOutput()), context);
                yield apiId && configuration && resultOutput;
            }
            default -> throw new IllegalArgumentException("Unsupported AMIS declaration type");
        };
    }

    private boolean check(String field, Runnable validation, ConstraintValidatorContext context) {
        try {
            validation.run();
            return true;
        } catch (IllegalArgumentException exception) {
            context.buildConstraintViolationWithTemplate(exception.getMessage()).addPropertyNode(field).addConstraintViolation();
            return false;
        }
    }
}
