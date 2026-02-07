package io.janitor.finops.exception;

/**
 * Thrown when a K8s API call fails (e.g. namespace not found, RBAC denied).
 */
public class KubernetesOperationException extends RuntimeException {

    private final String namespace;

    public KubernetesOperationException(String namespace, String message) {
        super(message);
        this.namespace = namespace;
    }

    public KubernetesOperationException(String namespace, String message, Throwable cause) {
        super(message, cause);
        this.namespace = namespace;
    }

    public String getNamespace() { return namespace; }
}
