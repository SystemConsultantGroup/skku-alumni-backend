package com.scg.alumni.api.common;

public record ApiErrorResponse(
        String code,
        String message
) {
}
