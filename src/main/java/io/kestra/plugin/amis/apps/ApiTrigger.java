package io.kestra.plugin.amis.apps;

import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.plugin.amis.apps.domain.ApiConfiguration;
import io.kestra.plugin.amis.apps.validation.ValidAppDeclaration;
import io.micronaut.core.annotation.Introspected;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Duration;

@Introspected
@Plugin
@ValidAppDeclaration
@Schema(title = "AMIS API declaration model; no HTTP or execution runtime")
public final class ApiTrigger extends AbstractTrigger {
    @NotBlank
    @Schema(title = "API identifier within the owning Flow", pattern = "[A-Za-z0-9_][A-Za-z0-9_-]{0,99}")
    private String apiId;

    @NotNull
    @Schema(title = "Execution mode declaration", defaultValue = "ASYNC")
    private ApiConfiguration.Mode mode = ApiConfiguration.Mode.ASYNC;

    @Schema(title = "Required only for SYNC; ISO-8601 duration greater than zero and at most PT30S")
    private Duration syncTimeout;

    @NotBlank
    @Schema(title = "Declared Flow output identifier, not a task identifier", defaultValue = "response",
        pattern = "^[a-zA-Z0-9][.a-zA-Z0-9_-]*")
    private String resultOutput = "response";

    public ApiTrigger() { }

    public String getApiId() {
        return apiId;
    }

    public void setApiId(String apiId) {
        this.apiId = apiId;
    }

    public ApiConfiguration.Mode getMode() {
        return mode;
    }

    public void setMode(ApiConfiguration.Mode mode) {
        this.mode = mode;
    }

    public Duration getSyncTimeout() {
        return syncTimeout;
    }

    public void setSyncTimeout(Duration syncTimeout) {
        this.syncTimeout = syncTimeout;
    }

    public String getResultOutput() {
        return resultOutput;
    }

    public void setResultOutput(String resultOutput) {
        this.resultOutput = resultOutput;
    }
}
