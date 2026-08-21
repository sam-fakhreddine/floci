package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A handshake: the two-party agreement behind invitations (INVITE) and the
 * all-features migration (ENABLE_ALL_FEATURES with one APPROVE_ALL_FEATURES per member).
 *
 * <p>Handshakes are stored in the management account's namespace like all other org
 * state; {@code managementAccountId} lets the invited account — which is not yet a
 * member — find its open handshakes by scanning all namespaces. {@code EXPIRED} is
 * computed lazily from {@code expirationTimestamp} when the handshake is read.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/organizations/latest/APIReference/API_Handshake.html">Handshake</a>
 */
@RegisterForReflection
public class Handshake {

    private String id;
    private String arn;
    private String state;
    private String action;
    private double requestedTimestamp;
    private double expirationTimestamp;
    private String targetAccountId;
    private String managementAccountId;
    private String orgId;
    private String notes;
    private String parentHandshakeId;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public String getArn() { return arn; }
    public void setArn(String v) { this.arn = v; }

    public String getState() { return state; }
    public void setState(String v) { this.state = v; }

    public String getAction() { return action; }
    public void setAction(String v) { this.action = v; }

    public double getRequestedTimestamp() { return requestedTimestamp; }
    public void setRequestedTimestamp(double v) { this.requestedTimestamp = v; }

    public double getExpirationTimestamp() { return expirationTimestamp; }
    public void setExpirationTimestamp(double v) { this.expirationTimestamp = v; }

    public String getTargetAccountId() { return targetAccountId; }
    public void setTargetAccountId(String v) { this.targetAccountId = v; }

    public String getManagementAccountId() { return managementAccountId; }
    public void setManagementAccountId(String v) { this.managementAccountId = v; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String v) { this.orgId = v; }

    public String getNotes() { return notes; }
    public void setNotes(String v) { this.notes = v; }

    public String getParentHandshakeId() { return parentHandshakeId; }
    public void setParentHandshakeId(String v) { this.parentHandshakeId = v; }

    /** OPEN past its expiration reads as EXPIRED; stored state is not rewritten. */
    public String effectiveState(double now) {
        if ("OPEN".equals(state) && now > expirationTimestamp) {
            return "EXPIRED";
        }
        return state;
    }
}
