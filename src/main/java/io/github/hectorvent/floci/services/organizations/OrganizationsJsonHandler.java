package io.github.hectorvent.floci.services.organizations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.organizations.model.CreateAccountStatus;
import io.github.hectorvent.floci.services.organizations.model.Handshake;
import io.github.hectorvent.floci.services.organizations.model.OrgAccount;
import io.github.hectorvent.floci.services.organizations.model.OrgPolicy;
import io.github.hectorvent.floci.services.organizations.model.OrgRoot;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationalUnit;
import io.github.hectorvent.floci.services.organizations.model.PolicyTypeSummary;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.1 handler for the AWS Organizations API.
 *
 * <p>Requests arrive with the {@code AWSOrganizationsV20161128.} target prefix and are
 * signed with the {@code organizations} credential scope. Unlike most JSON 1.1 services
 * in this codebase, Organizations request and response fields are PascalCase.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/organizations/latest/APIReference/Welcome.html">Organizations API Reference</a>
 */
@ApplicationScoped
public class OrganizationsJsonHandler {

    private final OrganizationsService service;
    private final ObjectMapper mapper;

    @Inject
    public OrganizationsJsonHandler(OrganizationsService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region, String account) {
        return switch (action) {
            case "CreateOrganization" -> handleCreateOrganization(request, account);
            case "DescribeOrganization" -> handleDescribeOrganization(account);
            case "DeleteOrganization" -> handleDeleteOrganization(account);
            case "CreateAccount" -> handleCreateAccount(request, account, false);
            case "CreateGovCloudAccount" -> handleCreateAccount(request, account, true);
            case "DescribeCreateAccountStatus" -> handleDescribeCreateAccountStatus(request, account);
            case "ListCreateAccountStatus" -> handleListCreateAccountStatus(request, account);
            case "DescribeAccount" -> handleDescribeAccount(request, account);
            case "ListAccounts" -> handleListAccounts(request, account);
            case "ListAccountsForParent" -> handleListAccountsForParent(request, account);
            case "CloseAccount" -> handleCloseAccount(request, account);
            case "RemoveAccountFromOrganization" -> handleRemoveAccountFromOrganization(request, account);
            case "LeaveOrganization" -> handleLeaveOrganization(account);
            case "MoveAccount" -> handleMoveAccount(request, account);
            case "ListParents" -> handleListParents(request, account);
            case "ListChildren" -> handleListChildren(request, account);
            case "ListRoots" -> handleListRoots(request, account);
            case "CreateOrganizationalUnit" -> handleCreateOrganizationalUnit(request, account);
            case "DescribeOrganizationalUnit" -> handleDescribeOrganizationalUnit(request, account);
            case "UpdateOrganizationalUnit" -> handleUpdateOrganizationalUnit(request, account);
            case "DeleteOrganizationalUnit" -> handleDeleteOrganizationalUnit(request, account);
            case "ListOrganizationalUnitsForParent" -> handleListOrganizationalUnitsForParent(request, account);
            case "InviteAccountToOrganization" -> handleInviteAccountToOrganization(request, account);
            case "AcceptHandshake" -> handleAcceptHandshake(request, account);
            case "DeclineHandshake" -> handleDeclineHandshake(request, account);
            case "CancelHandshake" -> handleCancelHandshake(request, account);
            case "DescribeHandshake" -> handleDescribeHandshake(request, account);
            case "ListHandshakesForAccount" -> handleListHandshakesForAccount(request, account);
            case "ListHandshakesForOrganization" -> handleListHandshakesForOrganization(request, account);
            case "EnableAllFeatures" -> handleEnableAllFeatures(account);
            case "RegisterDelegatedAdministrator" -> handleRegisterDelegatedAdministrator(request, account);
            case "DeregisterDelegatedAdministrator" -> handleDeregisterDelegatedAdministrator(request, account);
            case "ListDelegatedAdministrators" -> handleListDelegatedAdministrators(request, account);
            case "ListDelegatedServicesForAccount" -> handleListDelegatedServicesForAccount(request, account);
            case "EnableAWSServiceAccess" -> handleEnableAwsServiceAccess(request, account);
            case "DisableAWSServiceAccess" -> handleDisableAwsServiceAccess(request, account);
            case "ListAWSServiceAccessForOrganization" -> handleListAwsServiceAccess(request, account);
            case "PutResourcePolicy" -> handlePutResourcePolicy(request, account);
            case "DescribeResourcePolicy" -> handleDescribeResourcePolicy(account);
            case "DeleteResourcePolicy" -> handleDeleteResourcePolicy(account);
            case "CreatePolicy" -> handleCreatePolicy(request, account);
            case "DescribePolicy" -> handleDescribePolicy(request, account);
            case "UpdatePolicy" -> handleUpdatePolicy(request, account);
            case "DeletePolicy" -> handleDeletePolicy(request, account);
            case "ListPolicies" -> handleListPolicies(request, account);
            case "AttachPolicy" -> handleAttachPolicy(request, account);
            case "DetachPolicy" -> handleDetachPolicy(request, account);
            case "ListPoliciesForTarget" -> handleListPoliciesForTarget(request, account);
            case "ListTargetsForPolicy" -> handleListTargetsForPolicy(request, account);
            case "EnablePolicyType" -> handleEnablePolicyType(request, account);
            case "DisablePolicyType" -> handleDisablePolicyType(request, account);
            case "DescribeEffectivePolicy" -> handleDescribeEffectivePolicy(request, account);
            case "TagResource" -> handleTagResource(request, account);
            case "UntagResource" -> handleUntagResource(request, account);
            case "ListTagsForResource" -> handleListTagsForResource(request, account);
            default -> throw new AwsException("UnsupportedOperation",
                    "Operation " + action + " is not supported.", 400);
        };
    }

