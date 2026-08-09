package io.github.hectorvent.floci.services.organizations;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.ScpProvider;
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
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AWS Organizations control plane.
 *
 * <p>Organizations is a global service (no region in storage keys) whose state is shared by
 * every member account. All state lives in the <em>management account's</em> storage
 * namespace: every store access goes through the explicit {@code *ForAccount} overloads
 * with the management account ID, never the implicit caller-prefixed methods — a member
 * account calling the API must read and mutate the same records the management account
 * sees. Member accounts are resolved to their organization through the
 * {@code managementAccountId} back-reference on {@link OrgAccount}.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/organizations/latest/APIReference/Welcome.html">Organizations API Reference</a>
 */
@ApplicationScoped
public class OrganizationsService implements ScpProvider {

    private static final Logger LOG = Logger.getLogger(OrganizationsService.class);

    /** The single Organization record's key inside the management account's namespace. */
    private static final String ORG_KEY = "org";

    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    static final String STATUS_ACTIVE = "ACTIVE";
    static final String STATUS_SUSPENDED = "SUSPENDED";

    static final String SCP_TYPE = "SERVICE_CONTROL_POLICY";
    static final Set<String> POLICY_TYPES = Set.of(
            SCP_TYPE, "TAG_POLICY", "BACKUP_POLICY", "AISERVICES_OPT_OUT_POLICY");

    static final String FULL_AWS_ACCESS_ID = "p-FullAWSAccess";
    static final String FULL_AWS_ACCESS_CONTENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Action\":\"*\",\"Resource\":\"*\"}]}";

    /** Handshakes expire 15 days after creation, matching AWS. */
    private static final double HANDSHAKE_TTL_SECONDS = 15 * 24 * 3600;

    private final AccountAwareStorageBackend<Organization> organizationStore;
    private final AccountAwareStorageBackend<OrgAccount> accountStore;
    private final AccountAwareStorageBackend<OrgRoot> rootStore;
    private final AccountAwareStorageBackend<OrganizationalUnit> ouStore;
    private final AccountAwareStorageBackend<CreateAccountStatus> accountStatusStore;
    private final AccountAwareStorageBackend<OrgPolicy> policyStore;
    private final AccountAwareStorageBackend<Handshake> handshakeStore;
    private final ObjectMapper mapper;
    /** Nullable in unit tests: role provisioning on CreateAccount is best-effort. */
    private final IamService iamService;
    /**
     * Optional override for the management account's email. AWS derives the management
     * account email from the account that creates the organization; floci has no such
     * signup identity, so when this is blank it falls back to a synthetic
     * {@code management+<id>@example.com}. Tools that resolve accounts by email (e.g. the
     * AWS Landing Zone Accelerator) require this to match their config, so operators can
     * set it via {@code floci.services.organizations.management-account-email}.
     */
    private final String managementAccountEmailOverride;
    private final boolean scpEnforcementEnabled;

    @Inject
    @SuppressWarnings("unchecked")
    public OrganizationsService(StorageFactory storageFactory, ObjectMapper mapper,
                                IamService iamService, EmulatorConfig config) {
        this((AccountAwareStorageBackend<Organization>) storageFactory.create(
                        "organizations", "org-organizations.json", new TypeReference<Map<String, Organization>>() {}),
                (AccountAwareStorageBackend<OrgAccount>) storageFactory.create(
                        "organizations", "org-accounts.json", new TypeReference<Map<String, OrgAccount>>() {}),
                (AccountAwareStorageBackend<OrgRoot>) storageFactory.create(
                        "organizations", "org-roots.json", new TypeReference<Map<String, OrgRoot>>() {}),
                (AccountAwareStorageBackend<OrganizationalUnit>) storageFactory.create(
                        "organizations", "org-ous.json", new TypeReference<Map<String, OrganizationalUnit>>() {}),
                (AccountAwareStorageBackend<CreateAccountStatus>) storageFactory.create(
                        "organizations", "org-account-statuses.json",
                        new TypeReference<Map<String, CreateAccountStatus>>() {}),
                (AccountAwareStorageBackend<OrgPolicy>) storageFactory.create(
                        "organizations", "org-policies.json", new TypeReference<Map<String, OrgPolicy>>() {}),
                (AccountAwareStorageBackend<Handshake>) storageFactory.create(
                        "organizations", "org-handshakes.json", new TypeReference<Map<String, Handshake>>() {}),
                mapper, iamService,
                config.services().organizations().managementAccountEmail().orElse(null),
                config.services().organizations().scpEnforcementEnabled());
    }

    OrganizationsService(AccountAwareStorageBackend<Organization> organizationStore,
                         AccountAwareStorageBackend<OrgAccount> accountStore,
                         AccountAwareStorageBackend<OrgRoot> rootStore,
                         AccountAwareStorageBackend<OrganizationalUnit> ouStore,
                         AccountAwareStorageBackend<CreateAccountStatus> accountStatusStore,
                         AccountAwareStorageBackend<OrgPolicy> policyStore,
                         AccountAwareStorageBackend<Handshake> handshakeStore,
                         ObjectMapper mapper, IamService iamService,
                         String managementAccountEmailOverride, boolean scpEnforcementEnabled) {
        this.organizationStore = organizationStore;
        this.accountStore = accountStore;
        this.rootStore = rootStore;
        this.ouStore = ouStore;
        this.accountStatusStore = accountStatusStore;
        this.policyStore = policyStore;
        this.handshakeStore = handshakeStore;
        this.mapper = mapper;
        this.iamService = iamService;
        this.managementAccountEmailOverride = managementAccountEmailOverride;
        this.scpEnforcementEnabled = scpEnforcementEnabled;
    }

    /** The caller's organization plus which namespace owns it. */
    record OrgContext(Organization org, String managementAccount, boolean callerIsManagement) {}

    OrgContext resolveOrg(String callerAccount) {
        Optional<Organization> own = organizationStore.getForAccount(callerAccount, ORG_KEY);
        if (own.isPresent()) {
            return new OrgContext(own.get(), callerAccount, true);
        }
        for (OrgAccount account : accountStore.scanAllAccounts()) {
            if (account.getId().equals(callerAccount)) {
                Optional<Organization> org =
                        organizationStore.getForAccount(account.getManagementAccountId(), ORG_KEY);
                if (org.isPresent()) {
                    return new OrgContext(org.get(), account.getManagementAccountId(), false);
                }
            }
        }
        throw new AwsException("AWSOrganizationsNotInUseException",
                "Your account is not a member of an organization.", 400);
    }

    private OrgContext requireManagement(String callerAccount) {
        OrgContext ctx = resolveOrg(callerAccount);
        if (!ctx.callerIsManagement()) {
            throw new AwsException("AccessDeniedException",
                    "You don't have permissions to access this resource. This operation can only be"
                            + " called from the organization's management account.", 400);
        }
        return ctx;
    }

    // ---------------------------------------------------------------- organization lifecycle

