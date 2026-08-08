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
| `TagResource` | Adds or overwrites tags on a root, OU, or account |
| `UntagResource` | Removes tags from a root, OU, or account |
| `ListTagsForResource` | Returns the tags on a root, OU, or account |
<!-- floci:actions:end -->

## Configuration

| Environment variable | Default | Description |
| --- | --- | --- |
| `FLOCI_SERVICES_ORGANIZATIONS_ENABLED` | `true` | Enables the service |
| `FLOCI_SERVICES_ORGANIZATIONS_SCP_ENFORCEMENT_ENABLED` | `false` | When `true` (and IAM enforcement is enabled), attached service control policies participate in IAM policy evaluation |
| `FLOCI_STORAGE_SERVICES_ORGANIZATIONS_MODE` | inherits `FLOCI_STORAGE_MODE` | Storage mode override |
| `FLOCI_STORAGE_SERVICES_ORGANIZATIONS_FLUSH_INTERVAL_MS` | `5000` | Hybrid/WAL flush interval |

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
