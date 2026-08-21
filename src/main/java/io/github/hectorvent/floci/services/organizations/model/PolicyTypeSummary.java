package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A policy type and its enablement status on a root.
 *
 * @see <a href="https://docs.aws.amazon.com/organizations/latest/APIReference/API_PolicyTypeSummary.html">PolicyTypeSummary</a>
 */
@RegisterForReflection
public class PolicyTypeSummary {

    private String type;
    private String status;

    public PolicyTypeSummary() {}

    public PolicyTypeSummary(String type, String status) {
        this.type = type;
        this.status = status;
    }

    public String getType() { return type; }
    public void setType(String v) { this.type = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
}
