package com.dwinovo.numen.e2e;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import static com.dwinovo.numen.e2e.FakeModel.lua;
import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.*;

/** 测试端点必须能模拟“工具回执和新插话同批”，否则不能用它判定真实大脑是否丢输入。 */
class FakeModelSteeringTest extends Scenario {
    @Test void new_input_is_not_hidden_by_a_previous_tool_receipt() throws Exception {
        MODEL.whenHeard("start old request", lua("print(\"old\")"), say("outdated-final-answer"));
        MODEL.whenHeard("new owner instruction", say("new-input-handled"));
        String first = post(List.of(Map.of("role", "user", "content", "start old request")));
        var id = Pattern.compile("\"id\":\"(call_e2e_\\d+)\"").matcher(first);
        assertTrue(id.find(), first);
        String next = post(List.of(Map.of("role", "assistant", "content", ""),
                Map.of("role", "tool", "tool_call_id", id.group(1), "content", "actual-tool-receipt"),
                Map.of("role", "user", "content", "new owner instruction")));
        assertTrue(next.contains("new-input-handled"), next);
        assertFalse(next.contains("outdated-final-answer"), next);
        var asked = MODEL.asked().getLast();
        assertTrue(asked.fromHuman());
        assertEquals(List.of("actual-tool-receipt"), asked.toolResults());
    }

    private static String post(List<Map<String, String>> messages) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + SPEC.host() + ":" + SPEC.modelPort() + "/v1/chat/completions"))
                .header("Authorization", "Bearer " + SPEC.modelKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(Map.of("messages", messages))))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return response.body();
    }
}
