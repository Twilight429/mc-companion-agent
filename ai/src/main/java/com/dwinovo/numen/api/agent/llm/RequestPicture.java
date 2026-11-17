package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.agent.catalog.Api;
import java.util.Set;

/** 一轮请求的现场图像,不进入 ConvoState、落盘历史或整理记忆。 */
public record RequestPicture(String description, String pngDataUrl) {
    private static final Set<String> FLASH = Set.of("deepseek-flash", "deepseek-v4-flash",
            "deepseek-v4-flash-vision-exp");

    public RequestPicture {
        if (description == null || description.length() > 2048)
            throw new IllegalArgumentException("invalid picture description");
        if (pngDataUrl != null && (!pngDataUrl.startsWith("data:image/png;base64,")
                || pngDataUrl.length() > 1_400_000))
            throw new IllegalArgumentException("invalid inline camera image");
    }

    /** 只向官方已声明支持视觉的 Flash 型号发送;不把 Pro 当作视觉模型。 */
    public static boolean supported(LlmEndpoint endpoint) {
        return endpoint.api() == Api.CHAT && endpoint.providerId().equals("deepseek")
                && FLASH.contains(endpoint.model().id());
    }

    @Override public String toString() {
        return "RequestPicture[" + description + ", image=" + (pngDataUrl != null) + "]";
    }
}
