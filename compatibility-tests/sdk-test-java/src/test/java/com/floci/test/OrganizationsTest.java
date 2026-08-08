package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.Account;
import software.amazon.awssdk.services.organizations.model.AccountNotFoundException;
import software.amazon.awssdk.services.organizations.model.ChildType;
import software.amazon.awssdk.services.organizations.model.ConstraintViolationException;
import software.amazon.awssdk.services.organizations.model.CreateAccountState;
import software.amazon.awssdk.services.organizations.model.CreateAccountStatus;
import software.amazon.awssdk.services.organizations.model.CreateOrganizationResponse;
import software.amazon.awssdk.services.organizations.model.DescribeOrganizationResponse;
import software.amazon.awssdk.services.organizations.model.DuplicatePolicyAttachmentException;
import software.amazon.awssdk.services.organizations.model.EffectivePolicyType;
import software.amazon.awssdk.services.organizations.model.OrganizationFeatureSet;
import software.amazon.awssdk.services.organizations.model.OrganizationalUnit;
import software.amazon.awssdk.services.organizations.model.Policy;
import software.amazon.awssdk.services.organizations.model.PolicySummary;
import software.amazon.awssdk.services.organizations.model.PolicyType;
import software.amazon.awssdk.services.organizations.model.Root;
import software.amazon.awssdk.services.organizations.model.Tag;
import software.amazon.awssdk.services.organizations.model.TargetType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates the Organizations management API with the real AWS SDK client: creation of the
 * organization tree, synchronous account creation, SCP lifecycle, effective tag policies,
 * and the typed error shapes.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrganizationsTest {

    /** Fresh 12-digit management account per run: an account can only create one organization. */
    private static final String MGMT_ACCOUNT = "31"
            + String.format("%010d", Math.abs(new java.util.Random().nextLong() % 10_000_000_000L));

    private static OrganizationsClient organizations;
    private static String rootId;
    private static String ouId;
    private static String memberId;
    private static String scpId;

    @BeforeAll
    static void setup() {
        organizations = TestFixtures.organizationsClient(MGMT_ACCOUNT);
    }

    @AfterAll
    static void teardown() {
        organizations.close();
    }

    @Test
    @Order(1)
    void createOrganizationSeedsRootWithScpEnabled() {
        CreateOrganizationResponse created = organizations.createOrganization(
                r -> r.featureSet(OrganizationFeatureSet.ALL));
        assertThat(created.organization().id()).startsWith("o-");
        assertThat(created.organization().masterAccountId()).isEqualTo(MGMT_ACCOUNT);
        assertThat(created.organization().arn())
                .isEqualTo("arn:aws:organizations::" + MGMT_ACCOUNT + ":organization/"
                        + created.organization().id());

        List<Root> roots = organizations.listRoots(r -> r.build()).roots();
        assertThat(roots).hasSize(1);
        rootId = roots.get(0).id();
        assertThat(roots.get(0).policyTypes())
                .anyMatch(t -> t.type() == PolicyType.SERVICE_CONTROL_POLICY);
    }

    @Test
    @Order(2)
    void createAccountCompletesSynchronously() {
        CreateAccountStatus status = organizations.createAccount(
                r -> r.email("sdk-member@example.com").accountName("sdk-member"))
                .createAccountStatus();
        assertThat(status.state()).isEqualTo(CreateAccountState.SUCCEEDED);
        memberId = status.accountId();

        CreateAccountStatus described = organizations.describeCreateAccountStatus(
                r -> r.createAccountRequestId(status.id())).createAccountStatus();
        assertThat(described.accountId()).isEqualTo(memberId);

        Account account = organizations.describeAccount(r -> r.accountId(memberId)).account();
        assertThat(account.name()).isEqualTo("sdk-member");
        assertThat(account.joinedTimestamp()).isNotNull();
    }

    @Test
    @Order(3)
    void organizationalUnitTreeAndMoveAccount() {
        OrganizationalUnit ou = organizations.createOrganizationalUnit(
                r -> r.parentId(rootId).name("sdk-workloads")).organizationalUnit();
        ouId = ou.id();
        assertThat(ouId).startsWith("ou-");

        organizations.moveAccount(r -> r.accountId(memberId)
                .sourceParentId(rootId).destinationParentId(ouId));

        assertThat(organizations.listAccountsForParent(r -> r.parentId(ouId)).accounts())
                .extracting(Account::id).containsExactly(memberId);
        assertThat(organizations.listChildren(r -> r.parentId(rootId).childType(ChildType.ACCOUNT))
                .children()).extracting(c -> c.id()).containsExactly(MGMT_ACCOUNT);
        assertThat(organizations.listParents(r -> r.childId(memberId)).parents().get(0).id())
                .isEqualTo(ouId);
    }

    @Test
    @Order(4)
    void scpLifecycle() {
        Policy policy = organizations.createPolicy(r -> r
                .name("sdk-deny-s3")
                .description("deny s3")
                .type(PolicyType.SERVICE_CONTROL_POLICY)
                .content("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Deny\","
                        + "\"Action\":\"s3:*\",\"Resource\":\"*\"}]}"))
                .policy();
        scpId = policy.policySummary().id();
        assertThat(policy.policySummary().arn()).contains("/service_control_policy/");
        assertThat(policy.policySummary().awsManaged()).isFalse();

        organizations.attachPolicy(r -> r.policyId(scpId).targetId(ouId));
        assertThatThrownBy(() -> organizations.attachPolicy(r -> r.policyId(scpId).targetId(ouId)))
                .isInstanceOf(DuplicatePolicyAttachmentException.class);

        List<PolicySummary> onOu = organizations.listPoliciesForTarget(
                r -> r.targetId(ouId).filter(PolicyType.SERVICE_CONTROL_POLICY)).policies();
        assertThat(onOu).extracting(PolicySummary::id).contains(scpId, "p-FullAWSAccess");

        assertThat(organizations.listTargetsForPolicy(r -> r.policyId(scpId)).targets())
                .anyMatch(t -> t.type() == TargetType.ORGANIZATIONAL_UNIT);

        // The last SCP on a target can't be detached.
        assertThatThrownBy(() -> organizations.detachPolicy(
                r -> r.policyId("p-FullAWSAccess").targetId(memberId)))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    @Order(5)
    void effectiveTagPolicyMergesTheChain() {
        organizations.enablePolicyType(r -> r.rootId(rootId).policyType(PolicyType.TAG_POLICY));
        String tagPolicyId = organizations.createPolicy(r -> r
                .name("sdk-tags")
                .type(PolicyType.TAG_POLICY)
                .content("{\"tags\":{\"team\":{\"tag_key\":{\"@@assign\":\"Team\"}}}}"))
                .policy().policySummary().id();
        organizations.attachPolicy(r -> r.policyId(tagPolicyId).targetId(ouId));

        String effective = organizations.describeEffectivePolicy(r -> r
                .policyType(EffectivePolicyType.TAG_POLICY).targetId(memberId))
                .effectivePolicy().policyContent();
        assertThat(effective).contains("\"Team\"");
    }

    @Test
    @Order(6)
    void taggingRoundTrip() {
        organizations.tagResource(r -> r.resourceId(ouId)
                .tags(Tag.builder().key("env").value("sdk-test").build()));
        assertThat(organizations.listTagsForResource(r -> r.resourceId(ouId)).tags())
                .anyMatch(t -> t.key().equals("env") && t.value().equals("sdk-test"));
        organizations.untagResource(r -> r.resourceId(ouId).tagKeys("env"));
        assertThat(organizations.listTagsForResource(r -> r.resourceId(ouId)).tags())
                .noneMatch(t -> t.key().equals("env"));
    }

    @Test
    @Order(7)
    void memberAccountResolvesTheSameOrganization() {
        try (OrganizationsClient memberClient = TestFixtures.organizationsClient(memberId)) {
            DescribeOrganizationResponse fromMember = memberClient.describeOrganization(r -> r.build());
            DescribeOrganizationResponse fromMgmt = organizations.describeOrganization(r -> r.build());
            assertThat(fromMember.organization().id()).isEqualTo(fromMgmt.organization().id());
        }
    }

    @Test
    @Order(8)
    void typedErrorsSurfaceThroughTheSdk() {
        assertThatThrownBy(() -> organizations.describeAccount(r -> r.accountId("999999999999")))
                .isInstanceOf(AccountNotFoundException.class);
        assertThatThrownBy(() -> organizations.closeAccount(r -> r.accountId(MGMT_ACCOUNT)))
                .isInstanceOf(ConstraintViolationException.class);
    }
}
