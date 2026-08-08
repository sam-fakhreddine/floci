package io.github.hectorvent.floci.services.lambda;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.zip.CodeStore;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Reproduces the LZA LoggingStack failure: the CDK provider-framework waiter
 * invokes the custom-resource {@code isComplete} Lambda by its <em>full ARN</em>
 * (which names the member account, e.g. {@code 699432494089}) from an async
 * thread whose ambient account context is <em>not</em> that member account.
 *
 * <p>Before the fix {@link LambdaService} resolved the target purely from the
 * caller's account context, so a cross-account full-ARN invoke 404'd with
 * {@code "Function not found"} — exactly the waiter timeout observed in the
 * pipeline. A full Lambda ARN authoritatively names its account (unlike an S3
 * bucket name), so honoring that account at resolve time is unambiguously
 * correct AWS semantics and needs no opt-in flag.
 */
class LambdaCrossAccountArnInvokeTest {

    private static final String REGION = "us-east-1";
    private static final String CALLER_ACCOUNT = "000000000000";
    private static final String MEMBER_ACCOUNT = "699432494089";
    private static final String FN_NAME = "AWSAccelerator-LoggingStack-isComplete";
    private static final String FULL_ARN =
            "arn:aws:lambda:us-east-1:" + MEMBER_ACCOUNT + ":function:" + FN_NAME;

    /** One physical store; two account-scoped views over it, as production has per request. */
    private LambdaService serviceWithFunctionInMemberAccount() {
        InMemoryStorage<String, LambdaFunction> shared = new InMemoryStorage<>();

        // The deploy created the function under the member account.
        LambdaFunctionStore memberView = new LambdaFunctionStore(
                new AccountAwareStorageBackend<LambdaFunction>(shared, null, MEMBER_ACCOUNT));
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName(FN_NAME);
        fn.setVersion("$LATEST");
        fn.setAccountId(MEMBER_ACCOUNT);
        fn.setFunctionArn(FULL_ARN);
        fn.setRuntime("nodejs20.x");
        fn.setHandler("index.handler");
        fn.setRole("arn:aws:iam::" + MEMBER_ACCOUNT + ":role/test-role");
        memberView.save(REGION, fn);

        // The invoker (async waiter) runs under the caller/default account context.
        LambdaFunctionStore callerView = new LambdaFunctionStore(
                new AccountAwareStorageBackend<LambdaFunction>(shared, null, CALLER_ACCOUNT));
        return new LambdaService(callerView, new WarmPool(),
                new CodeStore(Path.of("target/test-data/lambda-code")),
                new ZipExtractor(), new RegionResolver(REGION, CALLER_ACCOUNT));
    }

    @Test
    void resolveInvokeTargetHonorsArnAccountOverCallerContext() {
        LambdaService service = serviceWithFunctionInMemberAccount();

        LambdaFunction resolved = service.resolveInvokeTarget(REGION, FN_NAME, null, MEMBER_ACCOUNT);

        assertEquals(MEMBER_ACCOUNT, resolved.getAccountId(),
                "full-ARN invoke must resolve the function in the ARN's account, not the caller context");
        assertEquals(FULL_ARN, resolved.getFunctionArn());
    }

    @Test
    void resolveInvokeTargetWithoutAccountUsesCallerContext() {
        // A function that lives in the caller account resolves via caller context when no
        // ARN account is supplied (bare-name / partial-name path unchanged).
        InMemoryStorage<String, LambdaFunction> shared = new InMemoryStorage<>();
        LambdaFunctionStore callerView = new LambdaFunctionStore(
                new AccountAwareStorageBackend<LambdaFunction>(shared, null, CALLER_ACCOUNT));
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("local-fn");
        fn.setVersion("$LATEST");
        fn.setAccountId(CALLER_ACCOUNT);
        fn.setFunctionArn("arn:aws:lambda:us-east-1:" + CALLER_ACCOUNT + ":function:local-fn");
        callerView.save(REGION, fn);
        LambdaService service = new LambdaService(callerView, new WarmPool(),
                new CodeStore(Path.of("target/test-data/lambda-code")),
                new ZipExtractor(), new RegionResolver(REGION, CALLER_ACCOUNT));

        LambdaFunction resolved = service.resolveInvokeTarget(REGION, "local-fn", null, null);

        assertEquals(CALLER_ACCOUNT, resolved.getAccountId());
    }

    @Test
    void crossAccountArnStillMissesWhenFunctionAbsentInThatAccount() {
        // Honoring the ARN account must not fabricate a hit: an absent function still 404s.
        LambdaService service = serviceWithFunctionInMemberAccount();

        assertThrows(RuntimeException.class,
                () -> service.resolveInvokeTarget(REGION, "no-such-fn", null, MEMBER_ACCOUNT));
    }

    @Test
    void fullArnParseCarriesAccount() {
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve(FULL_ARN);
        assertEquals(MEMBER_ACCOUNT, ref.account());
        assertEquals(FN_NAME, ref.name());
    }

    @Test
    void partialArnParseCarriesAccount() {
        LambdaArnUtils.ResolvedFunctionRef ref =
                LambdaArnUtils.resolve(MEMBER_ACCOUNT + ":function:" + FN_NAME);
        assertEquals(MEMBER_ACCOUNT, ref.account());
    }

    @Test
    void bareNameHasNullAccount() {
        LambdaArnUtils.ResolvedFunctionRef ref = LambdaArnUtils.resolve("just-a-name");
        assertNull(ref.account());
    }
}
