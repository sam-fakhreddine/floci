# AWS Organizations

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSOrganizationsV20161128.*`)
**Endpoint:** `POST http://localhost:4566/`
**Signing name:** `organizations`

Organizations is a **global** service: resources are not region-scoped and ARNs have an
empty region segment (`arn:aws:organizations::<account>:organization/o-...`).

All organization state is owned by the **management account** — the account whose
credentials called `CreateOrganization`. Member accounts created with `CreateAccount`
resolve to the same organization when they call the API, so `DescribeOrganization`
works with member credentials (a 12-digit access key ID selects the calling account —
see [multi-account support](../configuration/multi-account.md)).

Account creation is synchronous: `CreateAccount` returns an already-`SUCCEEDED`
`CreateAccountStatus`, and the new account's ID is immediately usable as an access key
ID against any other emulated service.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateOrganization` | Creates an organization with the caller as management account, plus its root |
| `DescribeOrganization` | Returns the caller's organization; works for member accounts |
| `DeleteOrganization` | Deletes an organization once all members, OUs, and policies are gone |
| `CreateAccount` | Creates a member account synchronously and parents it to the root |
| `CreateGovCloudAccount` | Same as CreateAccount, plus a linked GovCloud account ID |
| `DescribeCreateAccountStatus` | Returns one account-creation status record by `car-` ID |
| `ListCreateAccountStatus` | Lists account-creation status records, optionally by state |
| `DescribeAccount` | Returns one member account |
| `ListAccounts` | Lists all accounts in the organization |
| `ListAccountsForParent` | Lists the accounts directly under a root or OU |
| `CloseAccount` | Marks a member account SUSPENDED |
| `RemoveAccountFromOrganization` | Removes a member account (management account only) |
| `LeaveOrganization` | Removes the calling member account from its organization |
| `MoveAccount` | Moves an account between two roots/OUs |
| `ListParents` | Returns the parent root or OU of an account or OU |
| `ListChildren` | Lists child accounts or OUs of a root or OU |
| `ListRoots` | Lists the organization's root and its enabled policy types |
| `CreateOrganizationalUnit` | Creates an OU under a root or another OU |
| `DescribeOrganizationalUnit` | Returns one OU |
| `UpdateOrganizationalUnit` | Renames an OU |
| `DeleteOrganizationalUnit` | Deletes an empty OU |
| `ListOrganizationalUnitsForParent` | Lists the OUs directly under a root or OU |
| `InviteAccountToOrganization` | Opens an INVITE handshake for an account (by 12-digit ID) |
| `AcceptHandshake` | Invitee accepts; INVITE joins the org, APPROVE_ALL_FEATURES counts toward migration |
| `DeclineHandshake` | Invitee declines an open handshake |
| `CancelHandshake` | The management account cancels an open handshake it originated |
| `DescribeHandshake` | Returns one handshake; parties only |
| `ListHandshakesForAccount` | Lists handshakes the calling account is a party to |
| `ListHandshakesForOrganization` | Lists the organization's handshakes (management account only) |
| `EnableAllFeatures` | Starts the ALL-features migration; finalizes once every member approves |
| `RegisterDelegatedAdministrator` | Marks a member account delegated admin for a service principal |
| `DeregisterDelegatedAdministrator` | Removes a delegated-administrator registration |
| `ListDelegatedAdministrators` | Lists delegated-admin accounts, optionally for one service |
| `ListDelegatedServicesForAccount` | Lists the service principals an account administers |
| `EnableAWSServiceAccess` | Enables trusted access for a service principal |
| `DisableAWSServiceAccess` | Disables trusted access and revokes its delegated admins |
| `ListAWSServiceAccessForOrganization` | Lists service principals with trusted access |
| `PutResourcePolicy` | Creates or updates the organization's resource policy |
| `DescribeResourcePolicy` | Returns the organization's resource policy |
| `DeleteResourcePolicy` | Deletes the organization's resource policy |
| `CreatePolicy` | Creates a policy of any of the four types |
| `DescribePolicy` | Returns one policy with its content |
| `UpdatePolicy` | Updates a policy's name, description, or content |
| `DeletePolicy` | Deletes a detached, customer-managed policy |
| `ListPolicies` | Lists policies of one type |
| `AttachPolicy` | Attaches a policy to a root, OU, or account |
| `DetachPolicy` | Detaches a policy; the last SCP on a target can't be detached |
| `ListPoliciesForTarget` | Lists policies of one type attached to a target |
| `ListTargetsForPolicy` | Lists the roots, OUs, and accounts a policy is attached to |
| `EnablePolicyType` | Enables a policy type on the root; enabling SCPs attaches FullAWSAccess everywhere |
| `DisablePolicyType` | Disables a policy type and detaches all policies of that type |
| `DescribeEffectivePolicy` | Merges the inherited policy chain for the non-SCP types |
| `TagResource` | Adds or overwrites tags on a root, OU, account, or policy |
| `UntagResource` | Removes tags from a root, OU, account, or policy |
| `ListTagsForResource` | Returns the tags on a root, OU, account, or policy |
<!-- floci:actions:end -->

## Configuration

| Environment variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_ORGANIZATIONS_ENABLED` | `true` | Enables the service |
| `FLOCI_SERVICES_ORGANIZATIONS_SCP_ENFORCEMENT_ENABLED` | `false` | When `true` (and IAM enforcement is enabled), attached service control policies participate in IAM policy evaluation |
| `FLOCI_SERVICES_ORGANIZATIONS_MANAGEMENT_ACCOUNT_EMAIL` | unset | Email reported for the organization's management account (`DescribeOrganization` master account, `ListAccounts`). Unset falls back to the built-in default |
| `FLOCI_STORAGE_SERVICES_ORGANIZATIONS_MODE` | inherits `FLOCI_STORAGE_MODE` | Storage mode override |
| `FLOCI_STORAGE_SERVICES_ORGANIZATIONS_FLUSH_INTERVAL_MS` | `5000` | Hybrid/WAL flush interval |

## SCP enforcement

With `FLOCI_SERVICES_IAM_ENFORCEMENT_ENABLED=true` and
`FLOCI_SERVICES_ORGANIZATIONS_SCP_ENFORCEMENT_ENABLED=true`, service control policies
attached to the root, OUs, and accounts participate in IAM policy evaluation: an action
must be allowed at every level of the account's chain and denied at none, before the
caller's identity policies are consulted. SCPs never grant permissions on their own, and
the management account is exempt — both matching AWS. See
[IAM enforcement](iam.md#service-control-policies-scps) for the evaluation order.

`DescribeEffectivePolicy` applies to the three non-SCP policy types and merges the
inherited chain with the `@@assign`, `@@append`, and `@@remove` inheritance operators.

## Example

```bash
aws --endpoint-url http://localhost:4566 organizations create-organization --feature-set ALL

ROOT_ID=$(aws --endpoint-url http://localhost:4566 organizations list-roots \
  --query 'Roots[0].Id' --output text)

aws --endpoint-url http://localhost:4566 organizations create-organizational-unit \
  --parent-id "$ROOT_ID" --name workloads

aws --endpoint-url http://localhost:4566 organizations create-account \
  --email member@example.com --account-name "workload-account"

aws --endpoint-url http://localhost:4566 organizations list-accounts
```
