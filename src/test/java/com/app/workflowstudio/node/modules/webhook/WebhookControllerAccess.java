package com.app.workflowstudio.node.modules.webhook;

/** 테스트용: 패키지 안의 바디 해석 함수 열기 */
public final class WebhookControllerAccess {
    private WebhookControllerAccess() {}

    public static String decode(byte[] raw, String contentType) {
        return WebhookController.decodeBody(raw, contentType);
    }
}