    // ---------------------------------------------------------------- organization lifecycle

    private Response handleCreateOrganization(JsonNode request, String account) {
        Organization org = service.createOrganization(account, request.path("FeatureSet").asText(null));
        return ok(mapper.createObjectNode().<ObjectNode>set("Organization", organizationNode(org)));
    }

    private Response handleDescribeOrganization(String account) {
        Organization org = service.describeOrganization(account);
        return ok(mapper.createObjectNode().<ObjectNode>set("Organization", organizationNode(org)));
    }

    private Response handleDeleteOrganization(String account) {
        service.deleteOrganization(account);
        return empty();
    }

    // ---------------------------------------------------------------- accounts

    private Response handleCreateAccount(JsonNode request, String account, boolean govCloud) {
        CreateAccountStatus status = service.createAccount(account,
                request.path("Email").asText(null),
                request.path("AccountName").asText(null),
                request.path("RoleName").asText(null),
                parseTags(request.path("Tags")),
                govCloud);
        return ok(mapper.createObjectNode().<ObjectNode>set("CreateAccountStatus", statusNode(status)));
    }

    private Response handleDescribeCreateAccountStatus(JsonNode request, String account) {
        CreateAccountStatus status =
                service.describeCreateAccountStatus(account, text(request, "CreateAccountRequestId"));
        return ok(mapper.createObjectNode().<ObjectNode>set("CreateAccountStatus", statusNode(status)));
    }

    private Response handleListCreateAccountStatus(JsonNode request, String account) {
        List<String> states = stringList(request.path("States"));
        List<CreateAccountStatus> statuses = service.listCreateAccountStatus(account, states);
        return paged(request, statuses, "CreateAccountStatuses", this::statusNode);
    }

    private Response handleDescribeAccount(JsonNode request, String account) {
        OrgAccount found = service.describeAccount(account, text(request, "AccountId"));
        return ok(mapper.createObjectNode().<ObjectNode>set("Account", accountNode(found)));
    }

    private Response handleListAccounts(JsonNode request, String account) {
        return paged(request, service.listAccounts(account), "Accounts", this::accountNode);
    }

    private Response handleListAccountsForParent(JsonNode request, String account) {
        List<OrgAccount> accounts = service.listAccountsForParent(account, text(request, "ParentId"));
        return paged(request, accounts, "Accounts", this::accountNode);
    }

    private Response handleCloseAccount(JsonNode request, String account) {
        service.closeAccount(account, text(request, "AccountId"));
        return empty();
    }

