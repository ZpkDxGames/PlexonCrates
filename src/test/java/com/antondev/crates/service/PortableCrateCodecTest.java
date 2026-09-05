package com.antondev.crates.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PortableCrateCodecTest {
    private static final byte[] SECRET = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    @Test
    void signedPortableTokenRoundTrips() {
        UUID player = UUID.randomUUID();
        var issued = PortableCrateCodec.issue(
                "basic", PortableCrateCodec.RevisionPolicy.PINNED_REVISION, 4, player, SECRET);
        var decoded = PortableCrateCodec.decodeAndVerify(issued.encoded(), SECRET);

        assertEquals(issued.payload(), decoded.payload());
        assertEquals(player, decoded.payload().issuedTo());
        assertTrue(PortableCrateCodec.verifies(issued.encoded(), SECRET));
    }

    @Test
    void tamperingOrShortSecretsAreRejected() {
        var issued = PortableCrateCodec.issue(
                "basic", PortableCrateCodec.RevisionPolicy.LATEST_PUBLISHED, 0, null, SECRET);
        int changed = issued.encoded().indexOf('.') + 4;
        char replacement = issued.encoded().charAt(changed) == 'A' ? 'B' : 'A';
        String tampered = issued.encoded().substring(0, changed) + replacement
                + issued.encoded().substring(changed + 1);

        assertFalse(PortableCrateCodec.verifies(tampered, SECRET));
        assertThrows(IllegalArgumentException.class,
                () -> PortableCrateCodec.sign(issued.payload(), new byte[8]));
    }
}
