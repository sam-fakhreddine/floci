package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The root of an organization's OU tree. Every organization has exactly one.
 *
 * @see <a href="https://docs.aws.amazon.com/organizations/latest/APIReference/API_Root.html">Root</a>
 */
@RegisterForReflection
public class OrgRoot {

    private String id;
    private String arn;
    private String name;
    private List<PolicyTypeSummary> policyTypes = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public String getArn() { return arn; }
    public void setArn(String v) { this.arn = v; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public List<PolicyTypeSummary> getPolicyTypes() { return policyTypes; }
    public void setPolicyTypes(List<PolicyTypeSummary> v) {
        this.policyTypes = v == null ? new ArrayList<>() : v;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> v) { this.tags = v == null ? new LinkedHashMap<>() : v; }
}
