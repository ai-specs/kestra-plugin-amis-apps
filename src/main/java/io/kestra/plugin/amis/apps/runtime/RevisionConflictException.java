package io.kestra.plugin.amis.apps.runtime;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;

public final class RevisionConflictException extends HttpStatusException {
    public RevisionConflictException() {
        super(HttpStatus.CONFLICT, "Flow declaration revision has changed");
    }
}
