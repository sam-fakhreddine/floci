package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbService;
import io.github.hectorvent.floci.services.lambda.LambdaExecutorService;
import io.github.hectorvent.floci.services.lambda.LambdaFunctionStore;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.HistoryEvent;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.mutiny.core.Vertx;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Retry-cadence coverage for the ASL engine, mirroring the CDK Provider framework's waiter state
 * machine: a {@code framework-isComplete-task} that throws on every not-yet-complete poll and relies
 * on {@code Retry} to re-invoke {@code framework.isComplete}, falling through to {@code Catch}/
 * {@code framework-onTimeout-task} only once the attempt budget is exhausted.
 */
@QuarkusTest
class AslExecutorRetryTest {

    private static final String REGION = "us-east-2";
    private static final String ACCOUNT = "000000000000";
    private static final String IS_COMPLETE_NAME = "framework-isComplete";
    private static final String ON_TIMEOUT_NAME = "framework-onTimeout";
    private static final String IS_COMPLETE_ARN = lambdaArn(IS_COMPLETE_NAME);
    private static final String ON_TIMEOUT_ARN = lambdaArn(ON_TIMEOUT_NAME);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LambdaExecutorService lambdaExecutor;
    private LambdaFunctionStore functionStore;
    private LambdaFunction isCompleteFunction;
    private LambdaFunction onTimeoutFunction;
    private AslExecutor executor;

    @Inject
    Vertx vertx;