    private Response handleRemoveAccountFromOrganization(JsonNode request, String account) {
        service.removeAccountFromOrganization(account, text(request, "AccountId"));
        return empty();
    }

    private Response handleLeaveOrganization(String account) {
        service.leaveOrganization(account);
        return empty();
    }

    private Response handleMoveAccount(JsonNode request, String account) {
        service.moveAccount(account, text(request, "AccountId"),
                text(request, "SourceParentId"), text(request, "DestinationParentId"));
        return empty();
    }

    private Response handleListParents(JsonNode request, String account) {
        List<OrganizationsService.NodeRef> parents =
                service.listParents(account, text(request, "ChildId"));
        return paged(request, parents, "Parents", this::nodeRefNode);
    }

    private Response handleListChildren(JsonNode request, String account) {
        List<OrganizationsService.NodeRef> children = service.listChildren(account,
                text(request, "ParentId"), text(request, "ChildType"));
        return paged(request, children, "Children", this::nodeRefNode);
    }

    // ---------------------------------------------------------------- roots and OUs

    private Response handleListRoots(JsonNode request, String account) {
        return paged(request, service.listRoots(account), "Roots", this::rootNode);
    }

    private Response handleCreateOrganizationalUnit(JsonNode request, String account) {
        OrganizationalUnit ou = service.createOrganizationalUnit(account,
                text(request, "ParentId"), text(request, "Name"), parseTags(request.path("Tags")));
        return ok(mapper.createObjectNode().<ObjectNode>set("OrganizationalUnit", ouNode(ou)));
    }

    private Response handleDescribeOrganizationalUnit(JsonNode request, String account) {
        OrganizationalUnit ou =
                service.describeOrganizationalUnit(account, text(request, "OrganizationalUnitId"));
        return ok(mapper.createObjectNode().<ObjectNode>set("OrganizationalUnit", ouNode(ou)));
    }

    private Response handleUpdateOrganizationalUnit(JsonNode request, String account) {
        OrganizationalUnit ou = service.updateOrganizationalUnit(account,
                text(request, "OrganizationalUnitId"), request.path("Name").asText(null));
        return ok(mapper.createObjectNode().<ObjectNode>set("OrganizationalUnit", ouNode(ou)));
    }

    private Response handleDeleteOrganizationalUnit(JsonNode request, String account) {
        service.deleteOrganizationalUnit(account, text(request, "OrganizationalUnitId"));
        return empty();
    }

    private Response handleListOrganizationalUnitsForParent(JsonNode request, String account) {
        List<OrganizationalUnit> ous =
                service.listOrganizationalUnitsForParent(account, text(request, "ParentId"));
        return paged(request, ous, "OrganizationalUnits", this::ouNode);
    }

    // ---------------------------------------------------------------- handshakes

    private Response handleInviteAccountToOrganization(JsonNode request, String account) {
        JsonNode target = request.path("Target");
        Handshake handshake = service.inviteAccountToOrganization(account,
                target.path("Id").asText(null),
                target.path("Type").asText("ACCOUNT"),
                request.path("Notes").asText(null));
        return ok(mapper.createObjectNode().<ObjectNode>set("Handshake", handshakeNode(handshake)));
    }

    private Response handleAcceptHandshake(JsonNode request, String account) {
        Handshake handshake = service.acceptHandshake(account, text(request, "HandshakeId"));
        return ok(mapper.createObjectNode().<ObjectNode>set("Handshake", handshakeNode(handshake)));
    }

    private Response handleDeclineHandshake(JsonNode request, String account) {
        Handshake handshake = service.declineHandshake(account, text(request, "HandshakeId"));
        return ok(mapper.createObjectNode().<ObjectNode>set("Handshake", handshakeNode(handshake)));
    }

    private Response handleCancelHandshake(JsonNode request, String account) {
        Handshake handshake = service.cancelHandshake(account, text(request, "HandshakeId"));
        return ok(mapper.createObjectNode().<ObjectNode>set("Handshake", handshakeNode(handshake)));
    }

    private Response handleDescribeHandshake(JsonNode request, String account) {
        Handshake handshake = service.describeHandshake(account, text(request, "HandshakeId"));
        return ok(mapper.createObjectNode().<ObjectNode>set("Handshake", handshakeNode(handshake)));
    }