    public Organization createOrganization(String callerAccount, String featureSet) {
        if (organizationStore.getForAccount(callerAccount, ORG_KEY).isPresent()) {
            throw new AwsException("AlreadyInOrganizationException",
                    "The provided account is already a member of an organization.", 400);
        }
        for (OrgAccount account : accountStore.scanAllAccounts()) {
            if (account.getId().equals(callerAccount)) {
                throw new AwsException("AlreadyInOrganizationException",
                        "The provided account is already a member of an organization.", 400);
            }
        }
        if (featureSet == null || featureSet.isBlank()) {
            featureSet = "ALL";
        }
        if (!"ALL".equals(featureSet) && !"CONSOLIDATED_BILLING".equals(featureSet)) {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for FeatureSet: " + featureSet, 400);
        }

        String orgId = "o-" + randomId(10);
        Organization org = new Organization();
        org.setId(orgId);
        org.setArn(arn(callerAccount, "organization/" + orgId));
        org.setFeatureSet(featureSet);
        org.setManagementAccountId(callerAccount);
        org.setManagementAccountArn(arn(callerAccount, "account/" + orgId + "/" + callerAccount));
        org.setManagementAccountEmail(defaultEmail(callerAccount));
        if ("ALL".equals(featureSet)) {
            org.getAvailablePolicyTypes().add(new PolicyTypeSummary("SERVICE_CONTROL_POLICY", "ENABLED"));
        }
        organizationStore.putForAccount(callerAccount, ORG_KEY, org);

        String rootId = "r-" + randomId(4);
        OrgRoot root = new OrgRoot();
        root.setId(rootId);
        root.setArn(arn(callerAccount, "root/" + orgId + "/" + rootId));
        root.setName("Root");
        if ("ALL".equals(featureSet)) {
            root.getPolicyTypes().add(new PolicyTypeSummary("SERVICE_CONTROL_POLICY", "ENABLED"));
        }
        rootStore.putForAccount(callerAccount, rootId, root);

        OrgAccount management = new OrgAccount();
        management.setId(callerAccount);
        management.setArn(org.getManagementAccountArn());
        management.setName("Management Account");
        management.setEmail(org.getManagementAccountEmail());
        management.setStatus(STATUS_ACTIVE);
        management.setJoinedMethod("CREATED");
        management.setJoinedTimestamp(now());
        management.setParentId(rootId);
        management.setManagementAccountId(callerAccount);
        accountStore.putForAccount(callerAccount, callerAccount, management);

        if ("ALL".equals(featureSet)) {
            seedFullAwsAccess(callerAccount, List.of(rootId, callerAccount));
        }

        LOG.infov("CreateOrganization: {0} (management account {1})", orgId, callerAccount);
        return org;
    }

    public Organization describeOrganization(String callerAccount) {
        return resolveOrg(callerAccount).org();
    }

