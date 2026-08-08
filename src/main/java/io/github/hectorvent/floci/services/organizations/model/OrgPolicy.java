package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An Organizations policy of any of the four types (SERVICE_CONTROL_POLICY, TAG_POLICY,
 * BACKUP_POLICY, AISERVICES_OPT_OUT_POLICY), plus its attachments.
 *
 * <p>{@code targetIds} is the authoritative attachment record: the roots, OUs, and
 * accounts this policy is attached to, in attach order.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/organizations/latest/APIReference/API_Policy.html">Policy</a>
 */
@RegisterForReflection
public class OrgPolicy {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String type;
    private String content;
    private boolean awsManaged;
    private List<String> targetIds = new ArrayList<>();
    private double created;
    private double updated;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public String getArn() { return arn; }
    public void setArn(String v) { this.arn = v; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }

    public String getType() { return type; }
    public void setType(String v) { this.type = v; }

    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }

    public boolean isAwsManaged() { return awsManaged; }
    public void setAwsManaged(boolean v) { this.awsManaged = v; }

    public List<String> getTargetIds() { return targetIds; }
    public void setTargetIds(List<String> v) { this.targetIds = v == null ? new ArrayList<>() : v; }

    public double getCreated() { return created; }
    public void setCreated(double v) { this.created = v; }

    public double getUpdated() { return updated; }
    public void setUpdated(double v) { this.updated = v; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> v) { this.tags = v == null ? new LinkedHashMap<>() : v; }
}
