package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An organizational unit: a container for accounts and other OUs under a root.
 *
 * @see <a href="https://docs.aws.amazon.com/organizations/latest/APIReference/API_OrganizationalUnit.html">OrganizationalUnit</a>
 */
@RegisterForReflection
public class OrganizationalUnit {

    private String id;
    private String arn;
    private String name;
    private String parentId;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public String getArn() { return arn; }
    public void setArn(String v) { this.arn = v; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public String getParentId() { return parentId; }
    public void setParentId(String v) { this.parentId = v; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> v) { this.tags = v == null ? new LinkedHashMap<>() : v; }
}