    private Response handleListHandshakesForAccount(JsonNode request, String account) {
        List<Handshake> handshakes = service.listHandshakesForAccount(account,
                request.path("Filter").path("ActionType").asText(null));
        return paged(request, handshakes, "Handshakes", this::handshakeNode);
    }

    private Response handleListHandshakesForOrganization(JsonNode request, String account) {
        List<Handshake> handshakes = service.listHandshakesForOrganization(account,
                request.path("Filter").path("ActionType").asText(null));
        return paged(request, handshakes, "Handshakes", this::handshakeNode);
    }

    private Response handleEnableAllFeatures(String account) {
        Handshake handshake = service.enableAllFeatures(account);
        return ok(mapper.createObjectNode().<ObjectNode>set("Handshake", handshakeNode(handshake)));
    }

    // ---------------------------------------------------------------- delegated administrators

    private Response handleRegisterDelegatedAdministrator(JsonNode request, String account) {
        service.registerDelegatedAdministrator(account,
                text(request, "AccountId"), text(request, "ServicePrincipal"));
        return empty();
    }

    private Response handleDeregisterDelegatedAdministrator(JsonNode request, String account) {
        service.deregisterDelegatedAdministrator(account,
                text(request, "AccountId"), text(request, "ServicePrincipal"));
        return empty();
    }

    private Response handleListDelegatedAdministrators(JsonNode request, String account) {
        List<OrgAccount> admins = service.listDelegatedAdministrators(account,
                request.path("ServicePrincipal").asText(null));
        return paged(request, admins, "DelegatedAdministrators", this::delegatedAdministratorNode);
    }

    private Response handleListDelegatedServicesForAccount(JsonNode request, String account) {
        Map<String, Double> services =
                service.listDelegatedServicesForAccount(account, text(request, "AccountId"));
        ObjectNode response = mapper.createObjectNode();
        ArrayNode array = response.putArray("DelegatedServices");
        services.forEach((principal, enabled) -> array.addObject()
                .put("ServicePrincipal", principal)
                .put("DelegationEnabledDate", enabled));
        return ok(response);
    }

    // ---------------------------------------------------------------- AWS service access

    private Response handleEnableAwsServiceAccess(JsonNode request, String account) {
        service.enableAwsServiceAccess(account, text(request, "ServicePrincipal"));
        return empty();
    }

    private Response handleDisableAwsServiceAccess(JsonNode request, String account) {
        service.disableAwsServiceAccess(account, text(request, "ServicePrincipal"));
        return empty();
    }

    private Response handleListAwsServiceAccess(JsonNode request, String account) {
        Map<String, Double> principals = service.listAwsServiceAccessForOrganization(account);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode array = response.putArray("EnabledServicePrincipals");
        principals.forEach((principal, enabled) -> array.addObject()
                .put("ServicePrincipal", principal)
                .put("DateEnabled", enabled));
        return ok(response);
    }

    // ---------------------------------------------------------------- resource policy

    private Response handlePutResourcePolicy(JsonNode request, String account) {
        OrganizationsService.ResourcePolicy policy = service.putResourcePolicy(account,
                text(request, "Content"), parseTags(request.path("Tags")));
        return ok(mapper.createObjectNode().<ObjectNode>set("ResourcePolicy", resourcePolicyNode(policy)));
    }

    private Response handleDescribeResourcePolicy(String account) {
        OrganizationsService.ResourcePolicy policy = service.describeResourcePolicy(account);
        return ok(mapper.createObjectNode().<ObjectNode>set("ResourcePolicy", resourcePolicyNode(policy)));
    }

    private Response handleDeleteResourcePolicy(String account) {
        service.deleteResourcePolicy(account);
        return empty();
    }

    // ---------------------------------------------------------------- policies

    private Response handleCreatePolicy(JsonNode request, String account) {
        OrgPolicy policy = service.createPolicy(account,
                text(request, "Name"),
                request.path("Description").asText(null),
                text(request, "Type"),
                text(request, "Content"),
                parseTags(request.path("Tags")));
        return ok(mapper.createObjectNode().<ObjectNode>set("Policy", policyNode(policy)));
    }

