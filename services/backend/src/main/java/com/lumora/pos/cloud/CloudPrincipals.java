package com.lumora.pos.cloud;

import com.lumora.pos.web.RejectedException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Reads the principal {@link TenantAuthFilter} left on the request, and asserts its kind (M4-05).
 *
 * <p>One helper rather than the same six lines in every cloud controller, because those six lines
 * are the only thing standing between one shop's data and another's — and a copy of them is a copy
 * that can be edited, skipped, or written slightly differently in the endpoint added next year.
 */
public final class CloudPrincipals {

    private CloudPrincipals() {}

    /**
     * @throws IllegalStateException if the filter did not run. That is a wiring mistake rather than
     *     a caller's, and it fails loudly instead of defaulting to some tenant — the quiet version
     *     of this bug writes one shop's sales into another's.
     * @throws RejectedException if the credential is valid but of the wrong kind. A 403, not a 401:
     *     the caller authenticated fine and is simply not what this endpoint serves, and telling
     *     them to authenticate again would send them round a loop that cannot terminate.
     */
    public static AuthenticatedPrincipal require(
            HttpServletRequest request, AuthenticatedPrincipal.Kind kind) {
        Object found = request.getAttribute(TenantAuthFilter.PRINCIPAL_ATTRIBUTE);
        if (!(found instanceof AuthenticatedPrincipal principal)) {
            throw new IllegalStateException(
                    "No authenticated principal on the request — TenantAuthFilter is not covering "
                            + request.getRequestURI());
        }
        if (!principal.is(kind)) {
            throw new WrongCredentialKindException(
                    "This endpoint is for a %s credential.".formatted(kind.name().toLowerCase()));
        }
        return principal;
    }

    /** Distinct from {@link RejectedException} so the handler can answer 403 rather than 422. */
    public static class WrongCredentialKindException extends RuntimeException {
        public WrongCredentialKindException(String message) {
            super(message);
        }
    }
}
