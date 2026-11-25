package io.kestra.plugin.amis.apps.runtime;

import io.kestra.core.models.flows.Flow;
import io.kestra.core.serializers.YamlParser;
import io.kestra.plugin.amis.apps.ApiTrigger;
import io.kestra.plugin.amis.apps.PageTrigger;
import io.kestra.plugin.amis.apps.testing.PackagedTriggerMain;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RouteDeclarationsTest {
    @Test
    void snapshotIsImmutableAndDerivesOnlyTheSelectedFlow() {
        RouteDeclarations declarations = RouteDeclarations.from(flow(PackagedTriggerMain.declarations()));
        assertEquals("company._team", declarations.namespace());
        assertEquals("model.test", declarations.flowId());
        assertEquals(1, declarations.pages().size());
        assertEquals(1, declarations.apis().size());
        assertThrows(UnsupportedOperationException.class, () -> declarations.pages().clear());
        assertEquals(RouteDeclarations.Status.ACTIVE,
            declarations.page("company._team", "model.test", "home").status());
        assertEquals(RouteDeclarations.Status.ACTIVE,
            declarations.api("company._team", "model.test", "home").status());
        assertEquals("home", declarations.page("company._team", "model.test", "home").declaration().pageId());
        assertEquals("home", declarations.api("company._team", "model.test", "home").declaration().apiId());
    }

    @Test
    void missingAndMismatchedRoutesDoNotScanAnotherFlow() {
        RouteDeclarations declarations = RouteDeclarations.from(flow(PackagedTriggerMain.declarations()));
        assertEquals(RouteDeclarations.Status.DECLARATION_MISSING,
            declarations.page("company._team", "model.test", "missing").status());
        assertEquals(RouteDeclarations.Status.FLOW_MISMATCH,
            declarations.page("other", "model.test", "home").status());
        assertEquals(RouteDeclarations.Status.FLOW_MISMATCH,
            declarations.api("company._team", "other", "home").status());
    }

    @Test
    void disabledFlowAndDeletedFlowAreExplicitlyInactive() {
        String declaration = PackagedTriggerMain.declarations();
        Flow disabled = YamlParser.parse(PackagedTriggerMain.coreFlow().replace("id: model.test", "disabled: true\nid: model.test") + declaration, Flow.class);
        assertEquals(RouteDeclarations.Status.FLOW_DISABLED, RouteDeclarations.from(disabled).activation());
        Flow deleted = flow(declaration).toBuilder().deleted(true).build();
        assertEquals(RouteDeclarations.Status.FLOW_DELETED, RouteDeclarations.from(deleted).activation());
    }

    @Test
    void disabledTriggersAreNotReturnedAsActiveDeclarations() {
        String declaration = PackagedTriggerMain.declarations().replace("id: page-home", "disabled: true\n    id: page-home")
            .replace("id: api-home", "disabled: true\n    id: api-home");
        RouteDeclarations routes = RouteDeclarations.from(flow(declaration));
        assertEquals(RouteDeclarations.Status.TRIGGER_DISABLED, routes.page("company._team", "model.test", "home").status());
        assertEquals(RouteDeclarations.Status.TRIGGER_DISABLED, routes.api("company._team", "model.test", "home").status());
    }

    @Test
    void duplicateSameKindPathsRejectActivationButPageAndApiMayShareName() {
        String declaration = PackagedTriggerMain.declarations()
            + "  - id: page-other\n    type: " + PageTrigger.class.getName() + "\n    pageId: home\n    schema: nsfile:///other.json\n";
        assertEquals(RouteDeclarations.Status.DUPLICATE_PAGE_PATH, RouteDeclarations.from(flow(declaration)).activation());
        String apiDuplicate = PackagedTriggerMain.declarations()
            + "  - id: api-other\n    type: " + ApiTrigger.class.getName() + "\n    apiId: home\n";
        assertEquals(RouteDeclarations.Status.DUPLICATE_API_PATH, RouteDeclarations.from(flow(apiDuplicate)).activation());
    }

    @Test
    void p0LimitRejectsMultipleDeclarationsWithoutRejectingTheModelSnapshot() {
        String declaration = PackagedTriggerMain.declarations()
            + "  - id: page-other\n    type: " + PageTrigger.class.getName() + "\n    pageId: other\n    schema: nsfile:///other.json\n"
            + "  - id: api-other\n    type: " + ApiTrigger.class.getName() + "\n    apiId: other\n";
        RouteDeclarations routes = RouteDeclarations.from(flow(declaration));
        assertEquals(RouteDeclarations.Status.P0_DECLARATION_LIMIT, routes.activation());
        assertEquals(RouteDeclarations.Status.P0_DECLARATION_LIMIT,
            routes.page("company._team", "model.test", "home").status());
        assertEquals(RouteDeclarations.Status.P0_DECLARATION_LIMIT,
            routes.api("company._team", "model.test", "home").status());
    }

    @Test
    void triggerIdUniquenessRemainsTheHostRuleAndBusinessIdsAreSeparate() {
        String duplicateTrigger = PackagedTriggerMain.declarations().replace("id: api-home", "id: page-home");
        assertThrows(IllegalArgumentException.class, () -> RouteDeclarations.from(flow(duplicateTrigger)));
        String sameBusinessId = PackagedTriggerMain.declarations();
        RouteDeclarations routes = RouteDeclarations.from(flow(sameBusinessId));
        assertNotEquals(routes.pages().getFirst().triggerId(), routes.apis().getFirst().triggerId());
    }

    @Test
    void invalidRequestedIdentifiersAreRejectedBeforeLookup() {
        RouteDeclarations routes = RouteDeclarations.from(flow(PackagedTriggerMain.declarations()));
        assertThrows(IllegalArgumentException.class, () -> routes.page("company._team", "model.test", "../home"));
        assertThrows(IllegalArgumentException.class, () -> routes.api("company._team", "model.test", "home%2f"));
        assertThrows(IllegalArgumentException.class, () -> routes.page("Company", "model.test", "home"));
    }

    @Test
    void laterDtoMutationsCannotChangeTheDerivedSnapshot() {
        Flow flow = flow(PackagedTriggerMain.declarations());
        RouteDeclarations snapshot = RouteDeclarations.from(flow);
        ((PageTrigger) flow.getTriggers().getFirst()).setPageId("changed");
        ((PageTrigger) flow.getTriggers().getFirst()).setSchema("nsfile:///changed.json");
        ((ApiTrigger) flow.getTriggers().get(1)).setApiId("changed");
        ((ApiTrigger) flow.getTriggers().get(1)).setResultOutput("changed");
        assertEquals("home", snapshot.pages().getFirst().pageId());
        assertEquals("/pages/home.json", snapshot.pages().getFirst().schema().path());
        assertEquals("home", snapshot.apis().getFirst().apiId());
        assertEquals("response", snapshot.apis().getFirst().resultOutput());
    }

    @Test
    void flowWithoutAmisDeclarationsHasExplicitMissingLookup() {
        RouteDeclarations snapshot = RouteDeclarations.from(flow(""));
        assertEquals(RouteDeclarations.Status.ACTIVE, snapshot.activation());
        assertEquals(RouteDeclarations.Status.DECLARATION_MISSING, snapshot.api("company._team", "model.test", "home").status());
        assertEquals(RouteDeclarations.Status.DECLARATION_MISSING, snapshot.page("company._team", "model.test", "home").status());
    }

    private static Flow flow(String declarations) {
        return YamlParser.parse(PackagedTriggerMain.coreFlow() + declarations, Flow.class);
    }
}