    private Response handleDescribePolicy(JsonNode request, String account) {
        OrgPolicy policy = service.describePolicy(account, text(request, "PolicyId"));
        return ok(mapper.createObjectNode().<ObjectNode>set("Policy", policyNode(policy)));
    }

    private Response handleUpdatePolicy(JsonNode request, String account) {
        OrgPolicy policy = service.updatePolicy(account,
                text(request, "PolicyId"),
                request.path("Name").asText(null),
                request.hasNonNull("Description") ? request.path("Description").asText() : null,
                request.path("Content").asText(null));
        return ok(mapper.createObjectNode().<ObjectNode>set("Policy", policyNode(policy)));
    }

    private Response handleDeletePolicy(JsonNode request, String account) {
        service.deletePolicy(account, text(request, "PolicyId"));
        return empty();
    }

    private Response handleListPolicies(JsonNode request, String account) {
        List<OrgPolicy> policies = service.listPolicies(account, text(request, "Filter"));
        return paged(request, policies, "Policies", this::policySummaryNode);
    }

    private Response handleAttachPolicy(JsonNode request, String account) {
        service.attachPolicy(account, text(request, "PolicyId"), text(request, "TargetId"));
        return empty();
    }

    private Response handleDetachPolicy(JsonNode request, String account) {
        service.detachPolicy(account, text(request, "PolicyId"), text(request, "TargetId"));
        return empty();
    }

    private Response handleListPoliciesForTarget(JsonNode request, String account) {
        List<OrgPolicy> policies = service.listPoliciesForTarget(account,
                text(request, "TargetId"), text(request, "Filter"));
        return paged(request, policies, "Policies", this::policySummaryNode);
    }

    private Response handleListTargetsForPolicy(JsonNode request, String account) {
        List<OrganizationsService.TargetRef> targets =
                service.listTargetsForPolicy(account, text(request, "PolicyId"));
        return paged(request, targets, "Targets", this::targetRefNode);
    }

    private Response handleEnablePolicyType(JsonNode request, String account) {
        OrgRoot root = service.enablePolicyType(account,
                text(request, "RootId"), text(request, "PolicyType"));
        return ok(mapper.createObjectNode().<ObjectNode>set("Root", rootNode(root)));
    }

    private Response handleDisablePolicyType(JsonNode request, String account) {
        OrgRoot root = service.disablePolicyType(account,
                text(request, "RootId"), text(request, "PolicyType"));
        return ok(mapper.createObjectNode().<ObjectNode>set("Root", rootNode(root)));
    }

    private Response handleDescribeEffectivePolicy(JsonNode request, String account) {
        OrganizationsService.EffectivePolicy effective = service.describeEffectivePolicy(account,
                text(request, "PolicyType"), request.path("TargetId").asText(null));
        ObjectNode node = mapper.createObjectNode();
        node.put("PolicyContent", effective.policyContent());
        node.put("LastUpdatedTimestamp", effective.lastUpdatedTimestamp());
        node.put("TargetId", effective.targetId());
        node.put("PolicyType", effective.policyType());
        return ok(mapper.createObjectNode().<ObjectNode>set("EffectivePolicy", node));
    }

    // ---------------------------------------------------------------- tagging

    private Response handleTagResource(JsonNode request, String account) {
        Map<String, String> tags = parseTags(request.path("Tags"));
        if (tags == null || tags.isEmpty()) {
            throw new AwsException("InvalidInputException", "Tags is required", 400);
        }
        service.tagResource(account, text(request, "ResourceId"), tags);
        return empty();
    }

    private Response handleUntagResource(JsonNode request, String account) {
        List<String> keys = stringList(request.path("TagKeys"));
        if (keys.isEmpty()) {
            throw new AwsException("InvalidInputException", "TagKeys is required", 400);
        }
        service.untagResource(account, text(request, "ResourceId"), keys);
        return empty();
    }

    private Response handleListTagsForResource(JsonNode request, String account) {
        Map<String, String> tags = service.tagsForResource(account, text(request, "ResourceId"));
        ObjectNode response = mapper.createObjectNode();
        response.set("Tags", tagsNode(tags));
        return ok(response);
    }

