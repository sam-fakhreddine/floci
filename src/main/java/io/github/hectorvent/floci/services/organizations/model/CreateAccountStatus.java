package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The status record of a CreateAccount or CreateGovCloudAccount request.
 *
 * <p>Account creation is synchronous in the emulator, so records are stored already
 * {@code SUCCEEDED}, but the PENDING→SUCCEEDED shape is preserved so that SDK wait
 * loops polling DescribeCreateAccountStatus behave realistically.</p>
 *
 * @see <a href="https://docs.aws.amazon.com/organizations/latest/APIReference/API_CreateAccountStatus.html">CreateAccountStatus</a>
 */
@RegisterForReflection
public class CreateAccountStatus {

    private String id;
    private String accountName;
    private String accountId;
    private String state;
    private double requestedTimestamp;
    private Double completedTimestamp;
    private String failureReason;
    private String govCloudAccountId;

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }

    public String getAccountName() { return accountName; }
    public void setAccountName(String v) { this.accountName = v; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String v) { this.accountId = v; }

    public String getState() { return state; }
    public void setState(String v) { this.state = v; }

    public double getRequestedTimestamp() { return requestedTimestamp; }
    public void setRequestedTimestamp(double v) { this.requestedTimestamp = v; }

    public Double getCompletedTimestamp() { return completedTimestamp; }
    public void setCompletedTimestamp(Double v) { this.completedTimestamp = v; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String v) { this.failureReason = v; }

    public String getGovCloudAccountId() { return govCloudAccountId; }
    public void setGovCloudAccountId(String v) { this.govCloudAccountId = v; }
}
