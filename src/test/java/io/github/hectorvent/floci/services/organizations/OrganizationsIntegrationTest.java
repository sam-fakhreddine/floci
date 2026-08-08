package io.github.hectorvent.floci.services.organizations;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;

/**
 * Wire-level coverage for the Organizations API: PascalCase bodies, the
 * {@code AWSOrganizationsV20161128.} target prefix, empty-region ARNs, and the
 * {@code __type} error shape.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrganizationsIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AWSOrganizationsV20161128.";

    private static String orgId;
    private static String rootId;
    private static String ouId;
    private static String memberId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.specification.RequestSpecification call(String action) {
        return given().header("X-Amz-Target", TARGET_PREFIX + action).contentType(CONTENT_TYPE);
    }

    @Test
    @Order(1)
    void createOrganizationReturnsEmptyRegionArn() {
        orgId = call("CreateOrganization")
            .body("""
                { "FeatureSet": "ALL" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Organization.Id", startsWith("o-"))
            .body("Organization.Arn", matchesPattern("^arn:aws:organizations::\\d{12}:organization/o-[a-z0-9]+$"))
            .body("Organization.FeatureSet", equalTo("ALL"))
            .body("Organization.AvailablePolicyTypes[0].Type", equalTo("SERVICE_CONTROL_POLICY"))
            .extract().path("Organization.Id");
    }

    @Test
    @Order(2)
    void listRootsShowsScpEnabledRoot() {
        rootId = call("ListRoots")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Roots", hasSize(1))
            .body("Roots[0].Id", startsWith("r-"))
            .body("Roots[0].PolicyTypes[0].Status", equalTo("ENABLED"))
            .extract().path("Roots[0].Id");
    }

    @Test
    @Order(3)
    void createAccountSucceedsSynchronously() {
        memberId = call("CreateAccount")
            .body("""
                { "Email": "member@example.com", "AccountName": "integration-member" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateAccountStatus.State", equalTo("SUCCEEDED"))
            .body("CreateAccountStatus.Id", startsWith("car-"))
            .extract().path("CreateAccountStatus.AccountId");
    }

    @Test
    @Order(4)
    void createOrganizationalUnitAndMoveAccount() {
        ouId = call("CreateOrganizationalUnit")
            .body("""
                { "ParentId": "%s", "Name": "workloads" }
                """.formatted(rootId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("OrganizationalUnit.Id", startsWith("ou-"))
            .extract().path("OrganizationalUnit.Id");

        call("MoveAccount")
            .body("""
                { "AccountId": "%s", "SourceParentId": "%s", "DestinationParentId": "%s" }
                """.formatted(memberId, rootId, ouId))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        call("ListAccountsForParent")
            .body("""
                { "ParentId": "%s" }
                """.formatted(ouId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accounts", hasSize(1))
            .body("Accounts[0].Id", equalTo(memberId));
    }

    @Test
    @Order(5)
    void listAccountsIncludesManagementAndMember() {
        call("ListAccounts")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Accounts", hasSize(2))
            .body("Accounts.Id", hasItem(memberId));
    }

    @Test
    @Order(6)
    void tagAndListTagsOnOu() {
        call("TagResource")
            .body("""
                { "ResourceId": "%s", "Tags": [ { "Key": "env", "Value": "test" } ] }
                """.formatted(ouId))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        call("ListTagsForResource")
            .body("""
                { "ResourceId": "%s" }
                """.formatted(ouId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Tags[0].Key", equalTo("env"))
            .body("Tags[0].Value", equalTo("test"));
    }

    @Test
    @Order(7)
    void unknownAccountReturnsTypedError() {
        call("DescribeAccount")
            .body("""
                { "AccountId": "999999999999" }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("AccountNotFoundException"));
    }

    @Test
    @Order(8)
    void describeOrganizationMatchesCreated() {
        call("DescribeOrganization")
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Organization.Id", equalTo(orgId));
    }
}
