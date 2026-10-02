package io.edgeai.app.support;

import io.edgeai.app.exception.ProfilePayloadTooLargeException;
import io.edgeai.domain.profile.ProfileIdentity.Kind;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProfileJsonTest {
    private final ProfileJson json = new ProfileJson();
    private String body(String spec) { return "{\"key\":\"sensor\",\"version\":\"1.0.0\",\"spec\":" + spec + "}"; }
    @Test void canonicalizesNestedKeysAndNumbersButPreservesArrayOrder() {
        var a = json.parse(Kind.DEVICE, body("{\"z\":[1.00,2],\"a\":{\"n\":-0.0,\"b\":true}}"));
        var b = json.parse(Kind.DEVICE, body("{\"a\":{\"b\":true,\"n\":0},\"z\":[1,2.0]}"));
        assertThat(a.spec()).isEqualTo("{\"a\":{\"b\":true,\"n\":0},\"z\":[1,2]}");
        assertThat(a.digest()).isEqualTo(b.digest()).matches("sha256:[a-f0-9]{64}");
        assertThat(json.parse(Kind.SERVICE, body("{\"a\":{\"b\":true,\"n\":0},\"z\":[1,2]}")).digest()).isNotEqualTo(a.digest());
        assertThat(json.parse(Kind.DEVICE, body("{\"z\":[2,1],\"a\":{\"b\":true,\"n\":0}}")).digest()).isNotEqualTo(a.digest());
    }
    @Test void preservesPrecisionAndUnicode() {
        assertThat(json.parse(Kind.DEVICE, body("{\"값\":9007199254740993,\"d\":1.234567890123456789}")).spec())
            .isEqualTo("{\"d\":1.234567890123456789,\"값\":9007199254740993}");
    }
    @Test void rejectsInvalidDocumentsAndIdentifiers() {
        for (String spec : new String[]{"{}", "[]", "null", "{\"a\":1,\"a\":2}", "{\"n\":1e1001}", "{\"n\":\"\\u0000\"}", "{\"n\":\"\\uD800\"}", "{\"n\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}"})
            assertThatThrownBy(() -> json.parse(Kind.DEVICE, body(spec))).isInstanceOf(IllegalArgumentException.class);
        for (String invalid : new String[]{body("{\"a\":1}").replace("sensor", "UPPER"), body("{\"a\":1}").replace("1.0.0", "01.0.0"), body("{\"a\":1}") + "{}", body("{\"a\":1}").replace("\"spec\"", "\"unknown\"")})
            assertThatThrownBy(() -> json.parse(Kind.DEVICE, invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsOversizedDocuments() {
        assertThatThrownBy(() -> json.parse(Kind.DEVICE, body("{\"a\":\"" + "한".repeat(22000) + "\"}")))
            .isInstanceOf(ProfilePayloadTooLargeException.class);
    }
}
