package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The organization itself, stored once per management account under a fixed key.
 *
 * <p>The API's field names still say "master account" ({@code MasterAccountId} and peers);
 * the model uses the modern management-account terminology and the handler maps to the
 * wire names.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/organizations/latest/APIReference/API_Organization.html">Organization</a>
 */
@RegisterForReflection
public class Organization {

    private String id;
    private String arn;
    private String featureSet;
    private String managementAccountId;
    private String managementAccountArn;
    private String managementAccountEmail;
    private List<PolicyTypeSummary> availablePolicyTypes = new ArrayList<>();
    /** Service principal → epoch seconds when access was enabled. */
    private Map<String, Double> enabledServicePrincipals = new LinkedHashMap<>();
    private String resourcePolicyId;
    private String resourcePolicyContent;
    private Map<String, String> resourcePolicyTags = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public String getArn() { return arn; }
    public void setArn(String v) { this.arn = v; }

    public String getFeatureSet() { return featureSet; }
    public void setFeatureSet(String v) { this.featureSet = v; }

    public String getManagementAccountId() { return managementAccountId; }
    public void setManagementAccountId(String v) { this.managementAccountId = v; }

    public String getManagementAccountArn() { return managementAccountArn; }
    public void setManagementAccountArn(String v) { this.managementAccountArn = v; }

    public String getManagementAccountEmail() { return managementAccountEmail; }
    public void setManagementAccountEmail(String v) { this.managementAccountEmail = v; }

    public List<PolicyTypeSummary> getAvailablePolicyTypes() { return availablePolicyTypes; }
    public void setAvailablePolicyTypes(List<PolicyTypeSummary> v) {
        this.availablePolicyTypes = v == null ? new ArrayList<>() : v;
    }

    public Map<String, Double> getEnabledServicePrincipals() { return enabledServicePrincipals; }
    public void setEnabledServicePrincipals(Map<String, Double> v) {
        this.enabledServicePrincipals = v == null ? new LinkedHashMap<>() : v;
    }

    public String getResourcePolicyId() { return resourcePolicyId; }
    public void setResourcePolicyId(String v) { this.resourcePolicyId = v; }

    public String getResourcePolicyContent() { return resourcePolicyContent; }
    public void setResourcePolicyContent(String v) { this.resourcePolicyContent = v; }

    public Map<String, String> getResourcePolicyTags() { return resourcePolicyTags; }
    public void setResourcePolicyTags(Map<String, String> v) {
        this.resourcePolicyTags = v == null ? new LinkedHashMap<>() : v;
    }
}