    public void deleteOrganization(String callerAccount) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        List<OrgAccount> members = memberAccounts(mgmt);
        if (members.stream().anyMatch(a -> !a.getId().equals(mgmt))) {
            throw new AwsException("OrganizationNotEmptyException",
                    "The organization still contains member accounts.", 400);
        }
        if (!ouStore.scanForAccount(mgmt, key -> true).isEmpty()) {
            throw new AwsException("OrganizationNotEmptyException",
                    "The organization still contains organizational units.", 400);
        }
        if (policies(mgmt).stream().anyMatch(p -> !p.isAwsManaged())) {
            throw new AwsException("OrganizationNotEmptyException",
                    "The organization still contains customer-managed policies.", 400);
        }
        for (String key : accountStore.keysForAccount(mgmt)) {
            accountStore.deleteForAccount(mgmt, key);
        }
        for (String key : rootStore.keysForAccount(mgmt)) {
            rootStore.deleteForAccount(mgmt, key);
        }
        for (String key : accountStatusStore.keysForAccount(mgmt)) {
            accountStatusStore.deleteForAccount(mgmt, key);
        }
        for (String key : policyStore.keysForAccount(mgmt)) {
            policyStore.deleteForAccount(mgmt, key);
        }
        for (String key : handshakeStore.keysForAccount(mgmt)) {
            handshakeStore.deleteForAccount(mgmt, key);
        }
        organizationStore.deleteForAccount(mgmt, ORG_KEY);
        LOG.infov("DeleteOrganization: {0}", ctx.org().getId());
    }

    // ---------------------------------------------------------------- accounts

    public CreateAccountStatus createAccount(String callerAccount, String email, String accountName,
                                             String roleName, Map<String, String> tags, boolean govCloud) {
        OrgContext ctx = requireManagement(callerAccount);
        if (email == null || !email.contains("@")) {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for Email.", 400);
        }
        if (accountName == null || accountName.isBlank()) {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for AccountName.", 400);
        }
        String mgmt = ctx.managementAccount();
        String accountId = mintAccountId(mgmt);

        OrgAccount account = new OrgAccount();
        account.setId(accountId);
        account.setArn(arn(mgmt, "account/" + ctx.org().getId() + "/" + accountId));
        account.setName(accountName);
        account.setEmail(email);
        account.setStatus(STATUS_ACTIVE);
        account.setJoinedMethod("CREATED");
        account.setJoinedTimestamp(now());
        account.setParentId(rootId(mgmt));
        account.setManagementAccountId(mgmt);
        if (tags != null) {
            account.getTags().putAll(tags);
        }
        accountStore.putForAccount(mgmt, accountId, account);
        attachFullAwsAccessIfScpEnabled(mgmt, accountId);
        provisionAccountAccessRole(mgmt, accountId, roleName);

        CreateAccountStatus status = new CreateAccountStatus();
        status.setId("car-" + randomId(24));
        status.setAccountName(accountName);
        status.setAccountId(accountId);
        status.setState("SUCCEEDED");
        status.setRequestedTimestamp(now());
        status.setCompletedTimestamp(now());
        if (govCloud) {
            status.setGovCloudAccountId(mintAccountId(mgmt));
        }
        accountStatusStore.putForAccount(mgmt, status.getId(), status);
        LOG.infov("CreateAccount: {0} ({1}) in {2}", accountId, accountName, ctx.org().getId());
        return status;
    }

    public CreateAccountStatus describeCreateAccountStatus(String callerAccount, String requestId) {
        OrgContext ctx = requireManagement(callerAccount);
        return accountStatusStore.getForAccount(ctx.managementAccount(), requestId)
                .orElseThrow(() -> new AwsException("CreateAccountStatusNotFoundException",
                        "We can't find a create account request with the CreateAccountRequestId that"
                                + " you specified: " + requestId, 400));
    }

    public List<CreateAccountStatus> listCreateAccountStatus(String callerAccount, List<String> states) {
        OrgContext ctx = requireManagement(callerAccount);
        return accountStatusStore.scanForAccount(ctx.managementAccount(), key -> true).stream()
                .filter(s -> states == null || states.isEmpty() || states.contains(s.getState()))
                .sorted(Comparator.comparing(CreateAccountStatus::getId))
                .toList();
    }

    public OrgAccount describeAccount(String callerAccount, String accountId) {
        OrgContext ctx = resolveOrg(callerAccount);
        return requireAccount(ctx.managementAccount(), accountId);
    }

    public List<OrgAccount> listAccounts(String callerAccount) {
        OrgContext ctx = resolveOrg(callerAccount);
        return memberAccounts(ctx.managementAccount());
    }

    public List<OrgAccount> listAccountsForParent(String callerAccount, String parentId) {
        OrgContext ctx = resolveOrg(callerAccount);
        requireParent(ctx.managementAccount(), parentId, "ParentNotFoundException");
        return memberAccounts(ctx.managementAccount()).stream()
                .filter(a -> parentId.equals(a.getParentId()))
                .toList();
    }

    public void closeAccount(String callerAccount, String accountId) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        OrgAccount account = requireAccount(mgmt, accountId);
        if (accountId.equals(mgmt)) {
            throw new AwsException("ConstraintViolationException",
                    "You can't close the organization's management account.", 400);
        }
        if (STATUS_SUSPENDED.equals(account.getStatus())) {
            throw new AwsException("AccountAlreadyClosedException",
                    "The account is already closed.", 400);
        }
        account.setStatus(STATUS_SUSPENDED);
        accountStore.putForAccount(mgmt, accountId, account);
    }

    public void removeAccountFromOrganization(String callerAccount, String accountId) {
        OrgContext ctx = requireManagement(callerAccount);
        removeMember(ctx, accountId);
    }

    public void leaveOrganization(String callerAccount) {
        OrgContext ctx = resolveOrg(callerAccount);
        removeMember(ctx, callerAccount);
    }

    private void removeMember(OrgContext ctx, String accountId) {
        String mgmt = ctx.managementAccount();
        if (accountId.equals(mgmt)) {
            throw new AwsException("MasterCannotLeaveOrganizationException",
                    "You can't remove the management account from the organization.", 400);
        }
        requireAccount(mgmt, accountId);
        accountStore.deleteForAccount(mgmt, accountId);
        detachFromAllPolicies(mgmt, accountId);
        LOG.infov("Account {0} removed from organization {1}", accountId, ctx.org().getId());
    }

    public void moveAccount(String callerAccount, String accountId,
                            String sourceParentId, String destinationParentId) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        OrgAccount account = requireAccount(mgmt, accountId);
        requireParent(mgmt, sourceParentId, "SourceParentNotFoundException");
        requireParent(mgmt, destinationParentId, "DestinationParentNotFoundException");
        if (destinationParentId.equals(account.getParentId())) {
            throw new AwsException("DuplicateAccountException",
                    "The account is already present in the destination parent.", 400);
        }
        if (!sourceParentId.equals(account.getParentId())) {
            throw new AwsException("AccountNotFoundException",
                    "We can't find the account in the source parent that you specified.", 400);
        }
        account.setParentId(destinationParentId);
        accountStore.putForAccount(mgmt, accountId, account);
    }

    /** A parent or child reference: an account, OU, or root ID plus its type. */
    record NodeRef(String id, String type) {}

    public List<NodeRef> listParents(String callerAccount, String childId) {
        OrgContext ctx = resolveOrg(callerAccount);
        String mgmt = ctx.managementAccount();
        String parentId;
        if (childId.startsWith("ou-")) {
            parentId = requireOu(mgmt, childId).getParentId();
        } else if (isAccountId(childId)) {
            parentId = requireAccount(mgmt, childId).getParentId();
        } else {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for ChildId: " + childId, 400);
        }
        return List.of(new NodeRef(parentId, parentType(parentId)));
    }

    public List<NodeRef> listChildren(String callerAccount, String parentId, String childType) {
        OrgContext ctx = resolveOrg(callerAccount);
        String mgmt = ctx.managementAccount();
        requireParent(mgmt, parentId, "ParentNotFoundException");
        if (!"ACCOUNT".equals(childType) && !"ORGANIZATIONAL_UNIT".equals(childType)) {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for ChildType: " + childType, 400);
        }
        List<NodeRef> children = new ArrayList<>();
        if ("ACCOUNT".equals(childType)) {
            memberAccounts(mgmt).stream()
                    .filter(a -> parentId.equals(a.getParentId()))
                    .forEach(a -> children.add(new NodeRef(a.getId(), "ACCOUNT")));
        } else {
            organizationalUnits(mgmt).stream()
                    .filter(ou -> parentId.equals(ou.getParentId()))
                    .forEach(ou -> children.add(new NodeRef(ou.getId(), "ORGANIZATIONAL_UNIT")));
        }
        return children;
    }

    // ---------------------------------------------------------------- roots and OUs

    public List<OrgRoot> listRoots(String callerAccount) {
        OrgContext ctx = resolveOrg(callerAccount);
        return rootStore.scanForAccount(ctx.managementAccount(), key -> true).stream()
                .sorted(Comparator.comparing(OrgRoot::getId))
                .toList();
    }

    public OrganizationalUnit createOrganizationalUnit(String callerAccount, String parentId,
                                                       String name, Map<String, String> tags) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for Name.", 400);
        }
        requireParent(mgmt, parentId, "ParentNotFoundException");
        boolean duplicate = organizationalUnits(mgmt).stream()
                .anyMatch(ou -> parentId.equals(ou.getParentId()) && name.equals(ou.getName()));
        if (duplicate) {
            throw new AwsException("DuplicateOrganizationalUnitException",
                    "An OU with the same name already exists in the parent.", 400);
        }
        String ouId = "ou-" + rootId(mgmt).substring(2) + "-" + randomId(8);
        OrganizationalUnit ou = new OrganizationalUnit();
        ou.setId(ouId);
        ou.setArn(arn(mgmt, "ou/" + ctx.org().getId() + "/" + ouId));
        ou.setName(name);
        ou.setParentId(parentId);
        if (tags != null) {
            ou.getTags().putAll(tags);
        }
        ouStore.putForAccount(mgmt, ouId, ou);
        attachFullAwsAccessIfScpEnabled(mgmt, ouId);
        LOG.infov("CreateOrganizationalUnit: {0} ({1}) under {2}", ouId, name, parentId);
        return ou;
    }

    public OrganizationalUnit describeOrganizationalUnit(String callerAccount, String ouId) {
        OrgContext ctx = resolveOrg(callerAccount);
        return requireOu(ctx.managementAccount(), ouId);
    }

    public OrganizationalUnit updateOrganizationalUnit(String callerAccount, String ouId, String name) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        OrganizationalUnit ou = requireOu(mgmt, ouId);
        if (name != null && !name.isBlank()) {
            boolean duplicate = organizationalUnits(mgmt).stream()
                    .anyMatch(other -> !other.getId().equals(ouId)
                            && ou.getParentId().equals(other.getParentId())
                            && name.equals(other.getName()));
            if (duplicate) {
                throw new AwsException("DuplicateOrganizationalUnitException",
                        "An OU with the same name already exists in the parent.", 400);
            }
            ou.setName(name);
            ouStore.putForAccount(mgmt, ouId, ou);
        }
        return ou;
    }

    public void deleteOrganizationalUnit(String callerAccount, String ouId) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        requireOu(mgmt, ouId);
        boolean hasChildren = organizationalUnits(mgmt).stream()
                .anyMatch(ou -> ouId.equals(ou.getParentId()))
                || memberAccounts(mgmt).stream().anyMatch(a -> ouId.equals(a.getParentId()));
        if (hasChildren) {
            throw new AwsException("OrganizationalUnitNotEmptyException",
                    "The OU still contains accounts or child OUs.", 400);
        }
        ouStore.deleteForAccount(mgmt, ouId);
        detachFromAllPolicies(mgmt, ouId);
    }

    public List<OrganizationalUnit> listOrganizationalUnitsForParent(String callerAccount, String parentId) {
        OrgContext ctx = resolveOrg(callerAccount);
        requireParent(ctx.managementAccount(), parentId, "ParentNotFoundException");
        return organizationalUnits(ctx.managementAccount()).stream()
                .filter(ou -> parentId.equals(ou.getParentId()))
                .toList();
    }

    // ---------------------------------------------------------------- tagging

    public Map<String, String> tagsForResource(String callerAccount, String resourceId) {
        OrgContext ctx = resolveOrg(callerAccount);
        return taggableResourceTags(ctx.managementAccount(), resourceId);
    }

    public void tagResource(String callerAccount, String resourceId, Map<String, String> tags) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        Map<String, String> existing = taggableResourceTags(mgmt, resourceId);
        existing.putAll(tags);
        if (existing.size() > 50) {
            throw new AwsException("ConstraintViolationException",
                    "A resource can have at most 50 tags.", 400);
        }
        persistTaggable(mgmt, resourceId);
    }

    public void untagResource(String callerAccount, String resourceId, List<String> tagKeys) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        Map<String, String> existing = taggableResourceTags(mgmt, resourceId);
        tagKeys.forEach(existing::remove);
        persistTaggable(mgmt, resourceId);
    }

    /**
     * Live tag map of a taggable resource (root, OU, or account), by ID prefix.
     * Mutations must be followed by {@link #persistTaggable}.
     */
    private Map<String, String> taggableResourceTags(String mgmt, String resourceId) {
        if (resourceId.startsWith("r-")) {
            return requireRoot(mgmt, resourceId).getTags();
        }
        if (resourceId.startsWith("ou-")) {
            return requireOu(mgmt, resourceId).getTags();
        }
        if (resourceId.startsWith("p-")) {
            return requirePolicy(mgmt, resourceId).getTags();
        }
        if (isAccountId(resourceId)) {
            return requireAccount(mgmt, resourceId).getTags();
        }
        throw new AwsException("InvalidInputException",
                "You provided an invalid value for ResourceId: " + resourceId, 400);
    }

    private void persistTaggable(String mgmt, String resourceId) {
        if (resourceId.startsWith("r-")) {
            rootStore.putForAccount(mgmt, resourceId, requireRoot(mgmt, resourceId));
        } else if (resourceId.startsWith("ou-")) {
            ouStore.putForAccount(mgmt, resourceId, requireOu(mgmt, resourceId));
        } else if (resourceId.startsWith("p-")) {
            policyStore.putForAccount(mgmt, resourceId, requirePolicy(mgmt, resourceId));
        } else {
            accountStore.putForAccount(mgmt, resourceId, requireAccount(mgmt, resourceId));
        }
    }

    // ---------------------------------------------------------------- policies

    public OrgPolicy createPolicy(String callerAccount, String name, String description,
                                  String type, String content, Map<String, String> tags) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        requireValidPolicyType(type);
        if (SCP_TYPE.equals(type) && !"ALL".equals(ctx.org().getFeatureSet())) {
            throw new AwsException("PolicyTypeNotAvailableForOrganizationException",
                    "Service control policies require the ALL feature set.", 400);
        }
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for Name.", 400);
        }
        if (policies(mgmt).stream().anyMatch(p -> type.equals(p.getType()) && name.equals(p.getName()))) {
            throw new AwsException("DuplicatePolicyException",
                    "A policy with the same name and type already exists.", 400);
        }
        validatePolicyContent(content);

        OrgPolicy policy = new OrgPolicy();
        policy.setId("p-" + randomId(8));
        policy.setArn(policyArn(mgmt, ctx.org().getId(), type, policy.getId(), false));
        policy.setName(name);
        policy.setDescription(description == null ? "" : description);
        policy.setType(type);
        policy.setContent(content);
        policy.setAwsManaged(false);
        policy.setCreated(now());
        policy.setUpdated(policy.getCreated());
        if (tags != null) {
            policy.getTags().putAll(tags);
        }
        policyStore.putForAccount(mgmt, policy.getId(), policy);
        LOG.infov("CreatePolicy: {0} ({1}, {2})", policy.getId(), name, type);
        return policy;
    }

    public OrgPolicy describePolicy(String callerAccount, String policyId) {
        OrgContext ctx = resolveOrg(callerAccount);
        return requirePolicy(ctx.managementAccount(), policyId);
    }

    public OrgPolicy updatePolicy(String callerAccount, String policyId, String name,
                                  String description, String content) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        OrgPolicy policy = requirePolicy(mgmt, policyId);
        if (policy.isAwsManaged()) {
            throw new AwsException("ConstraintViolationException",
                    "You can't update an AWS managed policy.", 400);
        }
        if (name != null && !name.isBlank() && !name.equals(policy.getName())) {
            if (policies(mgmt).stream().anyMatch(p -> !p.getId().equals(policyId)
                    && policy.getType().equals(p.getType()) && name.equals(p.getName()))) {
                throw new AwsException("DuplicatePolicyException",
                        "A policy with the same name and type already exists.", 400);
            }
            policy.setName(name);
        }
        if (description != null) {
            policy.setDescription(description);
        }
        if (content != null) {
            validatePolicyContent(content);
            policy.setContent(content);
        }
        policy.setUpdated(now());
        policyStore.putForAccount(mgmt, policyId, policy);
        return policy;
    }

    public void deletePolicy(String callerAccount, String policyId) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        OrgPolicy policy = requirePolicy(mgmt, policyId);
        if (policy.isAwsManaged()) {
            throw new AwsException("ConstraintViolationException",
                    "You can't delete an AWS managed policy.", 400);
        }
        if (!policy.getTargetIds().isEmpty()) {
            throw new AwsException("PolicyInUseException",
                    "The policy is attached to one or more targets and can't be deleted.", 400);
        }
        policyStore.deleteForAccount(mgmt, policyId);
    }

    public List<OrgPolicy> listPolicies(String callerAccount, String filter) {
        OrgContext ctx = resolveOrg(callerAccount);
        requireValidPolicyType(filter);
        return policies(ctx.managementAccount()).stream()
                .filter(p -> filter.equals(p.getType()))
                .toList();
    }

    public void attachPolicy(String callerAccount, String policyId, String targetId) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        OrgPolicy policy = requirePolicy(mgmt, policyId);
        requireTarget(mgmt, targetId);
        requirePolicyTypeEnabled(mgmt, policy.getType());
        if (policy.getTargetIds().contains(targetId)) {
            throw new AwsException("DuplicatePolicyAttachmentException",
                    "The policy is already attached to the target.", 400);
        }
        policy.getTargetIds().add(targetId);
        policyStore.putForAccount(mgmt, policyId, policy);
    }

    public void detachPolicy(String callerAccount, String policyId, String targetId) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        OrgPolicy policy = requirePolicy(mgmt, policyId);
        requireTarget(mgmt, targetId);
        if (!policy.getTargetIds().contains(targetId)) {
            throw new AwsException("PolicyNotAttachedException",
                    "The policy is not attached to the target.", 400);
        }
        if (SCP_TYPE.equals(policy.getType())
                && policiesForTarget(mgmt, targetId, SCP_TYPE).size() == 1) {
            throw new AwsException("ConstraintViolationException",
                    "You can't detach the last service control policy from a target.", 400);
        }
        policy.getTargetIds().remove(targetId);
        policyStore.putForAccount(mgmt, policyId, policy);
    }

    public List<OrgPolicy> listPoliciesForTarget(String callerAccount, String targetId, String filter) {
        OrgContext ctx = resolveOrg(callerAccount);
        String mgmt = ctx.managementAccount();
        requireTarget(mgmt, targetId);
        requireValidPolicyType(filter);
        return policiesForTarget(mgmt, targetId, filter);
    }

    /** An attachment target of a policy: root, OU, or account. */
    record TargetRef(String targetId, String arn, String name, String type) {}

    public List<TargetRef> listTargetsForPolicy(String callerAccount, String policyId) {
        OrgContext ctx = resolveOrg(callerAccount);
        String mgmt = ctx.managementAccount();
        OrgPolicy policy = requirePolicy(mgmt, policyId);
        List<TargetRef> targets = new ArrayList<>();
        for (String targetId : policy.getTargetIds()) {
            if (targetId.startsWith("r-")) {
                rootStore.getForAccount(mgmt, targetId).ifPresent(root ->
                        targets.add(new TargetRef(targetId, root.getArn(), root.getName(), "ROOT")));
            } else if (targetId.startsWith("ou-")) {
                ouStore.getForAccount(mgmt, targetId).ifPresent(ou ->
                        targets.add(new TargetRef(targetId, ou.getArn(), ou.getName(), "ORGANIZATIONAL_UNIT")));
            } else {
                accountStore.getForAccount(mgmt, targetId).ifPresent(account ->
                        targets.add(new TargetRef(targetId, account.getArn(), account.getName(), "ACCOUNT")));
            }
        }
        return targets;
    }

    public OrgRoot enablePolicyType(String callerAccount, String rootId, String type) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        OrgRoot root = requireRoot(mgmt, rootId);
        requireValidPolicyType(type);
        if (root.getPolicyTypes().stream().anyMatch(t -> type.equals(t.getType()))) {
            throw new AwsException("PolicyTypeAlreadyEnabledException",
                    "The policy type is already enabled on the root.", 400);
        }
        root.getPolicyTypes().add(new PolicyTypeSummary(type, "ENABLED"));
        rootStore.putForAccount(mgmt, rootId, root);

        Organization org = ctx.org();
        if (org.getAvailablePolicyTypes().stream().noneMatch(t -> type.equals(t.getType()))) {
            org.getAvailablePolicyTypes().add(new PolicyTypeSummary(type, "ENABLED"));
            organizationStore.putForAccount(mgmt, ORG_KEY, org);
        }

        if (SCP_TYPE.equals(type)) {
            List<String> nodes = new ArrayList<>();
            nodes.add(rootId);
            organizationalUnits(mgmt).forEach(ou -> nodes.add(ou.getId()));
            memberAccounts(mgmt).forEach(a -> nodes.add(a.getId()));
            seedFullAwsAccess(mgmt, nodes.stream()
                    .filter(node -> policiesForTarget(mgmt, node, SCP_TYPE).isEmpty())
                    .toList());
        }
        return root;
    }

    public OrgRoot disablePolicyType(String callerAccount, String rootId, String type) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        OrgRoot root = requireRoot(mgmt, rootId);
        requireValidPolicyType(type);
        if (root.getPolicyTypes().stream().noneMatch(t -> type.equals(t.getType()))) {
            throw new AwsException("PolicyTypeNotEnabledException",
                    "The policy type is not enabled on the root.", 400);
        }
        root.getPolicyTypes().removeIf(t -> type.equals(t.getType()));
        rootStore.putForAccount(mgmt, rootId, root);

        Organization org = ctx.org();
        org.getAvailablePolicyTypes().removeIf(t -> type.equals(t.getType()));
        organizationStore.putForAccount(mgmt, ORG_KEY, org);

        // Disabling a policy type detaches every policy of that type, matching AWS.
        for (OrgPolicy policy : policies(mgmt)) {
            if (type.equals(policy.getType()) && !policy.getTargetIds().isEmpty()) {
                policy.getTargetIds().clear();
                policyStore.putForAccount(mgmt, policy.getId(), policy);
            }
        }
        return root;
    }

    /** The merged effective policy for the non-SCP policy types. */
    record EffectivePolicy(String policyContent, double lastUpdatedTimestamp,
                           String targetId, String policyType) {}

    public EffectivePolicy describeEffectivePolicy(String callerAccount, String policyType,
                                                   String targetId) {
        OrgContext ctx = resolveOrg(callerAccount);
        String mgmt = ctx.managementAccount();
        requireValidPolicyType(policyType);
        if (SCP_TYPE.equals(policyType)) {
            throw new AwsException("InvalidInputException",
                    "DescribeEffectivePolicy does not apply to service control policies.", 400);
        }
        String target = targetId == null ? callerAccount : targetId;
        requireAccount(mgmt, target);

        JsonNode merged = null;
        double lastUpdated = 0;
        for (String node : chainToTarget(mgmt, target)) {
            for (OrgPolicy policy : policiesForTarget(mgmt, node, policyType)) {
                merged = mergeEffective(merged, parsePolicyContent(policy.getContent()));
                lastUpdated = Math.max(lastUpdated, policy.getUpdated());
            }
        }
        if (merged == null) {
            throw new AwsException("EffectivePolicyNotFoundException",
                    "There is no effective policy of the specified type for the target.", 400);
        }
        return new EffectivePolicy(merged.toString(), lastUpdated, target, policyType);
    }

    /** Root-first chain of node IDs ending at the target account. */
    List<String> chainToTarget(String mgmt, String accountId) {
        List<String> chain = new ArrayList<>();
        chain.add(accountId);
        String parent = requireAccount(mgmt, accountId).getParentId();
        while (parent != null && parent.startsWith("ou-")) {
            chain.add(parent);
            parent = requireOu(mgmt, parent).getParentId();
        }
        if (parent != null) {
            chain.add(parent);
        }
        java.util.Collections.reverse(chain);
        return chain;
    }

    /**
     * Merges one policy document over the inherited base, honoring the child-policy
     * inheritance operators: {@code @@assign} replaces the inherited value, {@code @@append}
     * concatenates onto the inherited list, and {@code @@remove} filters values out. Bare
     * scalar and array values behave like {@code @@assign}; bare objects merge recursively.
     * Other {@code @@}-prefixed control keys are dropped from the output.
     */
    private JsonNode mergeEffective(JsonNode base, JsonNode overlay) {
        if (!(overlay instanceof ObjectNode overlayNode)) {
            return overlay == null ? base : overlay.deepCopy();
        }
        if (overlayNode.has("@@assign")) {
            return overlayNode.get("@@assign").deepCopy();
        }
        if (overlayNode.has("@@append")) {
            ArrayNode result = mapper.createArrayNode();
            if (base != null && base.isArray()) {
                base.forEach(result::add);
            }
            JsonNode values = overlayNode.get("@@append");
            if (values.isArray()) {
                values.forEach(result::add);
            } else {
                result.add(values);
            }
            return result;
        }
        if (overlayNode.has("@@remove")) {
            ArrayNode result = mapper.createArrayNode();
            JsonNode values = overlayNode.get("@@remove");
            if (base != null && base.isArray()) {
                base.forEach(entry -> {
                    boolean removed = false;
                    if (values.isArray()) {
                        for (JsonNode value : values) {
                            if (value.equals(entry)) {
                                removed = true;
                                break;
                            }
                        }
                    } else {
                        removed = values.equals(entry);
                    }
                    if (!removed) {
                        result.add(entry);
                    }
                });
            }
            return result;
        }
        ObjectNode result = base instanceof ObjectNode baseNode
                ? baseNode.deepCopy() : mapper.createObjectNode();
        overlayNode.fields().forEachRemaining(entry -> {
            if (entry.getKey().startsWith("@@")) {
                return;
            }
            result.set(entry.getKey(), mergeEffective(result.get(entry.getKey()), entry.getValue()));
        });
        return result;
    }

    private void seedFullAwsAccess(String mgmt, List<String> targetIds) {
        OrgPolicy policy = policyStore.getForAccount(mgmt, FULL_AWS_ACCESS_ID).orElseGet(() -> {
            OrgPolicy seeded = new OrgPolicy();
            seeded.setId(FULL_AWS_ACCESS_ID);
            seeded.setArn(policyArn(mgmt, null, SCP_TYPE, FULL_AWS_ACCESS_ID, true));
            seeded.setName("FullAWSAccess");
            seeded.setDescription("Allows access to every operation");
            seeded.setType(SCP_TYPE);
            seeded.setContent(FULL_AWS_ACCESS_CONTENT);
            seeded.setAwsManaged(true);
            seeded.setCreated(now());
            seeded.setUpdated(seeded.getCreated());
            return seeded;
        });
        for (String targetId : targetIds) {
            if (!policy.getTargetIds().contains(targetId)) {
                policy.getTargetIds().add(targetId);
            }
        }
        policyStore.putForAccount(mgmt, FULL_AWS_ACCESS_ID, policy);
    }

    private void attachFullAwsAccessIfScpEnabled(String mgmt, String targetId) {
        boolean scpEnabled = rootStore.scanForAccount(mgmt, key -> true).stream()
                .flatMap(root -> root.getPolicyTypes().stream())
                .anyMatch(t -> SCP_TYPE.equals(t.getType()) && "ENABLED".equals(t.getStatus()));
        if (scpEnabled) {
            seedFullAwsAccess(mgmt, List.of(targetId));
        }
    }

    private void detachFromAllPolicies(String mgmt, String targetId) {
        for (OrgPolicy policy : policies(mgmt)) {
            if (policy.getTargetIds().remove(targetId)) {
                policyStore.putForAccount(mgmt, policy.getId(), policy);
            }
        }
    }

    List<OrgPolicy> policies(String mgmt) {
        return policyStore.scanForAccount(mgmt, key -> true).stream()
                .sorted(Comparator.comparing(OrgPolicy::getId))
                .toList();
    }

    List<OrgPolicy> policiesForTarget(String mgmt, String targetId, String type) {
        return policies(mgmt).stream()
                .filter(p -> type.equals(p.getType()) && p.getTargetIds().contains(targetId))
                .toList();
    }

    OrgPolicy requirePolicy(String mgmt, String policyId) {
        return policyStore.getForAccount(mgmt, policyId)
                .orElseThrow(() -> new AwsException("PolicyNotFoundException",
                        "We can't find a policy with the PolicyId that you specified: " + policyId, 400));
    }

    private void requireTarget(String mgmt, String targetId) {
        boolean exists;
        if (targetId != null && targetId.startsWith("r-")) {
            exists = rootStore.getForAccount(mgmt, targetId).isPresent();
        } else if (targetId != null && targetId.startsWith("ou-")) {
            exists = ouStore.getForAccount(mgmt, targetId).isPresent();
        } else if (isAccountId(targetId)) {
            exists = accountStore.getForAccount(mgmt, targetId).isPresent();
        } else {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for TargetId: " + targetId, 400);
        }
        if (!exists) {
            throw new AwsException("TargetNotFoundException",
                    "We can't find a root, OU, or account with the TargetId that you specified: "
                            + targetId, 400);
        }
    }

    private void requirePolicyTypeEnabled(String mgmt, String type) {
        boolean enabled = rootStore.scanForAccount(mgmt, key -> true).stream()
                .flatMap(root -> root.getPolicyTypes().stream())
                .anyMatch(t -> type.equals(t.getType()) && "ENABLED".equals(t.getStatus()));
        if (!enabled) {
            throw new AwsException("PolicyTypeNotEnabledException",
                    "The policy type " + type + " is not enabled on the root.", 400);
        }
    }

    private static void requireValidPolicyType(String type) {
        if (type == null || !POLICY_TYPES.contains(type)) {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for policy type: " + type, 400);
        }
    }

    private JsonNode parsePolicyContent(String content) {
        try {
            return mapper.readTree(content);
        } catch (Exception e) {
            throw new AwsException("MalformedPolicyDocumentException",
                    "The policy content is not valid JSON.", 400);
        }
    }

    private void validatePolicyContent(String content) {
        if (content == null || content.isBlank()) {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for Content.", 400);
        }
        JsonNode parsed = parsePolicyContent(content);
        if (!parsed.isObject()) {
            throw new AwsException("MalformedPolicyDocumentException",
                    "The policy content must be a JSON object.", 400);
        }
    }

    /** AWS managed policies carry the literal {@code aws} account and no org ID path segment. */
    private static String policyArn(String mgmt, String orgId, String type, String policyId,
                                    boolean awsManaged) {
        String typeSegment = type.toLowerCase(java.util.Locale.ROOT);
        if (awsManaged) {
            return "arn:aws:organizations::aws:policy/" + typeSegment + "/" + policyId;
        }
        return "arn:aws:organizations::" + mgmt + ":policy/" + orgId + "/" + typeSegment + "/" + policyId;
    }

    // ---------------------------------------------------------------- handshakes

    public Handshake inviteAccountToOrganization(String callerAccount, String targetId,
                                                 String targetType, String notes) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        if ("EMAIL".equals(targetType)) {
            throw new AwsException("InvalidInputException",
                    "EMAIL targets are not supported by the emulator; invite by ACCOUNT ID.", 400);
        }
        if (!isAccountId(targetId)) {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for Target.Id: " + targetId, 400);
        }
        if (accountStore.getForAccount(mgmt, targetId).isPresent()) {
            throw new AwsException("ConstraintViolationException",
                    "The account is already a member of the organization.", 400);
        }
        boolean openInvite = handshakes(mgmt).stream()
                .anyMatch(h -> "INVITE".equals(h.getAction())
                        && targetId.equals(h.getTargetAccountId())
                        && "OPEN".equals(h.effectiveState(now())));
        if (openInvite) {
            throw new AwsException("DuplicateHandshakeException",
                    "An open invitation for the account already exists.", 400);
        }
        Handshake handshake = newHandshake(ctx, "INVITE", targetId, null);
        handshake.setNotes(notes);
        handshakeStore.putForAccount(mgmt, handshake.getId(), handshake);
        LOG.infov("InviteAccountToOrganization: {0} invited to {1}", targetId, ctx.org().getId());
        return handshake;
    }

    public Handshake acceptHandshake(String callerAccount, String handshakeId) {
        HandshakeRef ref = requireHandshakeAnywhere(handshakeId);
        Handshake handshake = ref.handshake();
        String mgmt = ref.managementAccount();
        requireOpen(handshake);
        if (!callerAccount.equals(handshake.getTargetAccountId())) {
            throw new AwsException("AccessDeniedException",
                    "Only the invited account can accept the handshake.", 400);
        }
        switch (handshake.getAction()) {
            case "INVITE" -> acceptInvite(mgmt, handshake, callerAccount);
            case "APPROVE_ALL_FEATURES" -> acceptAllFeaturesApproval(mgmt, handshake);
            default -> throw new AwsException("InvalidHandshakeTransitionException",
                    "The handshake can't be accepted directly.", 400);
        }
        handshake.setState("ACCEPTED");
        handshakeStore.putForAccount(mgmt, handshake.getId(), handshake);
        return handshake;
    }

    private void acceptInvite(String mgmt, Handshake handshake, String callerAccount) {
        if (organizationStore.getForAccount(callerAccount, ORG_KEY).isPresent()) {
            throw new AwsException("HandshakeConstraintViolationException",
                    "The invited account is already the management account of another organization.", 400);
        }
        for (OrgAccount member : accountStore.scanAllAccounts()) {
            if (member.getId().equals(callerAccount)) {
                throw new AwsException("HandshakeConstraintViolationException",
                        "The invited account is already a member of an organization.", 400);
            }
        }
        Organization org = organizationStore.getForAccount(mgmt, ORG_KEY)
                .orElseThrow(() -> new AwsException("AWSOrganizationsNotInUseException",
                        "The inviting organization no longer exists.", 400));
        OrgAccount account = new OrgAccount();
        account.setId(callerAccount);
        account.setArn(arn(mgmt, "account/" + org.getId() + "/" + callerAccount));
        account.setName("Account " + callerAccount);
        account.setEmail("account+" + callerAccount + "@example.com");
        account.setStatus(STATUS_ACTIVE);
        account.setJoinedMethod("INVITED");
        account.setJoinedTimestamp(now());
        account.setParentId(rootId(mgmt));
        account.setManagementAccountId(mgmt);
        accountStore.putForAccount(mgmt, callerAccount, account);
        attachFullAwsAccessIfScpEnabled(mgmt, callerAccount);
    }

    private void acceptAllFeaturesApproval(String mgmt, Handshake approval) {
        boolean allOthersAccepted = handshakes(mgmt).stream()
                .filter(h -> "APPROVE_ALL_FEATURES".equals(h.getAction())
                        && approval.getParentHandshakeId() != null
                        && approval.getParentHandshakeId().equals(h.getParentHandshakeId())
                        && !h.getId().equals(approval.getId()))
                .allMatch(h -> "ACCEPTED".equals(h.getState()));
        if (allOthersAccepted) {
            finalizeAllFeatures(mgmt, approval.getParentHandshakeId());
        }
    }

    private void finalizeAllFeatures(String mgmt, String parentHandshakeId) {
        Organization org = organizationStore.getForAccount(mgmt, ORG_KEY).orElseThrow();
        org.setFeatureSet("ALL");
        if (org.getAvailablePolicyTypes().stream().noneMatch(t -> SCP_TYPE.equals(t.getType()))) {
            org.getAvailablePolicyTypes().add(new PolicyTypeSummary(SCP_TYPE, "ENABLED"));
        }
        organizationStore.putForAccount(mgmt, ORG_KEY, org);

        String rootId = rootId(mgmt);
        OrgRoot root = requireRoot(mgmt, rootId);
        if (root.getPolicyTypes().stream().noneMatch(t -> SCP_TYPE.equals(t.getType()))) {
            root.getPolicyTypes().add(new PolicyTypeSummary(SCP_TYPE, "ENABLED"));
            rootStore.putForAccount(mgmt, rootId, root);
        }
        List<String> nodes = new ArrayList<>();
        nodes.add(rootId);
        organizationalUnits(mgmt).forEach(ou -> nodes.add(ou.getId()));
        memberAccounts(mgmt).forEach(a -> nodes.add(a.getId()));
        seedFullAwsAccess(mgmt, nodes.stream()
                .filter(node -> policiesForTarget(mgmt, node, SCP_TYPE).isEmpty())
                .toList());

        if (parentHandshakeId != null) {
            handshakeStore.getForAccount(mgmt, parentHandshakeId).ifPresent(parent -> {
                parent.setState("ACCEPTED");
                handshakeStore.putForAccount(mgmt, parentHandshakeId, parent);
            });
        }
        LOG.infov("EnableAllFeatures finalized for organization {0}", org.getId());
    }

    public Handshake declineHandshake(String callerAccount, String handshakeId) {
        HandshakeRef ref = requireHandshakeAnywhere(handshakeId);
        Handshake handshake = ref.handshake();
        requireOpen(handshake);
        if (!callerAccount.equals(handshake.getTargetAccountId())) {
            throw new AwsException("AccessDeniedException",
                    "Only the recipient account can decline the handshake.", 400);
        }
        handshake.setState("DECLINED");
        handshakeStore.putForAccount(ref.managementAccount(), handshakeId, handshake);
        return handshake;
    }

    public Handshake cancelHandshake(String callerAccount, String handshakeId) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        Handshake handshake = handshakeStore.getForAccount(mgmt, handshakeId)
                .orElseThrow(OrganizationsService::handshakeNotFound);
        requireOpen(handshake);
        handshake.setState("CANCELED");
        handshakeStore.putForAccount(mgmt, handshakeId, handshake);
        return handshake;
    }

    public Handshake describeHandshake(String callerAccount, String handshakeId) {
        HandshakeRef ref = requireHandshakeAnywhere(handshakeId);
        Handshake handshake = ref.handshake();
        if (!callerAccount.equals(handshake.getTargetAccountId())
                && !callerAccount.equals(ref.managementAccount())) {
            throw new AwsException("AccessDeniedException",
                    "You are not a party to the handshake.", 400);
        }
        return handshake;
    }

    public List<Handshake> listHandshakesForAccount(String callerAccount, String actionTypeFilter) {
        return handshakeStore.scanAllAccounts().stream()
                .filter(h -> callerAccount.equals(h.getTargetAccountId())
                        || callerAccount.equals(h.getManagementAccountId()))
                .filter(h -> actionTypeFilter == null || actionTypeFilter.equals(h.getAction()))
                .sorted(Comparator.comparing(Handshake::getId))
                .toList();
    }

    public List<Handshake> listHandshakesForOrganization(String callerAccount, String actionTypeFilter) {
        OrgContext ctx = requireManagement(callerAccount);
        return handshakes(ctx.managementAccount()).stream()
                .filter(h -> actionTypeFilter == null || actionTypeFilter.equals(h.getAction()))
                .toList();
    }

    public Handshake enableAllFeatures(String callerAccount) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        if ("ALL".equals(ctx.org().getFeatureSet())) {
            throw new AwsException("HandshakeConstraintViolationException",
                    "The organization already has all features enabled.", 400);
        }
        Handshake parent = newHandshake(ctx, "ENABLE_ALL_FEATURES", mgmt, null);
        List<OrgAccount> members = memberAccounts(mgmt).stream()
                .filter(a -> !a.getId().equals(mgmt))
                .toList();
        if (members.isEmpty()) {
            parent.setState("ACCEPTED");
            handshakeStore.putForAccount(mgmt, parent.getId(), parent);
            finalizeAllFeatures(mgmt, null);
            return parent;
        }
        handshakeStore.putForAccount(mgmt, parent.getId(), parent);
        for (OrgAccount member : members) {
            Handshake approval = newHandshake(ctx, "APPROVE_ALL_FEATURES", member.getId(), parent.getId());
            handshakeStore.putForAccount(mgmt, approval.getId(), approval);
        }
        return parent;
    }

    private Handshake newHandshake(OrgContext ctx, String action, String targetAccountId,
                                   String parentHandshakeId) {
        String mgmt = ctx.managementAccount();
        Handshake handshake = new Handshake();
        handshake.setId("h-" + randomId(8));
        handshake.setArn(arn(mgmt, "handshake/" + ctx.org().getId() + "/"
                + action.toLowerCase(java.util.Locale.ROOT) + "/" + handshake.getId()));
        handshake.setState("OPEN");
        handshake.setAction(action);
        handshake.setRequestedTimestamp(now());
        handshake.setExpirationTimestamp(now() + HANDSHAKE_TTL_SECONDS);
        handshake.setTargetAccountId(targetAccountId);
        handshake.setManagementAccountId(mgmt);
        handshake.setOrgId(ctx.org().getId());
        handshake.setParentHandshakeId(parentHandshakeId);
        return handshake;
    }

    private record HandshakeRef(Handshake handshake, String managementAccount) {}

    private HandshakeRef requireHandshakeAnywhere(String handshakeId) {
        for (Map.Entry<String, Handshake> entry : handshakeStore.scanAllAccountsAsMap().entrySet()) {
            Handshake handshake = entry.getValue();
            if (handshakeId.equals(handshake.getId())) {
                return new HandshakeRef(handshake, handshake.getManagementAccountId());
            }
        }
        throw handshakeNotFound();
    }

    private static AwsException handshakeNotFound() {
        return new AwsException("HandshakeNotFoundException",
                "We can't find a handshake with the HandshakeId that you specified.", 400);
    }

    private void requireOpen(Handshake handshake) {
        String state = handshake.effectiveState(now());
        if (!"OPEN".equals(state)) {
            throw new AwsException("HandshakeAlreadyInStateException",
                    "The handshake is already " + state + ".", 400);
        }
    }

    List<Handshake> handshakes(String mgmt) {
        return handshakeStore.scanForAccount(mgmt, key -> true).stream()
                .sorted(Comparator.comparing(Handshake::getId))
                .toList();
    }

    // ---------------------------------------------------------------- delegated administrators

    public void registerDelegatedAdministrator(String callerAccount, String accountId,
                                               String servicePrincipal) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        requireServicePrincipal(servicePrincipal);
        OrgAccount account = requireAccount(mgmt, accountId);
        if (accountId.equals(mgmt)) {
            throw new AwsException("ConstraintViolationException",
                    "You can't register the management account as a delegated administrator.", 400);
        }
        if (!ctx.org().getEnabledServicePrincipals().containsKey(servicePrincipal)) {
            throw new AwsException("ConstraintViolationException",
                    "Enable trusted access for " + servicePrincipal
                            + " before registering a delegated administrator.", 400);
        }
        if (account.getDelegatedServices().containsKey(servicePrincipal)) {
            throw new AwsException("AccountAlreadyRegisteredException",
                    "The account is already a delegated administrator for the service.", 400);
        }
        account.getDelegatedServices().put(servicePrincipal, now());
        accountStore.putForAccount(mgmt, accountId, account);
    }

    public void deregisterDelegatedAdministrator(String callerAccount, String accountId,
                                                 String servicePrincipal) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        requireServicePrincipal(servicePrincipal);
        OrgAccount account = requireAccount(mgmt, accountId);
        if (account.getDelegatedServices().remove(servicePrincipal) == null) {
            throw new AwsException("AccountNotRegisteredException",
                    "The account is not a delegated administrator for the service.", 400);
        }
        accountStore.putForAccount(mgmt, accountId, account);
    }

    public List<OrgAccount> listDelegatedAdministrators(String callerAccount, String servicePrincipal) {
        OrgContext ctx = resolveOrg(callerAccount);
        return memberAccounts(ctx.managementAccount()).stream()
                .filter(a -> servicePrincipal == null
                        ? !a.getDelegatedServices().isEmpty()
                        : a.getDelegatedServices().containsKey(servicePrincipal))
                .toList();
    }

    public Map<String, Double> listDelegatedServicesForAccount(String callerAccount, String accountId) {
        OrgContext ctx = resolveOrg(callerAccount);
        OrgAccount account = requireAccount(ctx.managementAccount(), accountId);
        if (account.getDelegatedServices().isEmpty()) {
            throw new AwsException("AccountNotRegisteredException",
                    "The account is not a delegated administrator for any service.", 400);
        }
        return account.getDelegatedServices();
    }

    // ---------------------------------------------------------------- AWS service access

    public void enableAwsServiceAccess(String callerAccount, String servicePrincipal) {
        OrgContext ctx = requireManagement(callerAccount);
        requireServicePrincipal(servicePrincipal);
        Organization org = ctx.org();
        org.getEnabledServicePrincipals().putIfAbsent(servicePrincipal, now());
        organizationStore.putForAccount(ctx.managementAccount(), ORG_KEY, org);
    }

    public void disableAwsServiceAccess(String callerAccount, String servicePrincipal) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        requireServicePrincipal(servicePrincipal);
        Organization org = ctx.org();
        org.getEnabledServicePrincipals().remove(servicePrincipal);
        organizationStore.putForAccount(mgmt, ORG_KEY, org);
        // Disabling trusted access also revokes the service's delegated administrators.
        for (OrgAccount account : memberAccounts(mgmt)) {
            if (account.getDelegatedServices().remove(servicePrincipal) != null) {
                accountStore.putForAccount(mgmt, account.getId(), account);
            }
        }
    }

    public Map<String, Double> listAwsServiceAccessForOrganization(String callerAccount) {
        return resolveOrg(callerAccount).org().getEnabledServicePrincipals();
    }

    private static void requireServicePrincipal(String servicePrincipal) {
        if (servicePrincipal == null || servicePrincipal.isBlank() || !servicePrincipal.contains(".")) {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for ServicePrincipal: " + servicePrincipal, 400);
        }
    }

    // ---------------------------------------------------------------- resource policy

    /** The organization's resource-based delegation policy. */
    record ResourcePolicy(String id, String arn, String content) {}

    public ResourcePolicy putResourcePolicy(String callerAccount, String content,
                                            Map<String, String> tags) {
        OrgContext ctx = requireManagement(callerAccount);
        String mgmt = ctx.managementAccount();
        validatePolicyContent(content);
        Organization org = ctx.org();
        if (org.getResourcePolicyId() == null) {
            org.setResourcePolicyId("rp-" + randomId(8));
        }
        org.setResourcePolicyContent(content);
        if (tags != null) {
            org.getResourcePolicyTags().putAll(tags);
        }
        organizationStore.putForAccount(mgmt, ORG_KEY, org);
        return resourcePolicyOf(org, mgmt);
    }

    public ResourcePolicy describeResourcePolicy(String callerAccount) {
        OrgContext ctx = resolveOrg(callerAccount);
        Organization org = ctx.org();
        if (org.getResourcePolicyId() == null) {
            throw new AwsException("ResourcePolicyNotFoundException",
                    "The organization has no resource policy.", 400);
        }
        return resourcePolicyOf(org, ctx.managementAccount());
    }

    public void deleteResourcePolicy(String callerAccount) {
        OrgContext ctx = requireManagement(callerAccount);
        Organization org = ctx.org();
        if (org.getResourcePolicyId() == null) {
            throw new AwsException("ResourcePolicyNotFoundException",
                    "The organization has no resource policy.", 400);
        }
        org.setResourcePolicyId(null);
        org.setResourcePolicyContent(null);
        org.getResourcePolicyTags().clear();
        organizationStore.putForAccount(ctx.managementAccount(), ORG_KEY, org);
    }

    private ResourcePolicy resourcePolicyOf(Organization org, String mgmt) {
        return new ResourcePolicy(org.getResourcePolicyId(),
                arn(mgmt, "resourcepolicy/" + org.getId() + "/" + org.getResourcePolicyId()),
                org.getResourcePolicyContent());
    }

    // ---------------------------------------------------------------- SCP provider

    /**
     * {@inheritDoc}
     *
     * <p>Levels are ordered root → OUs on the path → account. Only bites when
     * {@code floci.services.organizations.scp-enforcement-enabled} is set (and, in
     * practice, IAM enforcement too — {@code IamEnforcementFilter} is the only consumer).</p>
     */
    @Override
    public List<List<String>> effectiveScpLevels(String accountId) {
        if (!scpEnforcementEnabled) {
            return null;
        }
        OrgContext ctx;
        try {
            ctx = resolveOrg(accountId);
        } catch (AwsException e) {
            return null;
        }
        String mgmt = ctx.managementAccount();
        if (accountId.equals(mgmt)) {
            return null;
        }
        boolean scpEnabled = rootStore.scanForAccount(mgmt, key -> true).stream()
                .flatMap(root -> root.getPolicyTypes().stream())
                .anyMatch(t -> SCP_TYPE.equals(t.getType()) && "ENABLED".equals(t.getStatus()));
        if (!scpEnabled) {
            return null;
        }
        List<List<String>> levels = new ArrayList<>();
        for (String node : chainToTarget(mgmt, accountId)) {
            List<String> documents = policiesForTarget(mgmt, node, SCP_TYPE).stream()
                    .map(OrgPolicy::getContent)
                    .toList();
            if (!documents.isEmpty()) {
                levels.add(documents);
            }
        }
        return levels.isEmpty() ? null : levels;
    }

    private void provisionAccountAccessRole(String mgmt, String accountId, String roleName) {
        if (iamService == null) {
            return;
        }
        String effectiveRoleName =
                roleName == null || roleName.isBlank() ? "OrganizationAccountAccessRole" : roleName;
        try {
            iamService.provisionCrossAccountAdminRole(accountId, effectiveRoleName, mgmt);
        } catch (Exception e) {
            LOG.warnv("Could not provision {0} in account {1}: {2}",
                    effectiveRoleName, accountId, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- shared lookups

    List<OrgAccount> memberAccounts(String mgmt) {
        return accountStore.scanForAccount(mgmt, key -> true).stream()
                .sorted(Comparator.comparing(OrgAccount::getId))
                .toList();
    }

    List<OrganizationalUnit> organizationalUnits(String mgmt) {
        return ouStore.scanForAccount(mgmt, key -> true).stream()
                .sorted(Comparator.comparing(OrganizationalUnit::getId))
                .toList();
    }

    OrgAccount requireAccount(String mgmt, String accountId) {
        return accountStore.getForAccount(mgmt, accountId)
                .orElseThrow(() -> new AwsException("AccountNotFoundException",
                        "We can't find an AWS account with the AccountId that you specified: "
                                + accountId, 400));
    }

    OrganizationalUnit requireOu(String mgmt, String ouId) {
        return ouStore.getForAccount(mgmt, ouId)
                .orElseThrow(() -> new AwsException("OrganizationalUnitNotFoundException",
                        "We can't find an OU with the OrganizationalUnitId that you specified: "
                                + ouId, 400));
    }

    OrgRoot requireRoot(String mgmt, String rootId) {
        return rootStore.getForAccount(mgmt, rootId)
                .orElseThrow(() -> new AwsException("RootNotFoundException",
                        "We can't find a root with the RootId that you specified: " + rootId, 400));
    }

    void requireParent(String mgmt, String parentId, String errorCode) {
        boolean exists;
        if (parentId != null && parentId.startsWith("r-")) {
            exists = rootStore.getForAccount(mgmt, parentId).isPresent();
        } else if (parentId != null && parentId.startsWith("ou-")) {
            exists = ouStore.getForAccount(mgmt, parentId).isPresent();
        } else {
            throw new AwsException("InvalidInputException",
                    "You provided an invalid value for ParentId: " + parentId, 400);
        }
        if (!exists) {
            throw new AwsException(errorCode,
                    "We can't find a root or OU with the ParentId that you specified: " + parentId, 400);
        }
    }

    /** The single root's ID in the given management namespace. */
    String rootId(String mgmt) {
        return rootStore.scanForAccount(mgmt, key -> true).stream()
                .map(OrgRoot::getId)
                .sorted()
                .findFirst()
                .orElseThrow(() -> new AwsException("RootNotFoundException",
                        "The organization has no root.", 400));
    }

    private String mintAccountId(String mgmt) {
        for (int attempt = 0; attempt < 100; attempt++) {
            StringBuilder id = new StringBuilder(12);
            for (int i = 0; i < 12; i++) {
                id.append(RANDOM.nextInt(10));
            }
            String candidate = id.toString();
            if (!candidate.equals(mgmt) && accountStore.getForAccount(mgmt, candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new AwsException("ServiceException", "Unable to allocate an account ID.", 500);
    }

    static String parentType(String parentId) {
        return parentId.startsWith("r-") ? "ROOT" : "ORGANIZATIONAL_UNIT";
    }

    static boolean isAccountId(String value) {
        return value != null && value.length() == 12 && value.chars().allMatch(Character::isDigit);
    }

    static String randomId(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Organizations ARNs have an empty region segment. */
    private static String arn(String managementAccount, String resource) {
        return "arn:aws:organizations::" + managementAccount + ":" + resource;
    }

    private String defaultEmail(String accountId) {
        if (managementAccountEmailOverride != null && !managementAccountEmailOverride.isBlank()) {
            return managementAccountEmailOverride;
        }
        return "management+" + accountId + "@example.com";
    }

    static double now() {
        return System.currentTimeMillis() / 1000.0;
    }
}
