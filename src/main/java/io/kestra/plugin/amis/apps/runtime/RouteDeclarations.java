package io.kestra.plugin.amis.apps.runtime;

import io.kestra.core.models.flows.Flow;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.plugin.amis.apps.ApiTrigger;
import io.kestra.plugin.amis.apps.PageTrigger;
import io.kestra.plugin.amis.apps.domain.ApiConfiguration;
import io.kestra.plugin.amis.apps.domain.Identifiers;
import io.kestra.plugin.amis.apps.domain.SchemaReference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RouteDeclarations {
    public enum Status {
        ACTIVE, FLOW_MISMATCH, DECLARATION_MISSING, FLOW_DISABLED, FLOW_DELETED, TRIGGER_DISABLED,
        DUPLICATE_PAGE_PATH, DUPLICATE_API_PATH, P0_DECLARATION_LIMIT
    }

    private final String namespace;
    private final String flowId;
    private final Integer flowRevision;
    private final List<Page> pages;
    private final List<Api> apis;
    private final Status activation;

    private RouteDeclarations(Flow flow) {
        Objects.requireNonNull(flow, "Missing Flow");
        namespace = Identifiers.namespace(flow.getNamespace());
        flowId = Identifiers.flowId(flow.getId());
        flowRevision = flow.getRevision();
        List<Page> pageList = new ArrayList<>();
        List<Api> apiList = new ArrayList<>();
        Set<String> triggerIds = new HashSet<>();
        for (AbstractTrigger trigger : flow.getTriggers() == null ? List.<AbstractTrigger>of() : flow.getTriggers()) {
            Objects.requireNonNull(trigger, "Null trigger");
            if (trigger.getId() == null || trigger.getId().isBlank() || trigger.getId().length() > 256
                || !triggerIds.add(trigger.getId())) {
                throw new IllegalArgumentException("Invalid or duplicate trigger id; validate Flow with host ModelValidator first");
            }
            if (trigger instanceof PageTrigger page) {
                pageList.add(new Page(page.getId(), Identifiers.id(page.getPageId()), SchemaReference.parse(page.getSchema()), page.isDisabled()));
            } else if (trigger instanceof ApiTrigger api) {
                if (api.getMode() == null) {
                    throw new IllegalArgumentException("API mode must not be null");
                }
                apiList.add(new Api(api.getId(), Identifiers.id(api.getApiId()),
                    new ApiConfiguration(api.getMode(), api.getSyncTimeout()), Identifiers.outputId(api.getResultOutput()), api.isDisabled()));
            }
        }
        pages = List.copyOf(pageList);
        apis = List.copyOf(apiList);
        if (flow.isDeleted()) {
            activation = Status.FLOW_DELETED;
        } else if (flow.isDisabled()) {
            activation = Status.FLOW_DISABLED;
        } else if (pages.stream().map(Page::pageId).distinct().count() != pages.size()) {
            activation = Status.DUPLICATE_PAGE_PATH;
        } else if (apis.stream().map(Api::apiId).distinct().count() != apis.size()) {
            activation = Status.DUPLICATE_API_PATH;
        } else {
            activation = Status.ACTIVE;
        }
    }

    public static RouteDeclarations from(Flow flow) {
        return new RouteDeclarations(flow);
    }

    public Lookup<Page> page(String requestedNamespace, String requestedFlowId, String pageId) {
        Identifiers.id(pageId);
        Status unavailable = unavailable(requestedNamespace, requestedFlowId);
        if (unavailable != null) {
            return new Lookup<>(unavailable, null);
        }
        return pages.stream().filter(page -> page.pageId().equals(pageId)).findFirst()
            .map(page -> page.disabled() ? new Lookup<Page>(Status.TRIGGER_DISABLED, null) : new Lookup<>(Status.ACTIVE, page))
            .orElseGet(() -> new Lookup<>(Status.DECLARATION_MISSING, null));
    }

    public Lookup<Api> api(String requestedNamespace, String requestedFlowId, String apiId) {
        Identifiers.id(apiId);
        Status unavailable = unavailable(requestedNamespace, requestedFlowId);
        if (unavailable != null) {
            return new Lookup<>(unavailable, null);
        }
        return apis.stream().filter(api -> api.apiId().equals(apiId)).findFirst()
            .map(api -> api.disabled() ? new Lookup<Api>(Status.TRIGGER_DISABLED, null) : new Lookup<>(Status.ACTIVE, api))
            .orElseGet(() -> new Lookup<>(Status.DECLARATION_MISSING, null));
    }

    private Status unavailable(String requestedNamespace, String requestedFlowId) {
        Identifiers.namespace(requestedNamespace);
        Identifiers.flowId(requestedFlowId);
        if (!namespace.equals(requestedNamespace) || !flowId.equals(requestedFlowId)) {
            return Status.FLOW_MISMATCH;
        }
        return activation == Status.ACTIVE ? null : activation;
    }

    public String namespace() { return namespace; }
    public String flowId() { return flowId; }
    public Integer flowRevision() { return flowRevision; }
    public List<Page> pages() { return pages; }
    public List<Api> apis() { return apis; }
    public Status activation() { return activation; }

    public record Lookup<T>(Status status, T declaration) {
        public Lookup {
            Objects.requireNonNull(status, "Missing lookup status");
            if ((status == Status.ACTIVE) != (declaration != null)) {
                throw new IllegalArgumentException("Only active lookups may contain a declaration");
            }
        }
    }

    public record Page(String triggerId, String pageId, SchemaReference schema, boolean disabled) { }
    public record Api(String triggerId, String apiId, ApiConfiguration configuration, String resultOutput, boolean disabled) { }
}
