package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.organizations.model.CreateAccountStatus;
import io.github.hectorvent.floci.services.organizations.model.OrgAccount;
import io.github.hectorvent.floci.services.organizations.model.OrgRoot;
import io.github.hectorvent.floci.services.organizations.model.Organization;
import io.github.hectorvent.floci.services.organizations.model.OrganizationalUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                backend(), accountStore, backend(), backend(), backend());
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
    void createOrganizationTwiceFails() {
        service.createOrganization(MGMT, null);
        AwsException e = assertThrows(AwsException.class, () -> service.createOrganization(MGMT, "ALL"));
        assertEquals("AlreadyInOrganizationException", e.getErrorCode());
    }

    @Test
    void memberAccountCannotCreateItsOwnOrganization() {
        service.createOrganization(MGMT, null);
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, false)
                .getAccountId();
        AwsException e = assertThrows(AwsException.class, () -> service.createOrganization(member, "ALL"));
        assertEquals("AlreadyInOrganizationException", e.getErrorCode());
    }

    @Test
    void deleteOrganizationRequiresEmptyOrganization() {
        service.createOrganization(MGMT, null);
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, false)
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
                service.createAccount(MGMT, "member@example.com", "Member", Map.of("team", "dev"), false);

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
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, false)
                .getAccountId();

        Organization resolved = service.describeOrganization(member);
        assertEquals(org.getId(), resolved.getId());
    }

    @Test
    void managementOnlyOperationsRejectMemberCallers() {
        service.createOrganization(MGMT, null);
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, false)
                .getAccountId();

        AwsException e = assertThrows(AwsException.class,
                () -> service.createAccount(member, "x@example.com", "X", null, false));
        assertEquals("AccessDeniedException", e.getErrorCode());
    }

    @Test
    void closeAccountSuspendsAndProtectsManagement() {
        service.createOrganization(MGMT, null);
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, false)
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
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, false)
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
                service.createAccount(MGMT, "member@example.com", "Member", null, false);

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
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, false)
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
        String member = service.createAccount(MGMT, "member@example.com", "Member", null, false)
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
}
