package com.app.workflowstudio.node;

/** 파이프라인 실행이 시작된 경로 */
public enum RunMode {
    /** 에디터/목록의 ▶ 실행 버튼 */
    MANUAL,
    /** 웹훅 테스트 URL 수신 (/webhook-test/**) — 열려 있는 에디터에 실시간 표시 */
    TEST,
    /** 웹훅 운영 URL 수신 (/webhook/**) — 백그라운드 실행 */
    PROD
}
