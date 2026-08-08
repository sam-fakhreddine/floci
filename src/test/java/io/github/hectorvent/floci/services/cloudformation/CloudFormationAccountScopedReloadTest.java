package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.s3.S3Service;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies account-scoped stack storage survives a restart: stacks persisted under different
 * account prefixes reload into their own accounts, and legacy entries persisted without an account
 * prefix (or without an accountId field) load into the default account.
 */
class CloudFormationAccountScopedReloadTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String ACCOUNT_A = "111111111111";
    private static final String ACCOUNT_B = "222222222222";
    private static final String REGION = "us-east-1";
    private static final String STACKS_FILE = "cloudformation-stacks.json";

    @TempDir
    Path tempDir;

    @Test
    void accountScopedStacksSurviveRestartAndLegacyEntriesLoadIntoDefaultAccount() {
        var seeder = new PersistentStorage<String, Stack>(
                tempDir.resolve(STACKS_FILE), new TypeReference<Map<String, Stack>>() {});
        seeder.put(ACCOUNT_A + "/" + REGION + ":shared-stack",
                stack(ACCOUNT_A, "shared-stack", "{\"Resources\":{\"A\":{}}}"));
        seeder.put(ACCOUNT_B + "/" + REGION + ":shared-stack",
                stack(ACCOUNT_B, "shared-stack", "{\"Resources\":{\"B\":{}}}"));
        seeder.put(REGION + ":legacy-stack", stack(null, "legacy-stack", "{\"Resources\":{}}"));

        CloudFormationService service = newService();
        service.loadPersistedState();

        List<Stack> forA = service.describeStacks("shared-stack", REGION, ACCOUNT_A);
        assertEquals(1, forA.size());
        assertEquals(ACCOUNT_A, forA.get(0).getAccountId());
        assertEquals("{\"Resources\":{\"A\":{}}}", forA.get(0).getTemplateBody());

        List<Stack> forB = service.describeStacks("shared-stack", REGION, ACCOUNT_B);
        assertEquals(ACCOUNT_B, forB.get(0).getAccountId());
        assertEquals("{\"Resources\":{\"B\":{}}}", forB.get(0).getTemplateBody());

        Stack legacy = service.describeStacks("legacy-stack", REGION, DEFAULT_ACCOUNT).get(0);
        assertEquals(DEFAULT_ACCOUNT, legacy.getAccountId());
        assertThrows(AwsException.class,
                () -> service.describeStacks("legacy-stack", REGION, ACCOUNT_A));
        assertThrows(AwsException.class,
                () -> service.describeStacks("shared-stack", REGION, DEFAULT_ACCOUNT));
    }

    @Test
    void newStacksPersistUnderTheirOwnAccountPrefix() {
        CloudFormationService service = newService();
        service.loadPersistedState();

        service.createChangeSet("fresh-stack", "cs1", "CREATE", "{\"Resources\":{}}", null,
                Map.of(), List.of(), Map.of(), REGION, "333333333333");

        var reader = new PersistentStorage<String, Stack>(
                tempDir.resolve(STACKS_FILE), new TypeReference<Map<String, Stack>>() {});
        reader.load();
        Stack persisted = reader.get("333333333333/" + REGION + ":fresh-stack").orElseThrow();
        assertEquals("333333333333", persisted.getAccountId());
        assertTrue(persisted.getStackId().contains(":333333333333:stack/fresh-stack/"));
    }

    private CloudFormationService newService() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(DEFAULT_ACCOUNT);
        return new CloudFormationService(
                mock(CloudFormationResourceProvisioner.class), mock(S3Service.class),
                new ObjectMapper(), config, new RegionResolver(REGION, DEFAULT_ACCOUNT),
                Clock.systemUTC(), new TempDirStorageFactory(tempDir));
    }

    private static Stack stack(String accountId, String stackName, String templateBody) {
        Stack stack = new Stack();
        stack.setStackName(stackName);
        stack.setAccountId(accountId);
        stack.setRegion(REGION);
        stack.setStatus("CREATE_COMPLETE");
        stack.setTemplateBody(templateBody);
        String account = accountId != null ? accountId : DEFAULT_ACCOUNT;
        stack.setStackId("arn:aws:cloudformation:" + REGION + ":" + account
                + ":stack/" + stackName + "/abc123");
        return stack;
    }

    private static final class TempDirStorageFactory extends StorageFactory {
        private final Path dir;

        private TempDirStorageFactory(Path dir) {
            super(null, null);
            this.dir = dir;
        }

        @Override
        public <V> StorageBackend<String, V> create(String serviceName, String fileName,
                                                    TypeReference<Map<String, V>> typeReference) {
            var inner = new PersistentStorage<String, V>(dir.resolve(fileName), typeReference);
            inner.load();
            return new AccountAwareStorageBackend<>(inner, null, DEFAULT_ACCOUNT);
        }
    }
}
