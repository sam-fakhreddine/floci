package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.organizations.model.CreateAccountStatus;
import io.github.hectorvent.floci.services.organizations.model.Handshake;
import io.github.hectorvent.floci.services.organizations.model.OrgAccount;
import io.github.hectorvent.floci.services.organizations.model.OrgPolicy;
import io.github.hectorvent.floci.services.organizations.model.OrgRoot;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationalUnit;
import io.github.hectorvent.floci.services.organizations.model.PolicyTypeSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrganizationsServiceTest {

    private static final String MGMT = "111111111111";

    private AccountAwareStorageBackend<OrgAccount> accountStore;
    private OrganizationsService service;

    @BeforeEach
    void setUp() {
        accountStore = backend();
        service = new OrganizationsService(
                backend(), accountStore, backend(), backend(), backend(), backend(), backend(),
                new com.fasterxml.jackson.databind.ObjectMapper(), null, null, true);
    }

    /** Builds a service whose management account email is overridden. */
    private OrganizationsService serviceWithManagementEmail(String email) {
        return new OrganizationsService(
                backend(), backend(), backend(), backend(), backend(), backend(), backend(),
                new com.fasterxml.jackson.databind.ObjectMapper(), null, email, false);
    }

    private static <V> AccountAwareStorageBackend<V> backend() {
        return new AccountAwareStorageBackend<>(new InMemoryStorage<>(), null, "000000000000");
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    void createOrganizationSeedsRootAndManagementAccount() {
        Organization org = service.createOrganization(MGMT, null);

        assertTrue(org.getId().startsWith("o-"));
        assertEquals("ALL", org.getFeatureSet());
        assertEquals(MGMT, org.getManagementAccountId());
        assertEquals("arn:aws:organizations::" + MGMT + ":organization/" + org.getId(), org.getArn());
        assertEquals("SERVICE_CONTROL_POLICY", org.getAvailablePolicyTypes().get(0).getType());

        List<OrgRoot> roots = service.listRoots(MGMT);
        assertEquals(1, roots.size());
        assertTrue(roots.get(0).getId().startsWith("r-"));
        assertEquals("ENABLED", roots.get(0).getPolicyTypes().get(0).getStatus());

        List<OrgAccount> accounts = service.listAccounts(MGMT);
        assertEquals(1, accounts.size());
        assertEquals(MGMT, accounts.get(0).getId());
        assertEquals("CREATED", accounts.get(0).getJoinedMethod());
    }

    @Test
    void createOrganizationUsesDefaultManagementEmailWhenUnconfigured() {
        Organization org = service.createOrganization(MGMT, null);
        assertEquals("management+" + MGMT + "@example.com", org.getManagementAccountEmail());
        assertEquals("management+" + MGMT + "@example.com", service.listAccounts(MGMT).get(0).getEmail());
    }

    @Test
    void createOrganizationUsesConfiguredManagementEmail() {
        // Real deployments (e.g. AWS Landing Zone Accelerator) resolve the management
        // account by matching the configured email against the Organizations account
        // list. The operator must be able to set that email so it matches their config,
        // instead of floci hardcoding an unusable example.com address.
        OrganizationsService svc = serviceWithManagementEmail("aws-lza-management@floci.test");
        Organization org = svc.createOrganization(MGMT, null);
        assertEquals("aws-lza-management@floci.test", org.getManagementAccountEmail());
        assertEquals("aws-lza-management@floci.test", svc.listAccounts(MGMT).get(0).getEmail());
    }

    @Test
    void createOrganizationTwiceFails() {
        service.createOrganization(MGMT, null);
        AwsException e = assertThrows(AwsException.class, () -> service.createOrganization(MGMT, "ALL"));
        assertEquals("AlreadyInOrganizationException", e.getErrorCode());
    }

    @Test
    void memberAccountCannotCreateItsOwnOrganization() {
        service.createOrganization(MGMT, null);
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();
        AwsException e = assertThrows(AwsException.class, () -> service.createOrganization(member, "ALL"));
        assertEquals("AlreadyInOrganizationException", e.getErrorCode());
    }

    @Test
    void deleteOrganizationRequiresEmptyOrganization() {
        service.createOrganization(MGMT, null);
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();

        AwsException e = assertThrows(AwsException.class, () -> service.deleteOrganization(MGMT));
        assertEquals("OrganizationNotEmptyException", e.getErrorCode());

        service.removeAccountFromOrganization(MGMT, member);
        service.deleteOrganization(MGMT);
        AwsException gone = assertThrows(AwsException.class, () -> service.describeOrganization(MGMT));
        assertEquals("AWSOrganizationsNotInUseException", gone.getErrorCode());
    }

    // ---------------------------------------------------------------- accounts and member resolution

    @Test
    void createAccountStoresRecordInManagementNamespace() {
        service.createOrganization(MGMT, null);
        CreateAccountStatus status =
                service.createAccount(MGMT, "member@example.com", "Member", null, Map.of("team", "dev"), false);

        assertEquals("SUCCEEDED", status.getState());
        String member = status.getAccountId();
        assertEquals(12, member.length());

        // The record must live under the management account's namespace, not the caller's.
        assertTrue(accountStore.getForAccount(MGMT, member).isPresent());
        assertTrue(accountStore.getForAccount(member, member).isEmpty());
    }

    @Test
    void memberAccountResolvesItsOrganization() {
        Organization org = service.createOrganization(MGMT, null);
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();

        Organization resolved = service.describeOrganization(member);
        assertEquals(org.getId(), resolved.getId());
    }

    @Test
    void managementOnlyOperationsRejectMemberCallers() {
        service.createOrganization(MGMT, null);
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();

        AwsException e = assertThrows(AwsException.class,
                () -> service.createAccount(member, "x@example.com", "X", null, null, false));
        assertEquals("AccessDeniedException", e.getErrorCode());
    }

    @Test
    void closeAccountSuspendsAndProtectsManagement() {
        service.createOrganization(MGMT, null);
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();

        service.closeAccount(MGMT, member);
        assertEquals("SUSPENDED", service.describeAccount(MGMT, member).getStatus());

        AwsException again = assertThrows(AwsException.class, () -> service.closeAccount(MGMT, member));
        assertEquals("AccountAlreadyClosedException", again.getErrorCode());

        AwsException mgmt = assertThrows(AwsException.class, () -> service.closeAccount(MGMT, MGMT));
        assertEquals("ConstraintViolationException", mgmt.getErrorCode());
    }

    @Test
    void leaveOrganizationRemovesMemberButNotManagement() {
        service.createOrganization(MGMT, null);
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();

        service.leaveOrganization(member);
        AwsException e = assertThrows(AwsException.class, () -> service.describeAccount(MGMT, member));
        assertEquals("AccountNotFoundException", e.getErrorCode());

        AwsException mgmt = assertThrows(AwsException.class, () -> service.leaveOrganization(MGMT));
        assertEquals("MasterCannotLeaveOrganizationException", mgmt.getErrorCode());
    }

    @Test
    void createAccountStatusIsQueryable() {
        service.createOrganization(MGMT, null);
        CreateAccountStatus status =
                service.createAccount(MGMT, "member@example.com", "Member", null, null, false);

        CreateAccountStatus described = service.describeCreateAccountStatus(MGMT, status.getId());
        assertEquals(status.getAccountId(), described.getAccountId());
        assertEquals(1, service.listCreateAccountStatus(MGMT, List.of("SUCCEEDED")).size());
        assertTrue(service.listCreateAccountStatus(MGMT, List.of("FAILED")).isEmpty());
    }

    // ---------------------------------------------------------------- OU tree

    @Test
    void organizationalUnitTree() {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();

        OrganizationalUnit workloads =
                service.createOrganizationalUnit(MGMT, rootId, "workloads", null);
        assertTrue(workloads.getId().startsWith("ou-" + rootId.substring(2) + "-"));

        OrganizationalUnit prod =
                service.createOrganizationalUnit(MGMT, workloads.getId(), "prod", null);

        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.createOrganizationalUnit(MGMT, rootId, "workloads", null));
        assertEquals("DuplicateOrganizationalUnitException", duplicate.getErrorCode());

        assertEquals(List.of(workloads.getId()),
                service.listOrganizationalUnitsForParent(MGMT, rootId).stream()
                        .map(OrganizationalUnit::getId).toList());

        assertEquals("ORGANIZATIONAL_UNIT", service.listParents(MGMT, prod.getId()).get(0).type());
        assertEquals(workloads.getId(), service.listParents(MGMT, prod.getId()).get(0).id());

        AwsException notEmpty = assertThrows(AwsException.class,
                () -> service.deleteOrganizationalUnit(MGMT, workloads.getId()));
        assertEquals("OrganizationalUnitNotEmptyException", notEmpty.getErrorCode());

        service.deleteOrganizationalUnit(MGMT, prod.getId());
        service.deleteOrganizationalUnit(MGMT, workloads.getId());
        assertTrue(service.listOrganizationalUnitsForParent(MGMT, rootId).isEmpty());
    }

    @Test
    void moveAccountBetweenParents() {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();
        String ouId = service.createOrganizationalUnit(MGMT, rootId, "workloads", null).getId();
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();

        service.moveAccount(MGMT, member, rootId, ouId);
        assertEquals(ouId, service.listParents(MGMT, member).get(0).id());
        assertEquals(1, service.listAccountsForParent(MGMT, ouId).size());
        assertTrue(service.listAccountsForParent(MGMT, rootId).stream()
                .noneMatch(a -> a.getId().equals(member)));

        AwsException alreadyThere = assertThrows(AwsException.class,
                () -> service.moveAccount(MGMT, member, rootId, ouId));
        assertEquals("DuplicateAccountException", alreadyThere.getErrorCode());

        AwsException badSource = assertThrows(AwsException.class,
                () -> service.moveAccount(MGMT, member, "ou-zzzz-zzzzzzzz", rootId));
        assertEquals("SourceParentNotFoundException", badSource.getErrorCode());

        AwsException badDest = assertThrows(AwsException.class,
                () -> service.moveAccount(MGMT, member, ouId, "ou-zzzz-zzzzzzzz"));
        assertEquals("DestinationParentNotFoundException", badDest.getErrorCode());
    }

    @Test
    void listChildrenSeparatesAccountsAndOus() {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();
        String ouId = service.createOrganizationalUnit(MGMT, rootId, "workloads", null).getId();

        List<OrganizationsService.NodeRef> ous = service.listChildren(MGMT, rootId, "ORGANIZATIONAL_UNIT");
        assertEquals(List.of(ouId), ous.stream().map(OrganizationsService.NodeRef::id).toList());

        List<OrganizationsService.NodeRef> accounts = service.listChildren(MGMT, rootId, "ACCOUNT");
        assertEquals(List.of(MGMT), accounts.stream().map(OrganizationsService.NodeRef::id).toList());

        AwsException badType = assertThrows(AwsException.class,
                () -> service.listChildren(MGMT, rootId, "BANANA"));
        assertEquals("InvalidInputException", badType.getErrorCode());
    }

    // ---------------------------------------------------------------- tagging

    @Test
    void tagUntagAndListAcrossResourceKinds() {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();
        String ouId = service.createOrganizationalUnit(MGMT, rootId, "workloads", null).getId();
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();

        for (String resourceId : List.of(rootId, ouId, member)) {
            service.tagResource(MGMT, resourceId, Map.of("env", "test"));
            assertEquals("test", service.tagsForResource(MGMT, resourceId).get("env"));
            service.untagResource(MGMT, resourceId, List.of("env"));
            assertFalse(service.tagsForResource(MGMT, resourceId).containsKey("env"));
        }

        AwsException bad = assertThrows(AwsException.class,
                () -> service.tagResource(MGMT, "not-a-resource", Map.of("a", "b")));
        assertEquals("InvalidInputException", bad.getErrorCode());
    }

    // ---------------------------------------------------------------- policies

    private static final String DENY_S3 =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\","
                    + "\"Action\":\"s3:*\",\"Resource\":\"*\"}]}";

    @Test
    void createOrganizationSeedsFullAwsAccessOnRootAndManagement() {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();

        List<OrgPolicy> onRoot = service.listPoliciesForTarget(MGMT, rootId, "SERVICE_CONTROL_POLICY");
        assertEquals(List.of("p-FullAWSAccess"), onRoot.stream().map(OrgPolicy::getId).toList());
        assertTrue(onRoot.get(0).isAwsManaged());

        List<OrgPolicy> onMgmt = service.listPoliciesForTarget(MGMT, MGMT, "SERVICE_CONTROL_POLICY");
        assertEquals(1, onMgmt.size());
    }

    @Test
    void newAccountsAndOusGetFullAwsAccessAutomatically() {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();
        String ouId = service.createOrganizationalUnit(MGMT, rootId, "workloads", null).getId();
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();

        assertEquals(1, service.listPoliciesForTarget(MGMT, ouId, "SERVICE_CONTROL_POLICY").size());
        assertEquals(1, service.listPoliciesForTarget(MGMT, member, "SERVICE_CONTROL_POLICY").size());
    }

    @Test
    void controlTowerGuardrailsReconcileRegisteredAndSecurityOusIdempotently() {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();
        String registered = service.createOrganizationalUnit(MGMT, rootId, "Infrastructure", null).getId();
        String security = service.createOrganizationalUnit(MGMT, rootId, "Security", null).getId();
        String unrelated = service.createOrganizationalUnit(MGMT, rootId, "Suspended", null).getId();

        service.ensureControlTowerGuardrails(MGMT, Set.of(registered));
        service.ensureControlTowerGuardrails(MGMT, Set.of(registered));

        OrgPolicy guardrail = service.describePolicy(MGMT, OrganizationsService.CONTROL_TOWER_GUARDRAIL_ID);
        assertEquals(OrganizationsService.CONTROL_TOWER_GUARDRAIL_NAME, guardrail.getName());
        assertFalse(guardrail.isAwsManaged());
        assertEquals(Set.of(registered, security), Set.copyOf(guardrail.getTargetIds()));
        assertTrue(service.listPoliciesForTarget(MGMT, registered, "SERVICE_CONTROL_POLICY")
                .stream().anyMatch(policy -> policy.getName().startsWith("aws-guardrails-")));
        assertTrue(service.listPoliciesForTarget(MGMT, security, "SERVICE_CONTROL_POLICY")
                .stream().anyMatch(policy -> policy.getName().startsWith("aws-guardrails-")));
        assertFalse(service.listPoliciesForTarget(MGMT, unrelated, "SERVICE_CONTROL_POLICY")
                .stream().anyMatch(policy -> policy.getName().startsWith("aws-guardrails-")));
    }

    @Test
    void policyCrudAndAttachmentLifecycle() {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();
        String ouId = service.createOrganizationalUnit(MGMT, rootId, "workloads", null).getId();

        OrgPolicy policy = service.createPolicy(MGMT, "deny-s3", "no s3",
                "SERVICE_CONTROL_POLICY", DENY_S3, Map.of("team", "sec"));
        assertTrue(policy.getId().startsWith("p-"));
        assertTrue(policy.getArn().contains("/service_control_policy/"));

        AwsException duplicate = assertThrows(AwsException.class, () -> service.createPolicy(
                MGMT, "deny-s3", null, "SERVICE_CONTROL_POLICY", DENY_S3, null));
        assertEquals("DuplicatePolicyException", duplicate.getErrorCode());

        service.attachPolicy(MGMT, policy.getId(), ouId);
        AwsException again = assertThrows(AwsException.class,
                () -> service.attachPolicy(MGMT, policy.getId(), ouId));
        assertEquals("DuplicatePolicyAttachmentException", again.getErrorCode());

        assertEquals(2, service.listPoliciesForTarget(MGMT, ouId, "SERVICE_CONTROL_POLICY").size());
        assertEquals(List.of(ouId), service.listTargetsForPolicy(MGMT, policy.getId()).stream()
                .map(OrganizationsService.TargetRef::targetId).toList());

        AwsException inUse = assertThrows(AwsException.class,
                () -> service.deletePolicy(MGMT, policy.getId()));
        assertEquals("PolicyInUseException", inUse.getErrorCode());

        service.detachPolicy(MGMT, policy.getId(), ouId);
        service.deletePolicy(MGMT, policy.getId());
        AwsException gone = assertThrows(AwsException.class,
                () -> service.describePolicy(MGMT, policy.getId()));
        assertEquals("PolicyNotFoundException", gone.getErrorCode());
    }

    @Test
    void concurrentPolicyAttachmentsDoNotLoseUpdates() throws Exception {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();
        String firstOu = service.createOrganizationalUnit(MGMT, rootId, "first", null).getId();
        String secondOu = service.createOrganizationalUnit(MGMT, rootId, "second", null).getId();
        OrgPolicy policy = service.createPolicy(MGMT, "concurrent", null,
                "SERVICE_CONTROL_POLICY", DENY_S3, null);

        runConcurrently(
                () -> service.attachPolicy(MGMT, policy.getId(), firstOu),
                () -> service.attachPolicy(MGMT, policy.getId(), secondOu));

        assertEquals(Set.of(firstOu, secondOu),
                Set.copyOf(service.describePolicy(MGMT, policy.getId()).getTargetIds()));
    }

    @Test
    void concurrentPolicyTypeEnablementDoesNotLoseUpdates() throws Exception {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();

        runConcurrently(
                () -> service.enablePolicyType(MGMT, rootId, "TAG_POLICY"),
                () -> service.enablePolicyType(MGMT, rootId, "BACKUP_POLICY"));

        assertEquals(Set.of("SERVICE_CONTROL_POLICY", "TAG_POLICY", "BACKUP_POLICY"),
                service.listRoots(MGMT).get(0).getPolicyTypes().stream()
                        .map(PolicyTypeSummary::getType).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("SERVICE_CONTROL_POLICY", "TAG_POLICY", "BACKUP_POLICY"),
                service.describeOrganization(MGMT).getAvailablePolicyTypes().stream()
                        .map(PolicyTypeSummary::getType).collect(java.util.stream.Collectors.toSet()));
    }

    private static void runConcurrently(Runnable first, Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> firstResult = executor.submit(() -> {
                ready.countDown();
                await(start);
                first.run();
            });
            Future<?> secondResult = executor.submit(() -> {
                ready.countDown();
                await(start);
                second.run();
            });
            ready.await();
            start.countDown();
            firstResult.get();
            secondResult.get();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting concurrent test start", e);
        }
    }

    @Test
    void lastScpCannotBeDetachedAndAwsManagedIsProtected() {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();

        AwsException lastScp = assertThrows(AwsException.class,
                () -> service.detachPolicy(MGMT, "p-FullAWSAccess", rootId));
        assertEquals("ConstraintViolationException", lastScp.getErrorCode());

        AwsException deleteManaged = assertThrows(AwsException.class,
                () -> service.deletePolicy(MGMT, "p-FullAWSAccess"));
        assertEquals("ConstraintViolationException", deleteManaged.getErrorCode());

        AwsException updateManaged = assertThrows(AwsException.class,
                () -> service.updatePolicy(MGMT, "p-FullAWSAccess", "renamed", null, null));
        assertEquals("ConstraintViolationException", updateManaged.getErrorCode());
    }

    @Test
    void policyTypeEnablementGatesAttachment() {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();
        OrgPolicy tagPolicy = service.createPolicy(MGMT, "tags", null, "TAG_POLICY",
                "{\"tags\":{}}", null);

        AwsException notEnabled = assertThrows(AwsException.class,
                () -> service.attachPolicy(MGMT, tagPolicy.getId(), rootId));
        assertEquals("PolicyTypeNotEnabledException", notEnabled.getErrorCode());

        service.enablePolicyType(MGMT, rootId, "TAG_POLICY");
        service.attachPolicy(MGMT, tagPolicy.getId(), rootId);

        AwsException alreadyEnabled = assertThrows(AwsException.class,
                () -> service.enablePolicyType(MGMT, rootId, "TAG_POLICY"));
        assertEquals("PolicyTypeAlreadyEnabledException", alreadyEnabled.getErrorCode());

        // Disabling detaches every policy of that type.
        service.disablePolicyType(MGMT, rootId, "TAG_POLICY");
        assertTrue(service.listTargetsForPolicy(MGMT, tagPolicy.getId()).isEmpty());
    }

    @Test
    void enablesNewerAwsPolicyTypes() {
        // LZA's AccountsStack enables DECLARATIVE_POLICY_EC2 (and orgs also support
        // RESOURCE_CONTROL_POLICY / CHATBOT_POLICY). These must be accepted, not rejected
        // as InvalidInputException.
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();

        for (String type : List.of("DECLARATIVE_POLICY_EC2", "RESOURCE_CONTROL_POLICY", "CHATBOT_POLICY")) {
            OrgRoot root = service.enablePolicyType(MGMT, rootId, type);
            assertTrue(root.getPolicyTypes().stream()
                            .anyMatch(t -> type.equals(t.getType()) && "ENABLED".equals(t.getStatus())),
                    "policy type " + type + " should be enabled on the root");
        }
    }

    @Test
    void effectivePolicyMergesInheritanceOperators() {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();
        String ouId = service.createOrganizationalUnit(MGMT, rootId, "workloads", null).getId();
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();
        service.moveAccount(MGMT, member, rootId, ouId);
        service.enablePolicyType(MGMT, rootId, "TAG_POLICY");

        String rootPolicy = "{\"tags\":{\"costcenter\":{\"tag_key\":{\"@@assign\":\"CostCenter\"},"
                + "\"tag_value\":{\"@@assign\":[\"100\",\"200\"]}}}}";
        String ouPolicy = "{\"tags\":{\"costcenter\":{\"tag_value\":{\"@@append\":[\"300\"]}}}}";

        String rootPolicyId = service.createPolicy(MGMT, "root-tags", null, "TAG_POLICY", rootPolicy, null).getId();
        String ouPolicyId = service.createPolicy(MGMT, "ou-tags", null, "TAG_POLICY", ouPolicy, null).getId();
        service.attachPolicy(MGMT, rootPolicyId, rootId);
        service.attachPolicy(MGMT, ouPolicyId, ouId);

        OrganizationsService.EffectivePolicy effective =
                service.describeEffectivePolicy(MGMT, "TAG_POLICY", member);
        assertEquals(member, effective.targetId());
        assertTrue(effective.policyContent().contains("\"CostCenter\""));
        assertTrue(effective.policyContent().contains("\"300\""));
        assertTrue(effective.policyContent().contains("\"100\""));

        AwsException scp = assertThrows(AwsException.class,
                () -> service.describeEffectivePolicy(MGMT, "SERVICE_CONTROL_POLICY", member));
        assertEquals("InvalidInputException", scp.getErrorCode());

        AwsException none = assertThrows(AwsException.class,
                () -> service.describeEffectivePolicy(MGMT, "BACKUP_POLICY", member));
        assertEquals("EffectivePolicyNotFoundException", none.getErrorCode());
    }

    @Test
    void policyTagsAreTaggable() {
        service.createOrganization(MGMT, null);
        String policyId = service.createPolicy(MGMT, "deny-s3", null,
                "SERVICE_CONTROL_POLICY", DENY_S3, null).getId();
        service.tagResource(MGMT, policyId, Map.of("env", "test"));
        assertEquals("test", service.tagsForResource(MGMT, policyId).get("env"));
    }

    // ---------------------------------------------------------------- handshakes

    private static final String OUTSIDER = "222222222222";

    @Test
    void inviteAcceptFlowAddsMemberWithInvitedJoinMethod() {
        service.createOrganization(MGMT, null);
        Handshake invite = service.inviteAccountToOrganization(MGMT, OUTSIDER, "ACCOUNT", "welcome");
        assertEquals("OPEN", invite.getState());
        assertEquals("INVITE", invite.getAction());

        // Repeat invite while one is open is rejected.
        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.inviteAccountToOrganization(MGMT, OUTSIDER, "ACCOUNT", null));
        assertEquals("DuplicateHandshakeException", duplicate.getErrorCode());

        // Only the invited account can accept.
        AwsException wrongCaller = assertThrows(AwsException.class,
                () -> service.acceptHandshake("333333333333", invite.getId()));
        assertEquals("AccessDeniedException", wrongCaller.getErrorCode());

        Handshake accepted = service.acceptHandshake(OUTSIDER, invite.getId());
        assertEquals("ACCEPTED", accepted.getState());
        assertEquals("INVITED", service.describeAccount(MGMT, OUTSIDER).getJoinedMethod());

        // The new member resolves the organization like any other member.
        assertEquals(service.describeOrganization(MGMT).getId(),
                service.describeOrganization(OUTSIDER).getId());
    }

    @Test
    void declineAndCancelTransitions() {
        service.createOrganization(MGMT, null);
        Handshake invite = service.inviteAccountToOrganization(MGMT, OUTSIDER, "ACCOUNT", null);
        Handshake declined = service.declineHandshake(OUTSIDER, invite.getId());
        assertEquals("DECLINED", declined.getState());

        AwsException alreadyTerminal = assertThrows(AwsException.class,
                () -> service.acceptHandshake(OUTSIDER, invite.getId()));
        assertEquals("HandshakeAlreadyInStateException", alreadyTerminal.getErrorCode());

        Handshake second = service.inviteAccountToOrganization(MGMT, OUTSIDER, "ACCOUNT", null);
        assertEquals("CANCELED", service.cancelHandshake(MGMT, second.getId()).getState());

        assertEquals(2, service.listHandshakesForAccount(OUTSIDER, null).size());
        assertEquals(2, service.listHandshakesForOrganization(MGMT, "INVITE").size());
    }

    @Test
    void enableAllFeaturesIsImmediateWithoutMembers() {
        service.createOrganization(MGMT, "CONSOLIDATED_BILLING");
        assertTrue(service.listRoots(MGMT).get(0).getPolicyTypes().isEmpty());

        Handshake handshake = service.enableAllFeatures(MGMT);
        assertEquals("ACCEPTED", handshake.getState());
        assertEquals("ALL", service.describeOrganization(MGMT).getFeatureSet());
        assertEquals("SERVICE_CONTROL_POLICY",
                service.listRoots(MGMT).get(0).getPolicyTypes().get(0).getType());
        // FullAWSAccess got seeded onto root and management account.
        assertEquals(1, service.listPoliciesForTarget(MGMT,
                service.listRoots(MGMT).get(0).getId(), "SERVICE_CONTROL_POLICY").size());
    }

    @Test
    void enableAllFeaturesWaitsForMemberApprovals() {
        service.createOrganization(MGMT, "CONSOLIDATED_BILLING");
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();

        Handshake parent = service.enableAllFeatures(MGMT);
        assertEquals("OPEN", parent.getState());
        assertEquals("CONSOLIDATED_BILLING", service.describeOrganization(MGMT).getFeatureSet());

        Handshake approval = service.listHandshakesForAccount(member, "APPROVE_ALL_FEATURES").get(0);
        service.acceptHandshake(member, approval.getId());

        assertEquals("ALL", service.describeOrganization(MGMT).getFeatureSet());
        assertEquals("ACCEPTED", service.describeHandshake(MGMT, parent.getId()).getState());

        AwsException already = assertThrows(AwsException.class, () -> service.enableAllFeatures(MGMT));
        assertEquals("HandshakeConstraintViolationException", already.getErrorCode());
    }

    // ---------------------------------------------------------------- delegated admins and service access

    @Test
    void delegatedAdministratorLifecycle() {
        service.createOrganization(MGMT, null);
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();

        AwsException noAccess = assertThrows(AwsException.class, () ->
                service.registerDelegatedAdministrator(MGMT, member, "guardduty.amazonaws.com"));
        assertEquals("ConstraintViolationException", noAccess.getErrorCode());

        service.enableAwsServiceAccess(MGMT, "guardduty.amazonaws.com");
        service.registerDelegatedAdministrator(MGMT, member, "guardduty.amazonaws.com");

        AwsException again = assertThrows(AwsException.class, () ->
                service.registerDelegatedAdministrator(MGMT, member, "guardduty.amazonaws.com"));
        assertEquals("AccountAlreadyRegisteredException", again.getErrorCode());

        assertEquals(List.of(member),
                service.listDelegatedAdministrators(MGMT, "guardduty.amazonaws.com").stream()
                        .map(OrgAccount::getId).toList());
        assertTrue(service.listDelegatedServicesForAccount(MGMT, member)
                .containsKey("guardduty.amazonaws.com"));

        // Disabling trusted access revokes the delegation.
        service.disableAwsServiceAccess(MGMT, "guardduty.amazonaws.com");
        assertTrue(service.listDelegatedAdministrators(MGMT, null).isEmpty());
        assertTrue(service.listAwsServiceAccessForOrganization(MGMT).isEmpty());

        AwsException notRegistered = assertThrows(AwsException.class, () ->
                service.deregisterDelegatedAdministrator(MGMT, member, "guardduty.amazonaws.com"));
        assertEquals("AccountNotRegisteredException", notRegistered.getErrorCode());
    }

    // ---------------------------------------------------------------- effective SCP levels

    @Test
    void effectiveScpLevelsWalkRootOuAccountChain() {
        service.createOrganization(MGMT, null);
        String rootId = service.listRoots(MGMT).get(0).getId();
        String ouId = service.createOrganizationalUnit(MGMT, rootId, "workloads", null).getId();
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();
        service.moveAccount(MGMT, member, rootId, ouId);

        // The management account is exempt.
        assertNull(service.effectiveScpLevels(MGMT));
        // Unknown accounts have no levels.
        assertNull(service.effectiveScpLevels("999999999999"));

        // FullAWSAccess sits on root, OU, and account: three levels.
        List<List<String>> levels = service.effectiveScpLevels(member);
        assertEquals(3, levels.size());
        assertTrue(levels.get(0).get(0).contains("\"Allow\""));

        // A deny-s3 SCP attached to the OU shows up on the middle level.
        String policyId = service.createPolicy(MGMT, "deny-s3", null,
                "SERVICE_CONTROL_POLICY", DENY_S3, null).getId();
        service.attachPolicy(MGMT, policyId, ouId);
        levels = service.effectiveScpLevels(member);
        assertEquals(2, levels.get(1).size());

        // Disabling the SCP policy type turns the levels off entirely.
        service.disablePolicyType(MGMT, rootId, "SERVICE_CONTROL_POLICY");
        assertNull(service.effectiveScpLevels(member));
    }

    @Test
    void effectiveScpLevelsAreNullWhenEnforcementDisabled() {
        OrganizationsService disabled = new OrganizationsService(
                backend(), backend(), backend(), backend(), backend(), backend(), backend(),
                new com.fasterxml.jackson.databind.ObjectMapper(), null, null, false);
        disabled.createOrganization(MGMT, null);
        String member = disabled.createAccount(MGMT, "member@example.com", "Member", null, null, false)
                .getAccountId();
        assertNull(disabled.effectiveScpLevels(member));
    }

    // ---------------------------------------------------------------- resource policy

    @Test
    void resourcePolicyLifecycle() {
        service.createOrganization(MGMT, null);
        AwsException none = assertThrows(AwsException.class, () -> service.describeResourcePolicy(MGMT));
        assertEquals("ResourcePolicyNotFoundException", none.getErrorCode());

        OrganizationsService.ResourcePolicy policy = service.putResourcePolicy(MGMT,
                "{\"Version\":\"2012-10-17\",\"Statement\":[]}", null);
        assertTrue(policy.id().startsWith("rp-"));
        assertEquals(policy.id(), service.describeResourcePolicy(MGMT).id());

        service.deleteResourcePolicy(MGMT);
        AwsException gone = assertThrows(AwsException.class, () -> service.describeResourcePolicy(MGMT));
        assertEquals("ResourcePolicyNotFoundException", gone.getErrorCode());
    }
}
