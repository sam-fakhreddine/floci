package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for account-scoped stack storage: two accounts deploying the same stack name
 * into the same region (the AWS Landing Zone Accelerator bootstrap pattern) must not clobber each
 * other's templates, outputs, events, exports, or change sets.
 */
@QuarkusTest
class CloudFormationAccountIsolationIntegrationTest {

    private static final String ACCOUNT_A = "111111111111";
    private static final String ACCOUNT_B = "222222222222";
    private static final String REGION = "us-east-1";

    private static String auth(String account, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260215/" + REGION + "/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static String template(String queueName, String exportName, String exportValue) {
        return """
            {"Resources":{"Q":{"Type":"AWS::SQS::Queue","Properties":{"QueueName":"%s"}}},
             "Outputs":{"Marker":{"Value":"%s","Export":{"Name":"%s"}}}}
            """.formatted(queueName, exportValue, exportName);
    }

    private static String outputOnlyTemplate(String exportName, String exportValue) {
        return """
            {"Resources":{},
             "Outputs":{"Marker":{"Value":"%s","Export":{"Name":"%s"}}}}
            """.formatted(exportValue, exportName);
    }

    private void createStack(String account, String stackName, String templateBody) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", templateBody)
            .header("Authorization", auth(account, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString(":" + account + ":stack/" + stackName + "/"));
    }

    private void assertQueueVisible(String account, String queueName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", queueName)
            .header("Authorization", auth(account, "sqs"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("/" + account + "/" + queueName));
    }

    private void assertQueueAbsent(String account, String queueName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", queueName)
            .header("Authorization", auth(account, "sqs"))
        .when().post("/")
        .then().statusCode(400);
    }

    private static void awaitStackDeleted(String account, String stackName) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            int status = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
                .header("Authorization", auth(account, "cloudformation"))
            .when().post("/")
            .then().extract().statusCode();
            if (status == 400) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Stack " + stackName + " was not deleted in account " + account);
    }

    private static void awaitStackStatus(String account, String stackName, String status)
            throws InterruptedException {
        String body = null;
        for (int i = 0; i < 100; i++) {
            body = given()
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", stackName)
                .header("Authorization", auth(account, "cloudformation"))
            .when().post("/")
            .then().extract().asString();
            if (body.contains("<StackStatus>" + status + "</StackStatus>")) {
                return;
            }
            Thread.sleep(50);
        }
        fail("Stack " + stackName + " in account " + account + " did not reach " + status + ": " + body);
    }

    @Test
    void sameStackNameAndRegionIsIndependentPerAccount() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stackName = "iso-stack-" + suffix;
        String exportName = "iso-export-" + suffix;
        String queueA = "iso-qa-" + suffix;
        String queueB = "iso-qb-" + suffix;

        // Both accounts deploy the SAME stack name into the SAME region with different templates.
        createStack(ACCOUNT_A, stackName, template(queueA, exportName, "from-account-a"));
        createStack(ACCOUNT_B, stackName, template(queueB, exportName, "from-account-b"));

        // Each account sees its own stack: own StackId, own outputs.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
            .header("Authorization", auth(ACCOUNT_A, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString(":" + ACCOUNT_A + ":stack/" + stackName + "/"))
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("<OutputValue>from-account-a</OutputValue>"))
            .body(not(containsString("from-account-b")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
            .header("Authorization", auth(ACCOUNT_B, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString(":" + ACCOUNT_B + ":stack/" + stackName + "/"))
            .body(containsString("<OutputValue>from-account-b</OutputValue>"))
            .body(not(containsString("from-account-a")));

        // GetTemplate returns each account's own template body.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetTemplate")
            .formParam("StackName", stackName)
            .header("Authorization", auth(ACCOUNT_A, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString(queueA))
            .body(not(containsString(queueB)));

        // Stack events are per-account: account A's events reference only its own StackId.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStackEvents")
            .formParam("StackName", stackName)
            .header("Authorization", auth(ACCOUNT_A, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString(":" + ACCOUNT_A + ":stack/" + stackName + "/"))
            .body(not(containsString(":" + ACCOUNT_B + ":")));

        // The same export name carries a different value per account (exports are per-account).
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListExports")
            .header("Authorization", auth(ACCOUNT_A, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<Name>" + exportName + "</Name>"))
            .body(containsString("<Value>from-account-a</Value>"))
            .body(not(containsString("from-account-b")));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ListExports")
            .header("Authorization", auth(ACCOUNT_B, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<Value>from-account-b</Value>"))
            .body(not(containsString("from-account-a")));

        // Resources were provisioned into each account's namespace.
        assertQueueVisible(ACCOUNT_A, queueA);
        assertQueueVisible(ACCOUNT_B, queueB);
        assertQueueAbsent(ACCOUNT_A, queueB);
        assertQueueAbsent(ACCOUNT_B, queueA);

        // Deleting account A's stack leaves account B's untouched.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
            .header("Authorization", auth(ACCOUNT_A, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);
        awaitStackDeleted(ACCOUNT_A, stackName);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
            .header("Authorization", auth(ACCOUNT_B, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"));
        assertQueueAbsent(ACCOUNT_A, queueA);
        assertQueueVisible(ACCOUNT_B, queueB);
    }

    @Test
    void changeSetsWithSameNameDoNotCollideAcrossAccounts() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String stackName = "cs-iso-stack-" + suffix;
        String changeSetName = "cdk-deploy-change-set";

        createStack(ACCOUNT_A, stackName, outputOnlyTemplate("cs-a-" + suffix, "a-v1"));
        createStack(ACCOUNT_B, stackName, outputOnlyTemplate("cs-b-" + suffix, "b-v1"));

        // Both accounts create a change set with the SAME fixed name against the SAME stack name.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", changeSetName)
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", outputOnlyTemplate("cs-a-" + suffix, "a-v2"))
            .header("Authorization", auth(ACCOUNT_A, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString(":" + ACCOUNT_A + ":changeSet/" + changeSetName + "/"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", changeSetName)
            .formParam("ChangeSetType", "UPDATE")
            .formParam("TemplateBody", outputOnlyTemplate("cs-b-" + suffix, "b-v2"))
            .header("Authorization", auth(ACCOUNT_B, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString(":" + ACCOUNT_B + ":changeSet/" + changeSetName + "/"));

        // Each account describes its own change set against its own stack.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", changeSetName)
            .header("Authorization", auth(ACCOUNT_A, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString(":" + ACCOUNT_A + ":changeSet/" + changeSetName + "/"))
            .body(containsString(":" + ACCOUNT_A + ":stack/" + stackName + "/"));

        // Executing account B's change set updates only account B's stack.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ExecuteChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", changeSetName)
            .header("Authorization", auth(ACCOUNT_B, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);
        awaitStackStatus(ACCOUNT_B, stackName, "UPDATE_COMPLETE");

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
            .header("Authorization", auth(ACCOUNT_B, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<OutputValue>b-v2</OutputValue>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
            .header("Authorization", auth(ACCOUNT_A, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .body(containsString("<OutputValue>a-v1</OutputValue>"));

        // Deleting account A's change set does not touch account B's.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DeleteChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", changeSetName)
            .header("Authorization", auth(ACCOUNT_A, "cloudformation"))
        .when().post("/")
        .then().statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", changeSetName)
            .header("Authorization", auth(ACCOUNT_A, "cloudformation"))
        .when().post("/")
        .then().statusCode(400)
            .body(containsString("ChangeSetNotFoundException"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "DescribeChangeSet")
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", changeSetName)
            .header("Authorization", auth(ACCOUNT_B, "cloudformation"))
        .when().post("/")
        .then().statusCode(200)
            .body(containsString(":" + ACCOUNT_B + ":changeSet/" + changeSetName + "/"));
    }
}