    @BeforeEach
    void setUp() {
        lambdaExecutor = mock(LambdaExecutorService.class);
        functionStore = mock(LambdaFunctionStore.class);
        isCompleteFunction = lambdaFunction(IS_COMPLETE_NAME, IS_COMPLETE_ARN);
        onTimeoutFunction = lambdaFunction(ON_TIMEOUT_NAME, ON_TIMEOUT_ARN);

        when(functionStore.get(REGION, IS_COMPLETE_NAME)).thenReturn(Optional.of(isCompleteFunction));
        when(functionStore.get(REGION, ON_TIMEOUT_NAME)).thenReturn(Optional.of(onTimeoutFunction));
        when(lambdaExecutor.invoke(eq(onTimeoutFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> success(objectMapper.createObjectNode().put("onTimeout", true)));

        executor = new AslExecutor(
                lambdaExecutor,
                functionStore,
                mock(DynamoDbService.class),
                mock(DynamoDbJsonHandler.class),
                mock(SqsJsonHandler.class),
                mock(io.github.hectorvent.floci.services.cloudformation.CloudFormationQueryHandler.class),
                mock(io.github.hectorvent.floci.services.ec2.Ec2Service.class),
                mock(S3Service.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsService.class),
                mock(io.github.hectorvent.floci.services.ecs.EcsJsonHandler.class),
                objectMapper,
                new JsonataEvaluator(objectMapper),
                mock(Instance.class), mock(EmulatorConfig.class), vertx);
    }

    /** isComplete throws once (not yet complete) then succeeds; Retry drives it through to done. */
    @Test
    void notCompleteThenCompleteDrivesToSuccessWithoutTimeout() throws Exception {
        AtomicInteger isCompleteCalls = new AtomicInteger();
        when(lambdaExecutor.invoke(eq(isCompleteFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    if (isCompleteCalls.incrementAndGet() == 1) {
                        return notComplete();
                    }
                    return success(objectMapper.createObjectNode().put("IsComplete", true)
                            .put("PhysicalResourceId", "account-123"));
                });

        Execution execution = run(waiter(1, 3));

        assertEquals("SUCCEEDED", execution.getStatus());
        assertEquals(2, isCompleteCalls.get(), "isComplete should be retried exactly once");
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertTrue(output.path("IsComplete").asBoolean());
        assertEquals("account-123", output.path("PhysicalResourceId").asText());
        verify(lambdaExecutor, times(2))
                .invoke(eq(isCompleteFunction), any(byte[].class), eq(InvocationType.RequestResponse));
        verify(lambdaExecutor, never())
                .invoke(eq(onTimeoutFunction), any(byte[].class), eq(InvocationType.RequestResponse));
    }

    /** isComplete never completes; after the attempt budget the Catch routes to onTimeout. */
    @Test
    void exhaustedRetriesFallThroughCatchToOnTimeout() throws Exception {
        AtomicInteger isCompleteCalls = new AtomicInteger();
        when(lambdaExecutor.invoke(eq(isCompleteFunction), any(byte[].class), eq(InvocationType.RequestResponse)))
                .thenAnswer(invocation -> {
                    isCompleteCalls.incrementAndGet();
                    return notComplete();
                });

        Execution execution = run(waiter(0, 2));

        assertEquals("SUCCEEDED", execution.getStatus());
        // MaxAttempts=2 -> initial invoke plus two retries = three invocations, then Catch -> onTimeout.
        assertEquals(3, isCompleteCalls.get());
        verify(lambdaExecutor, times(3))
                .invoke(eq(isCompleteFunction), any(byte[].class), eq(InvocationType.RequestResponse));
        verify(lambdaExecutor)
                .invoke(eq(onTimeoutFunction), any(byte[].class), eq(InvocationType.RequestResponse));
        JsonNode output = objectMapper.readTree(execution.getOutput());
        assertTrue(output.path("onTimeout").asBoolean());
    }

    private String waiter(int intervalSeconds, int maxAttempts) {
        return """
                {
                  "StartAt": "framework-isComplete-task",
                  "States": {
                    "framework-isComplete-task": {
                      "End": true,
                      "Retry": [{"ErrorEquals": ["States.ALL"], "IntervalSeconds": %d, "MaxAttempts": %d, "BackoffRate": 1}],
                      "Catch": [{"ErrorEquals": ["States.ALL"], "Next": "framework-onTimeout-task"}],
                      "Type": "Task",
                      "Resource": "%s"
                    },
                    "framework-onTimeout-task": {
                      "End": true,
                      "Type": "Task",
                      "Resource": "%s"
                    }
                  }
                }
                """.formatted(intervalSeconds, maxAttempts, IS_COMPLETE_ARN, ON_TIMEOUT_ARN);
    }

    private Execution run(String definition) {
        StateMachine stateMachine = new StateMachine();
        stateMachine.setName("waiter");
        stateMachine.setStateMachineArn("arn:aws:states:%s:%s:stateMachine:waiter".formatted(REGION, ACCOUNT));
        stateMachine.setRoleArn("arn:aws:iam::%s:role/test-role".formatted(ACCOUNT));
        stateMachine.setDefinition(definition);

        Execution execution = new Execution();
        execution.setName("waiter-execution");
        execution.setExecutionArn("arn:aws:states:%s:%s:execution:waiter:waiter-execution".formatted(REGION, ACCOUNT));
        execution.setStateMachineArn(stateMachine.getStateMachineArn());
        execution.setInput("{\"RequestType\":\"Create\"}");

        List<HistoryEvent> history = new ArrayList<>();
        executor.executeSync(stateMachine, execution, history, (updated, events) -> {
        });
        return execution;
    }

    private InvokeResult success(JsonNode payload) throws Exception {
        return new InvokeResult(200, null, objectMapper.writeValueAsBytes(payload), null, "req");
    }

    private InvokeResult notComplete() {
        return new InvokeResult(200, "Error",
                "{\"errorType\":\"Error\",\"errorMessage\":\"not complete\"}".getBytes(StandardCharsets.UTF_8),
                null, "req");
    }

    private static LambdaFunction lambdaFunction(String name, String arn) {
        LambdaFunction function = new LambdaFunction();
        function.setFunctionName(name);
        function.setFunctionArn(arn);
        return function;
    }

    private static String lambdaArn(String name) {
        return "arn:aws:lambda:%s:%s:function:%s".formatted(REGION, ACCOUNT, name);
    }
}