    // ---------------------------------------------------------------- serialization

    private ObjectNode organizationNode(Organization org) {
        ObjectNode node = mapper.createObjectNode();
        node.put("Id", org.getId());
        node.put("Arn", org.getArn());
        node.put("FeatureSet", org.getFeatureSet());
        node.put("MasterAccountArn", org.getManagementAccountArn());
        node.put("MasterAccountId", org.getManagementAccountId());
        node.put("MasterAccountEmail", org.getManagementAccountEmail());
        ArrayNode types = node.putArray("AvailablePolicyTypes");
        org.getAvailablePolicyTypes().forEach(t -> types.add(policyTypeNode(t)));
        return node;
    }

    private ObjectNode accountNode(OrgAccount account) {
        ObjectNode node = mapper.createObjectNode();
        node.put("Id", account.getId());
        node.put("Arn", account.getArn());
        node.put("Email", account.getEmail());
        node.put("Name", account.getName());
        node.put("Status", account.getStatus());
        node.put("JoinedMethod", account.getJoinedMethod());
        node.put("JoinedTimestamp", account.getJoinedTimestamp());
        return node;
    }

    private ObjectNode rootNode(OrgRoot root) {
        ObjectNode node = mapper.createObjectNode();
        node.put("Id", root.getId());
        node.put("Arn", root.getArn());
        node.put("Name", root.getName());
        ArrayNode types = node.putArray("PolicyTypes");
        root.getPolicyTypes().forEach(t -> types.add(policyTypeNode(t)));
        return node;
    }

    private ObjectNode ouNode(OrganizationalUnit ou) {
        ObjectNode node = mapper.createObjectNode();
        node.put("Id", ou.getId());
        node.put("Arn", ou.getArn());
        node.put("Name", ou.getName());
        return node;
    }

    private ObjectNode statusNode(CreateAccountStatus status) {
        ObjectNode node = mapper.createObjectNode();
        node.put("Id", status.getId());
        node.put("AccountName", status.getAccountName());
        node.put("State", status.getState());
        node.put("RequestedTimestamp", status.getRequestedTimestamp());
        if (status.getCompletedTimestamp() != null) {
            node.put("CompletedTimestamp", status.getCompletedTimestamp());
        }
        node.put("AccountId", status.getAccountId());
        if (status.getGovCloudAccountId() != null) {
            node.put("GovCloudAccountId", status.getGovCloudAccountId());
        }
        if (status.getFailureReason() != null) {
            node.put("FailureReason", status.getFailureReason());
        }
        return node;
    }

    private ObjectNode policyTypeNode(PolicyTypeSummary type) {
        return mapper.createObjectNode().put("Type", type.getType()).put("Status", type.getStatus());
    }

    private ObjectNode policySummaryNode(OrgPolicy policy) {
        ObjectNode node = mapper.createObjectNode();
        node.put("Id", policy.getId());
        node.put("Arn", policy.getArn());
        node.put("Name", policy.getName());
        node.put("Description", policy.getDescription());
        node.put("Type", policy.getType());
        node.put("AwsManaged", policy.isAwsManaged());
        return node;
    }

    private ObjectNode policyNode(OrgPolicy policy) {
        ObjectNode node = mapper.createObjectNode();
        node.set("PolicySummary", policySummaryNode(policy));
        node.put("Content", policy.getContent());
        return node;
    }

    private ObjectNode targetRefNode(OrganizationsService.TargetRef target) {
        ObjectNode node = mapper.createObjectNode();
        node.put("TargetId", target.targetId());
        node.put("Arn", target.arn());
        node.put("Name", target.name());
        node.put("Type", target.type());
        return node;
    }

