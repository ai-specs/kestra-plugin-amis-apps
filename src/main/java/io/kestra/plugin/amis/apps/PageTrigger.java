package io.kestra.plugin.amis.apps;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.plugin.amis.apps.validation.ValidAppDeclaration;
import io.micronaut.core.annotation.Introspected;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Introspected
@Plugin
@ValidAppDeclaration
@Schema(title = "AMIS page declaration model; no HTTP or execution runtime")
public final class PageTrigger extends AbstractTrigger {
    @NotBlank
    @Schema(title = "Page identifier within the owning Flow", pattern = "[A-Za-z0-9_][A-Za-z0-9_-]{0,99}")
    private String pageId;

    @NotBlank
    @Schema(title = "Canonical nsfile:/// JSON schema reference")
    private String schema;

    public PageTrigger() { }

    public String getPageId() {
        return pageId;
    }

    public void setPageId(String pageId) {
        this.pageId = pageId;
    }

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }
}
