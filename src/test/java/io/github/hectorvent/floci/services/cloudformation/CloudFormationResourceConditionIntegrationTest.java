package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A resource whose {@code Condition} evaluates false must not be provisioned at all.
 * Regression: condition-false resources used to fall through the topological sort with
 * in-degree zero and get created first — Landing Zone Accelerator's installer template
 * grew a stray CodeCommit pipeline (with an unresolved Fn::GetAtt in its name) that way.
 */
@QuarkusTest
class CloudFormationResourceConditionIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260808/us-east-1/cloudformation/aws4_request";
    private static final String STACK = "condition-gate-it";

    private static final String TEMPLATE = """
        {
          "Parameters": {
            "Mode": {"Type": "String", "Default": "github"}
          },
          "Conditions": {
            "UseCodeCommit": {"Fn::Equals": [{"Ref": "Mode"}, "codecommit"]},
            "UseGitHub": {"Fn::Equals": [{"Ref": "Mode"}, "github"]}
          },
          "Resources": {
            "GitHubParam": {
              "Type": "AWS::SSM::Parameter",
              "Condition": "UseGitHub",
              "Properties": {"Name": "/condition-gate-it/github", "Type": "String", "Value": "on"}
            },
            "CodeCommitParam": {
              "Type": "AWS::SSM::Parameter",
              "Condition": "UseCodeCommit",
              "Properties": {"Name": "/condition-gate-it/codecommit", "Type": "String", "Value": "on"}
            }
          }
        }
        """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void conditionFalseResourcesAreNotProvisioned() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", STACK)
            .formParam("TemplateBody", TEMPLATE)
        .when().post("/").then().statusCode(200);

        String resources = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackResources")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200).extract().asString();

        assertTrue(resources.contains("GitHubParam"));
        assertFalse(resources.contains("CodeCommitParam"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", STACK)
        .when().post("/").then().statusCode(200);
    }
}