    private ObjectNode handshakeNode(Handshake handshake) {
        ObjectNode node = mapper.createObjectNode();
        node.put("Id", handshake.getId());
        node.put("Arn", handshake.getArn());
        ArrayNode parties = node.putArray("Parties");
        parties.addObject().put("Id", handshake.getOrgId()).put("Type", "ORGANIZATION");
        parties.addObject().put("Id", handshake.getTargetAccountId()).put("Type", "ACCOUNT");
        node.put("State", handshake.effectiveState(OrganizationsService.now()));
        node.put("RequestedTimestamp", handshake.getRequestedTimestamp());
        node.put("ExpirationTimestamp", handshake.getExpirationTimestamp());
        node.put("Action", handshake.getAction());
        ArrayNode resources = node.putArray("Resources");
        resources.addObject().put("Value", handshake.getOrgId()).put("Type", "ORGANIZATION");
        resources.addObject().put("Value", handshake.getTargetAccountId()).put("Type", "ACCOUNT");
        if (handshake.getNotes() != null) {
            resources.addObject().put("Value", handshake.getNotes()).put("Type", "NOTES");
        }
        if (handshake.getParentHandshakeId() != null) {
            resources.addObject().put("Value", handshake.getParentHandshakeId())
                    .put("Type", "PARENT_HANDSHAKE");
        }
        return node;
    }

    private ObjectNode delegatedAdministratorNode(OrgAccount account) {
        ObjectNode node = accountNode(account);
        account.getDelegatedServices().values().stream()
                .min(Double::compareTo)
                .ifPresent(first -> node.put("DelegationEnabledDate", first));
        return node;
    }

    private ObjectNode resourcePolicyNode(OrganizationsService.ResourcePolicy policy) {
        ObjectNode node = mapper.createObjectNode();
        ObjectNode summary = node.putObject("ResourcePolicySummary");
        summary.put("Id", policy.id());
        summary.put("Arn", policy.arn());
        node.put("Content", policy.content());
        return node;
    }

    private ObjectNode nodeRefNode(OrganizationsService.NodeRef ref) {
        return mapper.createObjectNode().put("Id", ref.id()).put("Type", ref.type());
    }

    private ArrayNode tagsNode(Map<String, String> tags) {
        ArrayNode node = mapper.createArrayNode();
        tags.forEach((key, value) -> node.addObject().put("Key", key).put("Value", value));
        return node;
    }

    static Map<String, String> parseTags(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        for (JsonNode tag : node) {
            String key = tag.path("Key").asText(null);
            if (key == null || key.isBlank()) {
                throw new AwsException("InvalidInputException", "Tag keys must be non-empty", 400);
            }
            tags.put(key, tag.path("Value").asText(""));
        }
        if (tags.size() > 50) {
            throw new AwsException("ConstraintViolationException",
                    "A resource can have at most 50 tags.", 400);
        }
        return tags;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(v -> values.add(v.asText()));
        }
        return values;
    }

    private static String text(JsonNode request, String field) {
        String value = request.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidInputException", field + " is required", 400);
        }
        return value;
    }

    // ---------------------------------------------------------------- pagination

    /** Serializes one item of a paged list response. */
    @FunctionalInterface
    private interface ItemNode<T> {
        ObjectNode toNode(T item);
    }

    private <T> Response paged(JsonNode request, List<T> items, String field, ItemNode<T> itemNode) {
        Page page = page(request, items.size(), 20);
        ObjectNode response = mapper.createObjectNode();
        ArrayNode array = response.putArray(field);
        for (int i = page.start(); i < page.end(); i++) {
            array.add(itemNode.toNode(items.get(i)));
        }
        if (page.end() < items.size()) {
            response.put("NextToken", Integer.toString(page.end()));
        }
        return ok(response);
    }

    private static Page page(JsonNode request, int size, int maximum) {
        int start = 0;
        if (request.hasNonNull("NextToken")) {
            try {
                start = Integer.parseInt(request.path("NextToken").asText());
            } catch (NumberFormatException e) {
                throw new AwsException("InvalidInputException", "Invalid NextToken", 400);
            }
        }
        int requested = request.hasNonNull("MaxResults") ? request.path("MaxResults").asInt(maximum) : maximum;
        if (requested < 1 || start < 0 || start > size) {
            throw new AwsException("InvalidInputException", "Invalid pagination parameters", 400);
        }
        return new Page(start, Math.min(size, start + Math.min(requested, maximum)));
    }

    private record Page(int start, int end) {}

    private static Response ok(ObjectNode node) {
        return Response.ok(node).build();
    }

    private Response empty() {
        return Response.ok(mapper.createObjectNode()).build();
    }
}
