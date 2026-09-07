package work.tokenpro.client;

import java.util.*;

final class SelfTest {
    static void run() {
        int passed = 0;
        Map<String, Object> value = Json.object(Json.parse("{\"name\":\"TokenPro\",\"items\":[1,true,null],\"n\":12}"));
        check("TokenPro".equals(value.get("name")), "JSON string"); passed++;
        check(value.get("items") instanceof List<?> list && list.size() == 3, "JSON array"); passed++;
        check(Json.stringify(value).contains("\"TokenPro\""), "JSON writer"); passed++;
        String sample = "before\n# >>> TokenPro managed >>>\nmanaged\n# <<< TokenPro managed <<<\nafter\n";
        check(CodexConfig.stripManaged(sample).equals("before\nafter\n"), "managed config removal"); passed++;
        String config = "model = \"old\"\nmodel_provider = \"openai\"\n[features]\napps = true\n";
        check(CodexConfig.stripRootOverrides(config).equals("[features]\napps = true\n"), "root override removal"); passed++;
        check(Platform.dataDirectory().endsWith("TokenPro"), "platform data directory"); passed++;
        System.out.println("TokenPro Java self-test: " + passed + " checks passed");
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
