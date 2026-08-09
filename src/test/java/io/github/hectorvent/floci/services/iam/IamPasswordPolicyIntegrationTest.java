package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamPasswordPolicyIntegrationTest {

    @Test
    @Order(1)
    void getBeforeSetReturnsNoSuchEntity() {
        given()
            .formParam("Action", "GetAccountPasswordPolicy")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchEntity"))
            .body(containsString("Password Policy"));
    }

    @Test
    @Order(2)
    void deleteBeforeSetReturnsNoSuchEntity() {
        given()
            .formParam("Action", "DeleteAccountPasswordPolicy")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchEntity"));
    }

    @Test
    @Order(3)
    void updateAccountPasswordPolicyReturnsEmptyResponse() {
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("MinimumPasswordLength", "14")
            .formParam("RequireSymbols", "true")
            .formParam("RequireNumbers", "true")
            .formParam("RequireUppercaseCharacters", "true")
            .formParam("RequireLowercaseCharacters", "true")
            .formParam("AllowUsersToChangePassword", "true")
            .formParam("MaxPasswordAge", "90")
            .formParam("PasswordReusePrevention", "24")
            .formParam("HardExpiry", "true")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<UpdateAccountPasswordPolicyResponse"))
            .body(containsString("<RequestId>"));
    }

    @Test
    @Order(4)
    void getReturnsAllStoredFields() {
        given()
            .formParam("Action", "GetAccountPasswordPolicy")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.MinimumPasswordLength",
                    equalTo("14"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.RequireSymbols",
                    equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.RequireNumbers",
                    equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.RequireUppercaseCharacters",
                    equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.RequireLowercaseCharacters",
                    equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.AllowUsersToChangePassword",
                    equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.ExpirePasswords",
                    equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.MaxPasswordAge",
                    equalTo("90"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.PasswordReusePrevention",
                    equalTo("24"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.HardExpiry",
                    equalTo("true"));
    }

    @Test
    @Order(5)
    void updateUpsertsWithDefaults() {
        given()
            .formParam("Action", "UpdateAccountPasswordPolicy")
            .formParam("RequireNumbers", "true")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "GetAccountPasswordPolicy")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.MinimumPasswordLength",
                    equalTo("6"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.RequireNumbers",
                    equalTo("true"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.RequireSymbols",
                    equalTo("false"))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.ExpirePasswords",
                    equalTo("false"))
            .body(not(containsString("<MaxPasswordAge>")))
            .body(not(containsString("<PasswordReusePrevention>")))
            .body("GetAccountPasswordPolicyResponse.GetAccountPasswordPolicyResult.PasswordPolicy.HardExpiry",
                    equalTo("false"));
    }

    @Test
    @Order(6)
    void deleteRemovesPolicy() {
        given()
            .formParam("Action", "DeleteAccountPasswordPolicy")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<DeleteAccountPasswordPolicyResponse"));

        given()
            .formParam("Action", "GetAccountPasswordPolicy")
        .when()
            .post("/")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchEntity"));
    }
}
