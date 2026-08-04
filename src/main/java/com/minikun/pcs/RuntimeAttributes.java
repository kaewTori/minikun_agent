package com.minikun.pcs;

public record RuntimeAttributes(
        boolean searchRequested,
        boolean toolInvocationRequested,
        ResponseMode responseMode) {
    public static final RuntimeAttributes EMPTY = new RuntimeAttributes(false, false, ResponseMode.DEFAULT);

    public RuntimeAttributes {
        responseMode = responseMode == null ? ResponseMode.DEFAULT : responseMode;
    }
}
