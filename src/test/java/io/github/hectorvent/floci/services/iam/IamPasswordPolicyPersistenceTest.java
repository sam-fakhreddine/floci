package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.iam.model.PasswordPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IamPasswordPolicyPersistenceTest {

    @Test
    void passwordPolicySurvivesRestart(@TempDir Path dir) {
        IamService first = newService(dir);
        PasswordPolicy policy = new PasswordPolicy();
        policy.setMinimumPasswordLength(12);
        policy.setRequireSymbols(true);
        policy.setRequireNumbers(true);
        policy.setAllowUsersToChangePassword(true);
        policy.setMaxPasswordAge(90);
        policy.setPasswordReusePrevention(5);
        policy.setHardExpiry(true);
        first.updateAccountPasswordPolicy(policy);

        IamService restarted = newService(dir);
        PasswordPolicy loaded = restarted.getAccountPasswordPolicy();

        assertEquals(12, loaded.getMinimumPasswordLength());
        assertTrue(loaded.isRequireSymbols());
        assertTrue(loaded.isRequireNumbers());
        assertFalse(loaded.isRequireUppercaseCharacters());
        assertFalse(loaded.isRequireLowercaseCharacters());
        assertTrue(loaded.isAllowUsersToChangePassword());
        assertEquals(90, loaded.getMaxPasswordAge());
        assertEquals(5, loaded.getPasswordReusePrevention());
        assertTrue(loaded.isHardExpiry());

        restarted.deleteAccountPasswordPolicy();
        IamService afterDelete = newService(dir);
        assertThrows(AwsException.class, afterDelete::getAccountPasswordPolicy);
    }

    private IamService newService(Path dir) {
        return new IamService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                load(dir, "iam-password-policy.json"),
                new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"),
                false
        );
    }

    private StorageBackend<String, PasswordPolicy> load(Path dir, String file) {
        PersistentStorage<String, PasswordPolicy> backend = new PersistentStorage<>(
                dir.resolve(file),
                new TypeReference<Map<String, PasswordPolicy>>() {}
        );
        backend.load();
        return backend;
    }
}
