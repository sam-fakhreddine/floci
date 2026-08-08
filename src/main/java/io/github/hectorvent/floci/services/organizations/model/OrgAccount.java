package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A member account of an organization, including the management account itself.
 *
 * <p>{@code managementAccountId} is a back-reference to the namespace that owns the
 * organization's state: member accounts calling the API are resolved to their
 * organization by scanning account records for a matching {@code id} and following
 * this field.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/organizations/latest/APIReference/API_Account.html">Account</a>
 */
@RegisterForReflection
public class OrgAccount {

    private String id;
    private String arn;
    private String name;
    private String email;
    private String status;
    private String joinedMethod;
    private double joinedTimestamp;
    private String parentId;
    private String managementAccountId;
    /** Service principal → epoch seconds when this account was registered as delegated admin. */
    private Map<String, Double> delegatedServices = new LinkedHashMap<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public String getArn() { return arn; }
    public void setArn(String v) { this.arn = v; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public String getJoinedMethod() { return joinedMethod; }
    public void setJoinedMethod(String v) { this.joinedMethod = v; }

    public double getJoinedTimestamp() { return joinedTimestamp; }
    public void setJoinedTimestamp(double v) { this.joinedTimestamp = v; }

    public String getParentId() { return parentId; }
    public void setParentId(String v) { this.parentId = v; }

    public String getManagementAccountId() { return managementAccountId; }
    public void setManagementAccountId(String v) { this.managementAccountId = v; }

    public Map<String, Double> getDelegatedServices() { return delegatedServices; }
    public void setDelegatedServices(Map<String, Double> v) {
        this.delegatedServices = v == null ? new LinkedHashMap<>() : v;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> v) { this.tags = v == null ? new LinkedHashMap<>() : v; }
}
